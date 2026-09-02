package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 逐修改刷新（#49）：直播步骤边界（live-step，step≥2 = 一次完整修改落定）→
 * 平台侧探活（8081，与收口核验同判据）→ 通过才发 {@code preview-updated} 通知；
 * 探活失败/异常不发射、不炸编码 run。主缝 = 智能体边界 mock（脚本化直播步骤序列
 * 经捕获的 sink 逐帧推入）+ 工作区 exec mock（探活结果脚本化，按命令内容区分）。
 */
@SpringBootTest
class LiveStepPreviewRefreshTest {

    private static final long OWNER = 3897654321098765432L;

    /** 探活异步轨道的收敛上限（专职单线程 FIFO，正常毫秒级）。 */
    private static final long PROBE_SETTLE_MS = 5000;

    @Autowired
    private GenerationAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private KnowledgePort knowledgePort;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    /** 轨道内联（异步生成轨道在测试线程上同步跑完）。 */
    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }

    /** 工作区 exec 全成功（AGENTS.md 写入 / 探活 / 收口核验通用底桩）。 */
    private void givenExecSucceeds() {
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
    }

    /**
     * 脚本化智能体边界：往捕获的流桥 sink 逐帧推直播序列（自述段 + 步骤段），
     * 模拟 mapper 在模型调用边界的产出——探活装饰正是挂在这条 sink 链上。
     */
    private void givenConverseEmittingLiveSteps(int... steps) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(new AgentEvent(AgentEventTypes.LIVE_TEXT, Map.of(
                    AgentStreamAppService.RUN_FIELD, command.runId(),
                    AgentEventTypes.LIVE_TEXT_FIELD, "正在创建首页。")));
            for (int step : steps) {
                sink.accept(new AgentEvent(AgentEventTypes.LIVE_STEP, Map.of(
                        AgentStreamAppService.RUN_FIELD, command.runId(),
                        AgentEventTypes.LIVE_STEP_FIELD, step)));
            }
            return new AgentReply(command.runId(), "系统已生成");
        });
    }

    @Test
    void given_completed_steps_and_probe_ok_when_generate_then_notification_per_step() {
        Long projectId = persistedProject("9820");
        givenSessionExecutorRunsInline();
        givenExecSucceeds();
        givenConverseEmittingLiveSteps(1, 2, 3);

        appService.startGeneration(projectId);

        // 刷新信号 = 步骤边界且 step≥2（step1 是起跑边界无完整修改）：恰两次通知，
        // 探活通过后发射（异步专职线程，timeout 收敛）
        verify(notificationAppService, timeout(PROBE_SETTLE_MS).times(2))
                .publish(eq(ProjectEventTypes.PREVIEW_UPDATED), argThat(payload ->
                        projectId.toString().equals(
                                payload.get(ProjectEventTypes.PROJECT_ID_FIELD))));
        // 帧原样透传不因装饰丢帧：live-step 照发智能体流通道（含 projectId 注入）
        verify(streamAppService, timeout(PROBE_SETTLE_MS).times(3))
                .publish(eq(AgentEventTypes.LIVE_STEP), argThat(payload ->
                        projectId.toString().equals(
                                payload.get(AgentStreamAppService.PROJECT_FIELD))));
        // run 照常成功收口（刷新装饰不改变收口行为）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_probe_not_serving_when_generate_then_no_notification() {
        Long projectId = persistedProject("9821");
        givenSessionExecutorRunsInline();
        givenExecSucceeds(); // AGENTS.md 写入等非探活命令成功
        givenConverseEmittingLiveSteps(1, 2, 3);
        // 探活不可达（应用未起服——待期常态）：curl 退出码非 0（收口核验同判据同桩，
        // 生成走重试属预期；断言面只在「不发射刷新通知」）
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7));

        appService.startGeneration(projectId);

        // 探活失败不发射（保最后好状态）——exitCode 非 0 后无发射路径，判定确定；
        // 探针确已执行过（不是没触发；重试各尝试均探，atLeast）
        verify(workspaceLifecycleAppService, timeout(PROBE_SETTLE_MS).atLeastOnce())
                .exec(eq("9821"), argThat((WorkspaceExecCommand cmd) ->
                        cmd.command().equals(GenerationAppService.CLOSING_PROBE)));
        verify(notificationAppService, never())
                .publish(any(), argThat(payload ->
                        projectId.toString().equals(
                                payload.get(ProjectEventTypes.PROJECT_ID_FIELD))));
    }

    @Test
    void given_probe_throws_when_generate_then_no_notification_and_run_unharmed() {
        Long projectId = persistedProject("9822");
        givenSessionExecutorRunsInline();
        // 探针期 curl 抛环境异常（容器抖动等），两针都打过且抛过后收口核验转成功桩
        // ——隔离「探活异常」与「收口核验」，断言炸点只在探活轨道
        AtomicBoolean probesThrow = new AtomicBoolean(true);
        CountDownLatch probesObserved = new CountDownLatch(2);
        when(workspaceLifecycleAppService.exec(anyString(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand cmd = invocation.getArgument(1);
            if (cmd.command().contains("curl") && probesThrow.get()) {
                probesObserved.countDown();
                throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
            }
            return new ExecResultResponse("", "", 0);
        });
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(new AgentEvent(AgentEventTypes.LIVE_STEP, Map.of(
                    AgentStreamAppService.RUN_FIELD, command.runId(),
                    AgentEventTypes.LIVE_STEP_FIELD, 2)));
            sink.accept(new AgentEvent(AgentEventTypes.LIVE_STEP, Map.of(
                    AgentStreamAppService.RUN_FIELD, command.runId(),
                    AgentEventTypes.LIVE_STEP_FIELD, 3)));
            // 等两针都执行过（都抛了）再收口——此后收口核验走成功桩
            assertThat(await(probesObserved)).isTrue();
            probesThrow.set(false);
            return new AgentReply(command.runId(), "系统已生成");
        });

        appService.startGeneration(projectId);

        // 探活异常不发射通知、不炸编码 run：流帧照发、run 成功收口、generated_at 落位
        // （两针已在 converse 内确认执行完毕——异常被吞，此后无发射路径，never 确定）
        verify(streamAppService, timeout(PROBE_SETTLE_MS).times(2))
                .publish(eq(AgentEventTypes.LIVE_STEP), anyMap());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
        verify(notificationAppService, never())
                .publish(any(), argThat(payload ->
                        projectId.toString().equals(
                                payload.get(ProjectEventTypes.PROJECT_ID_FIELD))));
    }

    @Test
    void given_step_one_or_non_step_frames_only_when_generate_then_no_notification() {
        Long projectId = persistedProject("9823");
        givenSessionExecutorRunsInline();
        givenExecSucceeds();
        // 只有起跑边界（step1）与非步骤帧：无完整修改落定，不触发刷新信号（无探针任务）
        givenConverseEmittingLiveSteps(1);

        appService.startGeneration(projectId);

        verify(notificationAppService, never())
                .publish(eq(ProjectEventTypes.PREVIEW_UPDATED), anyMap());
        // run 照常成功（无刷新信号不影响生成）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    // ---------- 测试数据与工具 ----------

    /** 可生成形态的项目（PRD 已产出、未生成、未归档）。 */
    private Long persistedProject(String workspaceId) {
        Project project = Project.create("逐修改刷新项目", null, Long.parseLong(workspaceId), OWNER);
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(PROBE_SETTLE_MS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
