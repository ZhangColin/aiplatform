package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;

/**
 * 文件面探针容器活体（#426 路由信号修复；docker daemon 不在整类跳过，对偶 #425
 * 基座 lint 活体口径）：探针改为「已跟踪文件数 − 基座文件基数」（基数随镜像内置
 * 模板 /opt/baseline 现场计数单源）——单测（mock exec 输出）不证 shell 形本身，
 * 本类是真容器里探针行为的唯一实证面：
 * <ul>
 * <li>基座就位、git 惰性初始化前（阶段 0 起跑面）：tracked 0 − 基数 → 负值钳 0；</li>
 * <li>git 成版把基座钉进跟踪面后：tracked≈基数 → 应用文件面 0（基座基数被整额
 * 扣除——#407 厚模板抬高基数不再漂移信号语义的活证）；</li>
 * <li>长出一个应用文件：应用文件面 +1（基线对照法，见 docs/agents/
 * live-test-lane.md 同款口径）。</li>
 * </ul>
 */
@IntegrationTest
@Tag("live")
class ModelRoutingFileCountProbeLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 600;

    @Autowired
    private ModelRoutingAppService modelRouting;

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String workspaceId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        if (workspaceId != null) {
            try {
                workspaceLifecycleAppService.destroy(workspaceId);
            }
            catch (RuntimeException e) {
                // 物理清理尽力而为（记录已随 destroy 删；Docker 残留可重试销毁）
            }
            jdbcTemplate.update("DELETE FROM prj_projects WHERE workspace_id = ?",
                    Long.parseLong(workspaceId));
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_workspace_in_container_when_count_tracked_files_then_baseline_excluded() {
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();

        // 基座在场（探针的 find 腿有东西可数——形态活证）
        int baselineCount = Integer.parseInt(exec(
                "find /opt/baseline -type f -not -path '*/node_modules/*' | wc -l").strip());
        assertThat(baselineCount).as("镜像内置基座模板文件数（/opt/baseline，排除 node_modules）")
                .isPositive();

        // 非 git 工作区（尚未起跑、git 惰性初始化未发生）：探针降级 null＝无此信号
        // （不升档、不断流——既有口径；真实 run 起跑由产出证明闸先 ensureRepo）
        assertThat(modelRouting.countTrackedFiles(workspaceId))
                .as("非 git 工作区降级无信号").isNull();

        // 惰性初始化后的空仓库（阶段 0 起跑面）：tracked 0 − 基数为负，钳 0
        exec("cd /workspace && git init -q -b main ."
                + " && git config user.name probe && git config user.email probe@local");
        assertThat(modelRouting.countTrackedFiles(workspaceId))
                .as("空仓库（tracked 0 − 基数）钳 0").isZero();

        // 基座钉进跟踪面（模拟收口成版）：tracked＝基座＋杂项（.env 等被 .gitignore
        // 挡在外）→ 应用文件面应＝tracked − 基数（基座整额扣除，不为基座误燃信号）
        exec("cd /workspace && git add -A && git commit -qm probe-baseline");
        int tracked = Integer.parseInt(exec("cd /workspace && git ls-files | wc -l").strip());
        Integer afterBaseline = modelRouting.countTrackedFiles(workspaceId);
        assertThat(afterBaseline).as("基座整额扣除：应用文件面＝tracked − 基数")
                .isEqualTo(Math.max(0, tracked - baselineCount));

        // 长出一个应用文件再成版：应用文件面 +1（对基线对照的差分面）
        exec("cd /workspace && printf 'export const x = 1;\\n' > src/app/probe-route.ts"
                + " && git add -A && git commit -qm probe-app-file");
        assertThat(modelRouting.countTrackedFiles(workspaceId))
                .as("应用文件增量如实入计")
                .isEqualTo(afterBaseline + 1);
    }

    private String exec(String command) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(workspaceId,
                new WorkspaceExecCommand(command));
        assertThat(result.exitCode()).as("探针辅助命令应成功: %s", command).isZero();
        return result.stdout() == null ? "" : result.stdout();
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
