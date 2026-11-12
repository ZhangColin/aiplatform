package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 生成会话形态两开关两态活体（#436，#432 Step 2 的 live 车道防腐面——编排缝的
 * workspace/执行会话寻址行为锚到「真 docker 工作区 × 开关两态」，对偶
 * {@link ClosingGatesSwitchLiveTest} 口径）：智能体边界（
 * {@link AgentscopeAgentClient}）与收口探针（{@link ClosingProbes}）mock 截住
 * （live 车道不烧真模型 token、不要求容器真起服），workspace 生命周期走真 docker
 * ——fork/合并/git 现场全是真容器事实：
 *
 * <ul>
 * <li><b>缺省态（#436 落地态）</b>：无配置行＝单会话开＋并行机器关——阶段 0＋切片
 *     全部寻址 {@code coder-{projectId}-single} 恒定槽位、零 fork；切片失败耗尽
 *     后<b>工作区无 {@code git reset --hard}</b>（段级回滚钩不传、失败现场原样
 *     保留）；「继续生成」从同槽位续作、脏续盘点喂入；</li>
 * <li><b>切片机器态（运营切回旧行为）</b>：executor 行单会话关＋并行机器开——
 *     每片新会话（{@code coder-{projectId}-slice-n}）＋宽波真 fork 并行。</li>
 * </ul>
 *
 * <p>开关读点动态查库（不发版）：本类直写库行即切态，跑完清理（不留运营态）。
 * 探针/置备命令一律单引号形态（①族纪律）。</p>
 */
