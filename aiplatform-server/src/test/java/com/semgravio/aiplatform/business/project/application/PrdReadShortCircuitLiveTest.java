package com.semgravio.aiplatform.business.project.application;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PRD 读未产出期短路活体（#478，真 docker 工作区 × 标志两态——live 车道防腐面：
 * 本票触碰 exec 缝，短路分支与产出后直读都锚到真容器事实）：
 *
 * <ul>
 * <li><b>标志未落（未产出期）</b>：{@code prd()} 直回 PRJ_015——工作区<b>零
 *     exec</b>（不进 awaitReady、不碰 docker，创建后首请求即时应答）；</li>
 * <li><b>标志已落（savePrd 收口后）</b>：真 exec 直读 {@code docs/PRD.md}
 *     ——内容 + 文件 mtime（与正文同一事实源）。</li>
 * </ul>
 *
 * <p>间谍＝真实方法执行＋可数 exec（对偶 {@link SessionFormSwitchLiveTest} 口径）；
 * 写文件命令 heredoc 单引号定界（①族纪律——Windows argv 链不剥、内容不逃逸）；
 * daemon 不在本机时跳过。</p>
 */
@IntegrationTest
@Tag("live")
class PrdReadShortCircuitLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 600;

    /** 真服务走真 docker（间谍＝真实方法执行＋可验证交互）。 */
    @MockitoSpyBean
    private WorkspaceLifecycleAppService workspaceLifecycle;

    @Autowired
    private ProjectQueryAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String workspaceId;

    private Long projectId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        if (workspaceId != null) {
            try {
                workspaceLifecycle.destroy(workspaceId);
            }
            catch (RuntimeException e) {
                // 物理清理尽力而为（Docker 残留可重试销毁）
            }
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_real_workspace_when_prd_read_then_flag_gates_exec() {
        // 置备真工作区＋项目（标志未落＝未产出期，访谈未收口常态）
        workspaceId = workspaceLifecycle.create(new CreateWorkspaceCommand(EnvKind.DEV))
                .workspaceId();
        Project project = Project.create("PRD 短路活体项目", null, null,
                Long.parseLong(workspaceId), 1L, null);
        projectId = projectRepository.save(project).getId();
        clearInvocations(workspaceLifecycle); // 置备自身的 exec 不计入观察窗

        // 未产出期短路（#478）：PRJ_015 直回——本工作区零 exec（不 awaitReady）
        assertThatThrownBy(() -> appService.prd(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PRD_NOT_PRODUCED.message());
        verify(workspaceLifecycle, never()).exec(eq(workspaceId), any(WorkspaceExecCommand.class));

        // 产出后（savePrd 同款半边：写文件；置位走真聚合）——真 exec 直读内容＋mtime
        exec("mkdir -p /workspace/docs && cat > /workspace/docs/PRD.md <<'PRD_EOF'\n"
                + "# 官网 PRD\n"
                + "\n"
                + "目标：三页官网。\n"
                + "PRD_EOF");
        Project loaded = projectRepository.findById(projectId).orElseThrow();
        loaded.markPrdProduced();
        projectRepository.save(loaded);
        clearInvocations(workspaceLifecycle); // 置备写文件的 exec 不计入读观察窗

        PrdResponse response = appService.prd(projectId);

        assertThat(response.projectId()).isEqualTo(projectId.toString());
        assertThat(response.content()).isEqualTo("# 官网 PRD\n\n目标：三页官网。\n");
        // updatedAt＝文件 mtime（秒精度）——刚写出的新鲜文件，容些许时钟毛刺
        assertThat(response.updatedAt())
                .isAfter(Instant.now().minusSeconds(300));
        verify(workspaceLifecycle, times(1)).exec(eq(workspaceId),
                argThat((WorkspaceExecCommand cmd) -> cmd != null
                        && cmd.command().contains("PRD.md")));
    }

    private void exec(String command) {
        var result = workspaceLifecycle.exec(workspaceId, new WorkspaceExecCommand(command));
        assertThat(result.exitCode()).as("置备命令应成功: %s", command).isZero();
    }

    private static boolean dockerAvailable() {
        try {
            // ②族纪律：不要输出就 DISCARD 直落 NUL——未读管道＋waitFor 在 Windows
            // 必死锁（docker info 输出可超匿名管道容量）
            return new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        }
        catch (Exception e) {
            return false;
        }
    }
}