@IntegrationTest
@Tag("live")
class SessionFormSwitchLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 1200;

    /** 真服务走真 docker（间谍＝真实方法执行＋可验证交互）。 */
    @MockitoSpyBean
    private WorkspaceLifecycleAppService workspaceLifecycle;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private ClosingProbes closingProbes;

    @Autowired
    private GenerationAppService appService;

    @Autowired
    private GenerationProperties properties;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String workspaceId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @BeforeEach
    void seedChannelKey() {
        // 命令构建的真解析链（ADR-0031）需要测试 key（假值——智能体边界已 mock）
        ModelTierSeed.ensureChannelKey(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_agent_configs WHERE agent_key = 'executor'");
        jdbcTemplate.update("DELETE FROM prj_generation_segments");
        jdbcTemplate.update("DELETE FROM prj_generation_plan_journeys");
        jdbcTemplate.update("DELETE FROM prj_demo_versions");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        if (workspaceId != null) {
            try {
                workspaceLifecycle.destroy(workspaceId);
            }
            catch (RuntimeException e) {
                // 物理清理尽力而为（Docker 残留可重试销毁）
            }
            jdbcTemplate.update("DELETE FROM prj_projects WHERE workspace_id = ?",
                    Long.parseLong(workspaceId));
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_default_state_when_track_then_single_session_no_fork_no_rollback_dirty_resume()
            throws Exception {
        // #436 AC①③ 缺省态 live 面：无配置行＝单会话开＋并行机器关——真容器里
        // 恒定槽位连续追加、零 fork；切片失败耗尽不回滚（无 git reset --hard）；
        // 断点续跑同槽位脏续
        Long projectId = persistedProjectWithRealWorkspace();
        givenSessionExecutorRunsInline();
        int maxAttempts = properties.getMaxAttempts();
        AtomicInteger converseCalls = new AtomicInteger();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            var command = invocation.<com.semgravio.aiplatform.base.agentscope.AgentCommand>getArgument(0);
            int call = converseCalls.incrementAndGet();
            // 第 2 ~ 1+maxAttempts 次＝切片 1 的各次尝试（首试与重试 prompt 措辞
            // 不同——按调用序甄别），全抛＝重试耗尽转终态失败
            if (call >= 2 && call <= 1 + maxAttempts) {
                throw new IllegalStateException("本片起服反复失败");
            }
            return new AgentReply(command.runId(), "完成");
        });

        BuildPlan plan = new BuildPlan(List.of("用户能注册登录", "用户能下单支付"));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        // 阶段 0 收口、切片 1 失败、切片 2 被依赖阻塞待跑；全轨恒定单会话槽位
        assertThat(converseCalls.get()).isEqualTo(1 + maxAttempts);
        assertThat(segmentStatuses(projectId)).containsExactly(2, 3, 1);
        assertThat(converseSessionIds()).containsOnly(GenerationAppService.singleSession(projectId));
        // 切片机器缺省关：零 fork
        verify(workspaceLifecycle, never()).fork(anyString());
        // 单会话：段级回滚钩不传——真容器工作区无任何 git reset --hard（失败现场
        // 原样保留）
        verify(workspaceLifecycle, never()).exec(anyString(),
                argThat((WorkspaceExecCommand cmd) -> cmd != null
                        && cmd.command().contains("reset --hard")));

        // 断点续跑（#221 语义不变）：同槽位续作、脏续盘点喂入、全轨收口
        appService.startGeneration(projectId);

        assertThat(converseCalls.get()).isEqualTo(1 + maxAttempts + 2);
        var resumed = converseSessionIds();
        assertThat(resumed.subList(1 + maxAttempts, resumed.size()))
                .containsOnly(GenerationAppService.singleSession(projectId));
        assertThat(segmentStatuses(projectId)).containsExactly(2, 2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_slice_machine_state_when_wide_wave_then_per_slice_sessions_and_real_forks()
            throws Exception {
        // #436 AC② 切片机器态 live 面：单会话关＋并行机器开＝运营切回旧行为——
        // 每片新会话（slice-n）＋宽波真 fork 并行（fork/合并全是真容器事实）
        givenSessionFormSwitches(false, true);
        Long projectId = persistedProjectWithRealWorkspace();
        givenSessionExecutorRunsInline();
        // 智能体边界收口＝真干活的镜像：mock 的 coder 在本工作区落一个本片标记并
        // git 提交（生产里提交由执行体自己做）——fork 片的合并 diff 取（HEAD^..HEAD
        // 成版对）与主工作区 apply 才有真产物可合
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            var command = invocation.<com.semgravio.aiplatform.base.agentscope.AgentCommand>getArgument(0);
            String marker = "slice-" + command.sessionId();
            var committed = workspaceLifecycle.exec(command.workspaceId(),
                    new WorkspaceExecCommand("cd /workspace && printf 'done\\n' > " + marker + ".txt"
                            + " && git add -A && git commit -qm " + marker));
            assertThat(committed.exitCode()).as("mock coder 本片提交应成功").isZero();
            return new AgentReply(command.runId(), "完成");
        });

        // 三片互不依赖：切片 1 序前段、切片 2/3 同宽波
        BuildPlan plan = new BuildPlan(
                List.of("用户能注册登录", "用户能下单支付", "用户能查看订单"),
                List.of(Set.of(), Set.of(), Set.of()));
        appService.dispatchGenerationOnTurnClose(projectId, plan);

        List<String> sessionIds = converseSessionIds();
        // 序前段（阶段 0＋切片 1）逐片新会话、串行有序
        assertThat(sessionIds.subList(0, 2)).containsExactly(
                GenerationAppService.sliceSession(projectId, 0),
                GenerationAppService.sliceSession(projectId, 1));
        // 宽波两片并行：slice-2/slice-3 各寻各址（提交序不保证——无序断言）
        assertThat(sessionIds.subList(2, 4)).containsExactlyInAnyOrder(
                GenerationAppService.sliceSession(projectId, 2),
                GenerationAppService.sliceSession(projectId, 3));
        // 宽波真 fork（每片一 fork，#351）
        verify(workspaceLifecycle, times(2)).fork(anyString());
        assertThat(segmentStatuses(projectId)).containsExactly(2, 2, 2, 2);
        assertThat(generatedAt(projectId)).isNotNull();
    }

    // ---------- 现场置备 ----------

    /** 真 docker 工作区＋git 基线＋可生成形态项目（PRD 已产出、未生成）。 */
    private Long persistedProjectWithRealWorkspace() {
        var workspace = workspaceLifecycle.create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        // ①族纪律：命令一律单引号形态；git 身份本地配置（收口成版/合并的提交面）
        exec("cd /workspace && git init -q -b main ."
                + " && git config user.name live && git config user.email live@local"
                + " && git add -A && git commit -qm baseline");
        Project project = Project.create("会话形态活体项目", null, null,
                Long.parseLong(workspaceId), 1L, null);
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    /** 智能体边界正常收口：sessionExecutor 直通内联（提交即跑完）。 */
    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(sessionExecutor).submitGeneration(any(), any());
    }

    /** 运营面开关直写（live 面不发版切态的唯一通道）。 */
    private void givenSessionFormSwitches(boolean singleSession, boolean parallelMachine) {
        jdbcTemplate.update("""
                INSERT INTO prj_agent_configs
                    (agent_key, web_search_enabled, fetch_url_enabled, routing_enabled,
                     single_session_generation_enabled, parallel_slice_machine_enabled)
                VALUES ('executor', TRUE, TRUE, TRUE, ?, ?)
                ON CONFLICT (agent_key) DO UPDATE SET
                    single_session_generation_enabled
                        = EXCLUDED.single_session_generation_enabled,
                    parallel_slice_machine_enabled = EXCLUDED.parallel_slice_machine_enabled
                """, singleSession, parallelMachine);
    }

    // ---------- 观测面 ----------

    private List<String> converseSessionIds() {
        var captor = org.mockito.ArgumentCaptor
                .forClass(com.semgravio.aiplatform.base.agentscope.AgentCommand.class);
        // atLeast(0)＝只取已发生调用的捕获语义，不叠加次数断言（调用序经由
        // converseCalls 计数器锚定）
        verify(agentClient, org.mockito.Mockito.atLeast(0)).converse(captor.capture(), any());
        return captor.getAllValues().stream()
                .map(com.semgravio.aiplatform.base.agentscope.AgentCommand::sessionId)
                .toList();
    }

    private List<Integer> segmentStatuses(Long projectId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM prj_generation_segments WHERE project_id = ? ORDER BY ord",
                Integer.class, projectId);
    }

    private java.sql.Timestamp generatedAt(Long projectId) {
        return jdbcTemplate.queryForObject("SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId);
    }

    private String exec(String command) {
        var result = workspaceLifecycle.exec(workspaceId, new WorkspaceExecCommand(command));
        assertThat(result.exitCode()).as("置备命令应成功: %s", command).isZero();
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
