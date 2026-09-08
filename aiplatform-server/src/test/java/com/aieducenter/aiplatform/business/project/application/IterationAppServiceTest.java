package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentSuspension;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.FileChange;
import com.aieducenter.aiplatform.base.agentscope.RunHeading;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 迭代编排（#26 验收 + #46 结束工具收口）：修正 run 与生成同机制（每场新会话
 * coder-{projectId}-fix-{runId}（#114）+ 同工作区 + EXECUTOR 配置 + live + 计量 dims
 * + 知识命中前置注入 +
 * 失败自动静默重试——run 失败为唯一失败终态）；run 在途时新任务排队（不即派）、当前 run 收口后
 * 合并为一场修正续派（排队意见不丢、不逐条烧 run）；收口以 finish_edit 工具事实
 * 为准（未调用=未正常收口按重试/终态；changed=false 发「未动系统+原因」事件，
 * changed=true 现有收口行为不回归）；超限终态恢复出口（#48：重派终态那场的交接
 * 物，正常态 / 在途 / 排队均不可达）；交接物三要素（#52：判定结果 + PRD 路径
 * 引用入修正 run prompt；排队合并 #55 逐轮配对：各轮「意见 → 修订说明」一一
 * 对应、未修订轮显式占位不串位——#53 判定全保留的口径不变，载体从两清单
 * 位置对齐收严为结构化成对）；守卫组（不存在 / 已归档 / 未生成）。
 */
@SpringBootTest
class IterationAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private IterationAppService appService;

    @Autowired
    private RunPermissionAppService runPermissionAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FinishEditFacts finishFixFacts;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private EventsAppService eventsAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private KnowledgePort knowledgePort;

    /** 工作区 exec（#91 版本锚定成版探针的脚本化缝——commit hash 回填 closing）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    /** 时钟（#112 权限确认超时的测试缝——替换 TimeConfig 单点，快进断言超时不真等）。 */
    @MockitoBean
    private Clock clock;

    private final MutableClock mutableClock = new MutableClock(Instant.parse("2026-09-07T00:00:00Z"));

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenAnswer(invocation -> mutableClock.instant());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    /** 轨道任务排队不跑（起跑侧只登记在途）；手动 run 模拟异步轨道执行。 */
    private List<Runnable> givenTrackQueued() {
        List<Runnable> tracks = new ArrayList<>();
        doAnswer(invocation -> {
            tracks.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());
        return tracks;
    }

    /** 脚本化智能体边界：run 正常返回即调 finish_edit（coder 会话才记——忠实于
     * 工具面按角色发放的事实），收口判定随脚本给定。 */
    private void givenConverseFinishing(Boolean changed, String text) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("coder-")) {
                finishFixFacts.record(command.workspaceId(), changed, text);
            }
            return new AgentReply(command.runId(), "修正完成");
        });
    }

    /**
     * 脚本化智能体事件缝（#84 验收）的本地别名：剧本体在
     * {@link AgentEventScripts}（生成/迭代共用，契约变化单点同步）。
     */
    private static AgentEvent scripted(String type, String runId, Map<String, Object> extra) {
        return AgentEventScripts.scripted(type, runId, extra);
    }

    private void givenConverseSucceeds() {
        givenConverseFinishing(true, "已按意见修正");
    }

    @Test
    void given_generated_project_when_fix_then_command_uses_new_fix_session_and_workspace() {
        Long projectId = persistedGeneratedProject("9900");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId,
                "把预约列表按时间倒序排列", null);
        tracks.remove(0).run();

        assertThat(dispatch.queued()).isFalse();
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        AgentCommand value = command.getValue();
        // 修正 run 全要素：每场新会话（#114 fix-{runId}）与同工作区 + EXECUTOR 配置 +
        // owner + 计量 dims + 流关联（与生成同机制）
        assertThat(value.runId()).isEqualTo(dispatch.runId());
        assertThat(value.prompt()).isEqualTo(IterationAppService.fixRunPrompt(
                singleHandoff("把预约列表按时间倒序排列", null)));
        assertThat(value.sessionId()).isEqualTo(
                IterationAppService.fixSession(projectId, dispatch.runId()));
        assertThat(value.workspaceId()).isEqualTo("9900");
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).isEqualTo(AgentProfile.EXECUTOR.systemPrompt());
        assertThat(value.usageContext().dims()).isEqualTo(UsageDims.of(projectId,
                UsageDims.kindOf(AgentProfile.EXECUTOR),
                IterationAppService.fixSession(projectId, dispatch.runId())));
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.agentKey()).isEqualTo("executor"); // run-start 携配置键（前端编码 run 判定锚）
        // #118 工作消息头部标题：更新 run 携平台生成的用户语言标题（无切片进度）
        assertThat(value.heading()).isEqualTo(RunHeading.titled(IterationAppService.FIX_TITLE));
        verify(eventsAppService, never()).publishAgentEvent(eq("role-assigned"), any());
    }

    @Test
    void given_fix_in_flight_when_more_fixes_then_queued_then_merged_into_one_run()
            throws InterruptedException {
        Long projectId = persistedGeneratedProject("9901");
        List<Runnable> tracks = givenTrackQueued();
        // 第一场修正 run 起跑后挂住（模拟真实长任务在途），主线程在在途窗口内派新任务
        CountDownLatch runInFlight = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            runInFlight.countDown();
            releaseRun.await();
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });

        IterationAppService.FixDispatch first = appService.startFixRun(projectId, "列表加筛选", null);
        Thread trackWorker = new Thread(tracks.remove(0));
        trackWorker.start();
        assertThat(runInFlight.await(5, TimeUnit.SECONDS)).isTrue();

        // run 在途：后续任务排队、不即派
        IterationAppService.FixDispatch second = appService.startFixRun(projectId, "按钮改蓝色", null);
        IterationAppService.FixDispatch third = appService.startFixRun(projectId, "加导出", null);
        assertThat(second.queued()).isTrue();
        assertThat(third.queued()).isTrue();
        verify(agentClient, times(1)).converse(any(), any());

        // 当前 run 收口（轨道循环排空队列）：两条排队任务合并为一场续派，不逐条烧 run
        releaseRun.countDown();
        trackWorker.join(5000);
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        List<AgentCommand> runs = command.getAllValues();
        assertThat(runs.get(1).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(new IterationAppService.FixHandoff(
                        List.of(new IterationAppService.FixHandoff.Round("按钮改蓝色", null),
                                new IterationAppService.FixHandoff.Round("加导出", null)))))
                .contains("1. 意见原文：按钮改蓝色").contains("2. 意见原文：加导出");
        // 排队合并续派是另一场 run：新会话（#114）与新 runId（不续首场会话）
        assertThat(runs.get(1).sessionId()).isEqualTo(
                IterationAppService.fixSession(projectId, runs.get(1).runId()));
        assertThat(runs.get(1).runId()).isNotEqualTo(first.runId());
        // 轨道收工（队列空）：在途释放——下一场意见可再起跑
        assertThat(appService.startFixRun(projectId, "再来一轮", null).queued()).isFalse();
    }

    /**
     * 权限确认挂起的脚本化缝（#83）：首场修正 run 触发需批准操作（permission-required
     * 事件 + 软终点挂起面），续跑与后续对话正常收口——配合作答分支驱动轨道分岔。
     */
    private void givenConverseSuspendsOnce(String engineRef, String resumeText) {
        java.util.concurrent.atomic.AtomicBoolean suspendedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (suspendedOnce.compareAndSet(false, true)) {
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent(AgentEventTypes.PERMISSION_REQUIRED,
                        new LinkedHashMap<>(Map.of(
                                "runId", command.runId(),
                                AgentEventTypes.WAIT_ENGINE_REF_FIELD, engineRef,
                                AgentEventTypes.WAIT_SUMMARY_FIELD, "rm -rf /workspace/data",
                                AgentEventTypes.WAIT_DATA_FIELD, Map.of("toolCalls", List.of(
                                        Map.of("id", "tc-9", "name", "command",
                                                "input", Map.of("command", "rm -rf /workspace/data"))))))));
                return new AgentReply(command.runId(), "需要确认", new AgentSuspension(
                        engineRef, false, List.of(Map.of(
                                "id", "tc-9", "name", "command",
                                "input", Map.of("command", "rm -rf /workspace/data")))));
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation -> {
            AgentResume resume = invocation.getArgument(0);
            finishFixFacts.record(resume.workspaceId(), true, resumeText);
            return new AgentReply(resume.runId(), "续跑收口");
        });
    }

    @Test
    void given_permission_suspension_when_approved_then_same_run_resumes_and_track_settles()
            throws InterruptedException {
        // #83 批准分岔（端到端·轨道级脚本化）：需批准操作 → permission-required 事件 +
        // 轨道驻留（不收口/不重试/不发 run-failed）→ 批准 → 同 run 续跑（ConfirmResult
        // 批准位、engineRef 锚）→ finish_edit 事实照常收口 → 队列照常排空
        Long projectId = persistedGeneratedProject("9906");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSuspendsOnce("reply-perm-approve", "批准后完成修正");

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId, "清理临时数据目录", null);
        Thread worker = new Thread(tracks.remove(0));
        worker.start();
        verify(eventsAppService, timeout(5000))
                .publishAgentEvent(eq(AgentEventTypes.PERMISSION_REQUIRED), any());

        // 挂起期间意见照常排队（权限挂起不拦意见链——「更新过程在途」语义不变）
        assertThat(appService.startFixRun(projectId, "顺带改个颜色", null).queued()).isTrue();

        runPermissionAppService.answer(projectId, dispatch.runId(), "reply-perm-approve", true);
        worker.join(5000);

        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().runId()).isEqualTo(dispatch.runId());
        assertThat(resume.getValue().replyId()).isEqualTo("reply-perm-approve");
        assertThat(resume.getValue().sessionId()).isEqualTo(
                IterationAppService.fixSession(projectId, dispatch.runId()));
        assertThat(resume.getValue().confirmResults()).hasSize(1);
        assertThat(resume.getValue().confirmResults().get(0).isConfirmed()).isTrue();
        assertThat(resume.getValue().confirmResults().get(0).getToolCall().getName()).isEqualTo("command");
        // 落定事件（确认卡转已批终态）与排队合并续派（第二场对话正常收口）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.PERMISSION_RESOLVED), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), any());
        verify(agentClient, times(2)).converse(any(), any());
    }

    @Test
    void given_permission_suspension_when_denied_then_run_continues_with_denied_result()
            throws InterruptedException {
        // #83 拒绝分岔：拒绝 = ConfirmResult(confirmed=false) 续跑（引擎写 DENIED 工具结果
        // 回模型，模型改道或如实收口——run 不终止），轨道照常收口
        Long projectId = persistedGeneratedProject("9907");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSuspendsOnce("reply-perm-deny", "改用其他方式完成修正");

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId, "重装依赖", null);
        Thread worker = new Thread(tracks.remove(0));
        worker.start();
        verify(eventsAppService, timeout(5000))
                .publishAgentEvent(eq(AgentEventTypes.PERMISSION_REQUIRED), any());

        runPermissionAppService.answer(projectId, dispatch.runId(), "reply-perm-deny", false);
        worker.join(5000);

        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().confirmResults().get(0).isConfirmed()).isFalse();
        assertThat(resume.getValue().resumeText()).contains("拒绝");
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.PERMISSION_RESOLVED), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), any());
    }

    @Test
    void given_permission_suspension_when_timeout_then_run_failed_without_retry()
            throws InterruptedException {
        // #112 超时收口（端到端·轨道级）：需批准操作挂起 → 时钟越过 10 分钟 → 超时默认
        // 拒绝——发 permission-timed-out（确认卡「已超时」）+ run-failed 收口，
        // 不复用静默重试（converse 恰一次、resume 零次——重试同上下文同命令必然再挂）
        Long projectId = persistedGeneratedProject("9914");
        List<Runnable> tracks = givenTrackQueued();
        String engineRef = "reply-perm-timeout";
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(new AgentEvent(AgentEventTypes.PERMISSION_REQUIRED,
                    new LinkedHashMap<>(Map.of(
                            "runId", command.runId(),
                            AgentEventTypes.WAIT_ENGINE_REF_FIELD, engineRef,
                            AgentEventTypes.WAIT_SUMMARY_FIELD, "rm -rf /workspace/data",
                            AgentEventTypes.WAIT_DATA_FIELD, Map.of("toolCalls", List.of(
                                    Map.of("id", "tc-9", "name", "command",
                                            "input", Map.of("command", "rm -rf /workspace/data"))))))));
            return new AgentReply(command.runId(), "需要确认", new AgentSuspension(
                    engineRef, false, List.of(Map.of(
                            "id", "tc-9", "name", "command",
                            "input", Map.of("command", "rm -rf /workspace/data")))));
        });

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId, "清理临时数据目录", null);
        Thread worker = new Thread(tracks.remove(0));
        worker.start();
        verify(eventsAppService, timeout(5000))
                .publishAgentEvent(eq(AgentEventTypes.PERMISSION_REQUIRED), any());

        // 快进时钟越过 10 分钟上限 → 超时默认拒绝（不真等 10 分钟）
        mutableClock.advance(Duration.ofMinutes(11));
        worker.join(5000);

        // 确认卡「已超时」定格事件 + run-failed 收口（不复用静默重试）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.PERMISSION_TIMED_OUT), any());
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), any());
        // 不复用静默重试：converse 恰一次（无第二次尝试）、resume 零次（不续跑同命令）
        verify(agentClient, times(1)).converse(any(), any());
        verify(agentClient, never()).resume(any(), any());
    }

    @Test
    void given_retry_attempt_suspends_when_approved_then_user_facing_anchor_is_first_run_id()
            throws InterruptedException {
        // #84 × #83 交叉面：首试中途错误静默重试，重试尝试（内部新 runId）触发权限
        // 挂起——确认卡事件归一首试 runId（用户面 run 身份不变），作答校验以首试
        // runId 为锚（前端按所见作答）；续跑引擎侧仍锚当次尝试的内部 runId
        Long projectId = persistedGeneratedProject("9913");
        List<Runnable> tracks = givenTrackQueued();
        String engineRef = "reply-perm-retry";
        when(agentClient.converse(any(), any()))
                // 首试：中途错误（error 事件 + 异常上浮）
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                            "prompt", command.prompt(), "role", "CODER")));
                    sink.accept(scripted(AgentEventTypes.ERROR, command.runId(), Map.of(
                            AgentEventTypes.ERROR_MESSAGE_FIELD, "首次尝试中断")));
                    throw new IllegalStateException("首次尝试中断");
                })
                // 重试尝试：重开场（应被投影滤掉）后挂起权限确认
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                            "prompt", command.prompt(), "role", "CODER")));
                    sink.accept(new AgentEvent(AgentEventTypes.PERMISSION_REQUIRED,
                            new LinkedHashMap<>(Map.of(
                                    "runId", command.runId(),
                                    AgentEventTypes.WAIT_ENGINE_REF_FIELD, engineRef,
                                    AgentEventTypes.WAIT_SUMMARY_FIELD, "rm -rf /workspace/data",
                                    AgentEventTypes.WAIT_DATA_FIELD, Map.of("toolCalls", List.of(
                                            Map.of("id", "tc-9", "name", "command",
                                                    "input", Map.of("command", "rm -rf /workspace/data"))))))));
                    return new AgentReply(command.runId(), "需要确认", new AgentSuspension(
                            engineRef, false, List.of(Map.of(
                                    "id", "tc-9", "name", "command",
                                    "input", Map.of("command", "rm -rf /workspace/data")))));
                });
        when(agentClient.resume(any(), any())).thenAnswer(invocation -> {
            AgentResume resume = invocation.getArgument(0);
            finishFixFacts.record(resume.workspaceId(), true, "批准后完成修正");
            return new AgentReply(resume.runId(), "续跑收口");
        });

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId, "清理临时数据目录", null);
        Thread worker = new Thread(tracks.remove(0));
        worker.start();
        // 确认卡事件锚 = 首试 runId（重试尝试的内部 runId 不出用户面）
        ArgumentCaptor<Map<String, Object>> requiredPayload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, timeout(5000)).publishAgentEvent(
                eq(AgentEventTypes.PERMISSION_REQUIRED), requiredPayload.capture());
        assertThat(requiredPayload.getValue().get(AgentEventTypes.RUN_FIELD))
                .isEqualTo(dispatch.runId());

        // 作答以首试 runId 为锚（校验通过即证明挂起会合登记的是用户面身份）
        runPermissionAppService.answer(projectId, dispatch.runId(), engineRef, true);
        worker.join(5000);

        // 续跑引擎侧锚当次尝试的内部 runId（第二次 converse 的命令）；批准位落定
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(commands.capture(), any());
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().runId()).isEqualTo(commands.getAllValues().get(1).runId());
        assertThat(resume.getValue().confirmResults().get(0).isConfirmed()).isTrue();

        // 全程静默：run-start 恰一次（首试）、零 error、零 run-failed；轨道正常收工
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_START), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.ERROR), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), any());
        assertThat(appService.startFixRun(projectId, "下一场", null).queued()).isFalse();
    }

    @Test
    void given_queued_tasks_when_track_not_run_yet_then_in_flight_covers_queued_start() {
        // 已提交未起跑（排队中）也算在途：重复派发仍排队，不并发起第二条轨道
        Long projectId = persistedGeneratedProject("9902");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "第一条（轨道已提交未起跑）", null);
        assertThat(appService.startFixRun(projectId, "第二条", null).queued()).isTrue();
        verify(agentClient, never()).converse(any(), any());

        // 轨道起跑：第一条跑完即合并第二条（同一场合并语义）
        tracks.remove(0).run();
        verify(agentClient, times(2)).converse(any(), any());
    }

    @Test
    void given_knowledge_hits_when_fix_then_prefix_injected_before_task_prompt() {
        Long projectId = persistedGeneratedProject("9903");
        List<Runnable> tracks = givenTrackQueued();
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "连锁诊所系统", "PRD·连锁诊所管理", "范围边界：不含库存。")));
        givenConverseSucceeds();

        String task = "给库存页加分页";
        appService.startFixRun(projectId, task, null);
        tracks.remove(0).run();

        // 知识命中前置注入（#24 生成/修正同机制）：query = 修正任务 prompt，命中拼前
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .startsWith("【平台知识库·相似历史需求】")
                .contains("连锁诊所系统")
                .endsWith("————\n\n" + IterationAppService.fixRunPrompt(singleHandoff(task, null)));
    }

    @Test
    void given_first_attempt_fails_when_fix_then_silent_retry_with_retry_prompt() {
        Long projectId = persistedGeneratedProject("9904");
        List<Runnable> tracks = givenTrackQueued();
        // 剧本（#84 事件序列断言）：首试中途错误（error 事件 + 异常上浮）；重试尝试
        //（内部新 runId）真实客户端会再发 run-start 与部件，收口 run-finish
        when(agentClient.converse(any(), any()))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                            "prompt", command.prompt(), "role", "CODER")));
                    sink.accept(scripted(AgentEventTypes.ERROR, command.runId(), Map.of(
                            AgentEventTypes.ERROR_MESSAGE_FIELD, "修正尝试中断")));
                    throw new IllegalStateException("修正尝试中断");
                })
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(), Map.of(
                            "prompt", command.prompt(), "role", "CODER")));
                    sink.accept(scripted(AgentEventTypes.PART_TEXT, command.runId(), Map.of(
                            AgentEventTypes.PART_TEXT_FIELD, "从中断处继续修正")));
                    finishFixFacts.record(command.workspaceId(), true, "重试轮完成修正并收口");
                    return new AgentReply(command.runId(), "修正完成");
                });

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId, "修正首页布局", null);
        tracks.remove(0).run();

        // 失败自动静默重试（#82/#84 口径）：重试续作轨照走，用户面零中间信号——
        // 无重试信号、无逐次 error（run 失败为唯一失败终态）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .isEqualTo(IterationAppService.FIX_RETRY_RUN_PROMPT);
        verify(eventsAppService, never()).publishAgentEvent(eq("run-retrying"), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.ERROR), any());

        // 用户面 run 身份 = 首试 runId 全程不变（#84）：重试不新发 run-start、
        // 重试尝试的部件归一首试锚——工作消息只见正常生长；自检播报（#85）：收口
        // 判据核验（finish_edit 事实）「检查中 → 通过」随正常收口出现
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(4)).publishAgentEvent(types.capture(), payloads.capture());
        assertThat(types.getAllValues())
                .containsExactly(AgentEventTypes.RUN_START, AgentEventTypes.PART_TEXT,
                        AgentEventTypes.PART_CHECK, AgentEventTypes.PART_CHECK);
        assertThat(payloads.getAllValues()).allSatisfy(payload ->
                assertThat(payload.get(AgentEventTypes.RUN_FIELD)).isEqualTo(dispatch.runId()));
        // 自检状态序：检查中 → 通过（首试死于 converse 中途、未进核验，恰一对）
        assertThat(payloads.getAllValues().get(2))
                .containsEntry(AgentEventTypes.PART_CHECK_STATE_FIELD,
                        AgentEventTypes.PART_CHECK_STATE_CHECKING);
        assertThat(payloads.getAllValues().get(3))
                .containsEntry(AgentEventTypes.PART_CHECK_STATE_FIELD,
                        AgentEventTypes.PART_CHECK_STATE_PASSED);

        // 重试成功后轨道正常收工：下一场可再起跑
        assertThat(appService.startFixRun(projectId, "下一场", null).queued()).isFalse();
    }

    @Test
    void given_all_attempts_fail_when_fix_then_terminal_and_track_released() {
        Long projectId = persistedGeneratedProject("9905");
        List<Runnable> tracks = givenTrackQueued();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("持续失败"));

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId, "修不动", null);
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> attempts = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(attempts.capture(), any());
        // 终态收口事件（#56）：run-failed 恰一次、锚该场 run 的用户面标识（首试
        // runId——重试不换新锚，#84）——前端「重新修改」出口只认本事件（点击即恢复
        // 出口重派链路，不被 PRJ_025 挡回）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                projectId.toString().equals(payload.get(EventsAppService.PROJECT_FIELD))
                        && dispatch.runId().equals(payload.get(EventsAppService.RUN_FIELD))));
        // 超限转终态后轨道照常收工释放：用户再提意见即重新起轨（兜底口径）
        assertThat(appService.startFixRun(projectId, "再试一场", null).queued()).isFalse();
    }

    // ---------- 结束工具收口（#46：finish_edit 事实观测；判定呈现归 #88 收口扩载） ----------

    @Test
    void given_finish_edit_changed_false_when_fix_closes_then_normal_close_no_unchanged_frame() {
        // 灵魂用例（#46 → #82 收缩）：脚本化结束工具 changed=false = 正常收口——
        // 判定结果的呈现归收口扩载权威化（#88 收尾卡判定行），本层零事件
        Long projectId = persistedGeneratedProject("9908");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing(false, "纯文档性修订，系统现状已满足");

        appService.startFixRun(projectId, "把首页标题改成「关于我们」", null);
        tracks.remove(0).run();

        // fix-unchanged 已退役（#82）：两态收口都不发
        verify(eventsAppService, never()).publishAgentEvent(eq("fix-unchanged"), any());
        assertThat(appService.startFixRun(projectId, "下一场", null).queued()).isFalse();
    }

    @Test
    void given_finish_edit_changed_true_when_fix_closes_then_no_unchanged_frame() {
        Long projectId = persistedGeneratedProject("9909");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing(true, "已把主色调改为绿色");

        appService.startFixRun(projectId, "把主色调改成绿色", null);
        tracks.remove(0).run();

        // changed=true：现有收口行为不回归——轨道正常收工
        verify(eventsAppService, never()).publishAgentEvent(eq("fix-unchanged"), any());
        assertThat(appService.startFixRun(projectId, "下一场", null).queued()).isFalse();
    }

    @Test
    void given_no_finish_edit_when_converse_returns_then_treated_as_failed_attempt() {
        Long projectId = persistedGeneratedProject("9910");
        List<Runnable> tracks = givenTrackQueued();
        // 首试正常返回但不调 finish_edit（脚本化「模型忘了收口」）→ 判未正常收口、
        // 按既有重试口径续试；重试轮调了 → 正常收口
        when(agentClient.converse(any(), any()))
                .thenAnswer(invocation -> new AgentReply(
                        ((AgentCommand) invocation.getArgument(0)).runId(), "做完了"))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    finishFixFacts.record(command.workspaceId(), true, "重试轮补上收口");
                    return new AgentReply(command.runId(), "修正完成");
                });

        appService.startFixRun(projectId, "修一下分页", null);
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .isEqualTo(IterationAppService.FIX_RETRY_RUN_PROMPT);
        // 未正常收口按重试口径静默（#82/#84）：无 error、无重试信号（与 converse
        // 异常的重试同一口径——run 失败为唯一失败终态）
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.ERROR), any());
        verify(eventsAppService, never()).publishAgentEvent(eq("run-retrying"), any());
        assertThat(appService.startFixRun(projectId, "下一场", null).queued()).isFalse();
    }

    @Test
    void given_no_finish_edit_all_attempts_when_fix_then_terminal_and_track_released() {
        Long projectId = persistedGeneratedProject("9911");
        List<Runnable> tracks = givenTrackQueued();
        // 全部尝试都不调 finish_edit：每次收口判定不过 → 静默重试超限转终态（唯一
        // 失败终态 run-failed 收口，中间 error 不出用户面），轨道收工释放
        when(agentClient.converse(any(), any()))
                .thenAnswer(invocation -> new AgentReply(
                        ((AgentCommand) invocation.getArgument(0)).runId(), "做完了"));

        appService.startFixRun(projectId, "修不动", null);
        tracks.remove(0).run();

        verify(agentClient, times(3)).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.ERROR), any());
        // 自检播报（#85）终态面：逐次「检查中」（尝试间未过不出 ❌），末次未过出 ❌
        // ——❌ 先于 run-failed 到达（同终态窗口）
        ArgumentCaptor<Map<String, Object>> checks = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService, times(4)).publishAgentEvent(eq(AgentEventTypes.PART_CHECK),
                checks.capture());
        assertThat(checks.getAllValues())
                .extracting(payload -> payload.get(AgentEventTypes.PART_CHECK_STATE_FIELD))
                .containsExactly(
                        AgentEventTypes.PART_CHECK_STATE_CHECKING,
                        AgentEventTypes.PART_CHECK_STATE_CHECKING,
                        AgentEventTypes.PART_CHECK_STATE_CHECKING,
                        AgentEventTypes.PART_CHECK_STATE_FAILED);
        InOrder closeOrder = inOrder(eventsAppService);
        closeOrder.verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.PART_CHECK),
                argThat(payload -> AgentEventTypes.PART_CHECK_STATE_FAILED.equals(
                        payload.get(AgentEventTypes.PART_CHECK_STATE_FIELD))));
        closeOrder.verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), any());
        // 未正常收口的超限同样由 run-failed 收口终态（#56）
        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), any());
        assertThat(appService.startFixRun(projectId, "再试一场", null).queued()).isFalse();
    }

    @Test
    void given_stale_fact_from_earlier_track_when_new_track_then_not_consumed_as_verdict() {
        // 轨道起跑清残留：上一轨遗留（或生成 run 误调）的事实不顶本轨的收口判定
        Long projectId = persistedGeneratedProject("9912");
        List<Runnable> tracks = givenTrackQueued();
        finishFixFacts.record("9912", false, "上一轨的旧事实");
        // 本轨全程不调 finish_edit → 旧事实被清、收口判定不过 → 重试超限转终态
        when(agentClient.converse(any(), any()))
                .thenAnswer(invocation -> new AgentReply(
                        ((AgentCommand) invocation.getArgument(0)).runId(), "做完了"));

        appService.startFixRun(projectId, "新一轮意见", null);
        tracks.remove(0).run();

        verify(agentClient, times(3)).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq("fix-unchanged"), any());
    }

    // ---------- 收口扩载（#88：判定行 + 变更清单 + 轮末统计，服务端权威） ----------

    /**
     * 脚本化收口轮（#88 验收缝）：真实事件序（run-start → 解说部件 → run-finish）
     * + finish_edit 事实 + 回复携带文件变更观察（真实客户端的 AgentReply.changes 面）。
     */
    private void givenConverseClosing(Boolean changed, String finishEditText,
            List<FileChange> changes) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.PART_TEXT, command.runId(),
                    Map.of(AgentEventTypes.PART_TEXT_FIELD, "正在按意见更新系统")));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            finishFixFacts.record(command.workspaceId(), changed, finishEditText);
            return new AgentReply(command.runId(), "修正完成", null, changes);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_update_round_when_fix_closes_then_run_finish_carries_authoritative_closing() {
        // 灵魂用例（#88）：脚本化更新轮收口——对话区收尾卡四要素齐（摘要/判定行/
        // 变更清单/统计）且判定行为服务端权威值（PRD 改没改 = 交接物修订说明、
        // 系统改没改 = finish_edit 工具事实，不由模型自报）；清单与统计源 = 工具
        // 调用观察（文件级）+ run 时长
        Long projectId = persistedGeneratedProject("9915");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseClosing(true, "下单页新增配送范围说明", List.of(
                new FileChange("/src/pages/Orders.jsx", 12, 3),
                new FileChange("/src/App.jsx", 4, 0)));

        appService.startFixRun(projectId, "下单页加配送范围说明", "配送范围改为全国");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        // 扩载随被押后的 run-finish 一体到达（收口判据落定后——真收口才扩载）
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing)
                .containsEntry("summary", "修订了需求文档，并更新了系统")
                .containsEntry("prdChanged", true)
                .containsEntry("prdNote", "配送范围改为全国")
                .containsEntry("systemChanged", true)
                .containsEntry("systemNote", "下单页新增配送范围说明");
        // 变更清单：文件级、路径排序稳定
        assertThat((List<Map<String, Object>>) closing.get("files")).containsExactly(
                Map.of("path", "/src/App.jsx", "added", 4, "removed", 0),
                Map.of("path", "/src/pages/Orders.jsx", "added", 12, "removed", 3));
        // 轮末统计：时长在场（≥0——起跑到收口的活动量，非墙钟断言）
        assertThat(closing.get("durationMs")).isInstanceOf(Long.class);
        assertThat((Long) closing.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_update_round_when_fix_closes_then_version_committed_and_hash_in_closing() {
        // 版本锚定（#91）：收口自动成版——commit hash 回填 closing 的 version 键
        // （SSE 扩载与对话史落库同载荷，版本详情复用）
        Long projectId = persistedGeneratedProject("9917");
        List<Runnable> tracks = givenTrackQueued();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0\n", "", 0));
        givenConverseClosing(true, "下单页新增配送范围说明", List.of(
                new FileChange("/src/pages/Orders.jsx", 12, 3)));

        appService.startFixRun(projectId, "下单页加配送范围说明", "配送范围改为全国");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry("version", "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_finish_edit_unchanged_when_fix_closes_then_closing_judgment_reflects_reason() {
        // 判定行的另一半（#88）：系统无需改动——原因随判定行权威承载（旧「编辑无
        // 变化」前端推导退役，权威值到位）；无修订说明的轮 prdChanged=false
        Long projectId = persistedGeneratedProject("9916");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseClosing(false, "页面上没有写死配送范围，都以文档为准", List.of());

        appService.startFixRun(projectId, "把配送范围改成全国", null);
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing)
                .containsEntry("summary", "本轮系统无需改动")
                .containsEntry("prdChanged", false)
                .containsEntry("systemChanged", false)
                .containsEntry("systemNote", "页面上没有写死配送范围，都以文档为准");
        assertThat(closing).doesNotContainKey("prdNote");
        assertThat((List<Map<String, Object>>) closing.get("files")).isEmpty();
    }

    // ---------- 交接物三要素（#52：需求侧判定结果入修正 run prompt） ----------

    @Test
    void given_prd_revised_when_fix_then_prompt_carries_summary_and_prd_reference() {
        // 灵魂用例（#41 Testing Decisions）：summary 终值 + PRD 路径引用都进修正
        // run prompt（不注全文——执行体重读约定见配置）
        Long projectId = persistedGeneratedProject("9919");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "把系统的主色调改成绿色",
                "按意见把主色调相关约定从蓝改为绿（覆盖本条意见）");
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .contains("PRD 已修订")
                .contains("按意见把主色调相关约定从蓝改为绿（覆盖本条意见）")
                .contains(ProjectArtifacts.PRD);
    }

    @Test
    void given_prd_not_revised_when_fix_then_prompt_states_not_revised_and_dispatch_proceeds() {
        // 主智能体流不调 savePrd：交接物如实含「本轮无修订」占位口径，修正 run 照派
        Long projectId = persistedGeneratedProject("9920");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "把系统的主色调改成绿色", null);
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .contains("本轮无修订")
                .doesNotContain("PRD 已修订")
                .contains(ProjectArtifacts.PRD);
    }

    @Test
    void given_queued_rounds_with_and_without_revision_when_merged_then_prompt_pairs_per_round() {
        // 灵魂用例（#55 story 14 收严）：多轮排队合并且部分轮未改 PRD——每条
        // 意见与其修订说明仍一一对应（意见二 ↔ 其修订说明、意见三 ↔ 显式占位），
        // 无修订轮不引起后续轮错位；配对由平台拼装锚定，不靠两清单位置对齐
        Long projectId = persistedGeneratedProject("9921");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "首条意见（在途）", null);
        appService.startFixRun(projectId, "意见二：加导出", "为意见二补充了导出功能章节");
        appService.startFixRun(projectId, "意见三：改蓝色", null);
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .contains("1. 意见原文：意见二：加导出"
                        + "\n   需求侧判定（主智能体已收口）：PRD 已修订——为意见二补充了导出功能章节")
                .contains("2. 意见原文：意见三：改蓝色"
                        + "\n   需求侧判定（主智能体已收口）：本轮无修订（未触发 PRD 变更）");
    }

    @Test
    void given_queued_opinions_each_with_revision_when_track_merges_then_prompt_carries_both_rounds() {
        // 灵魂用例（#53）：意见 A 的轮已把改 X 落盘（summary=改X）在途排队 +
        // 意见 B（summary=改Y）排队 → 当前 run 收口续派时，续派 run 的 prompt 同时
        // 含 X 与 Y 两段判定（各自配对、按派发序）——只带最新一段会让 run 执行体
        // 不知道 X 也是需求侧新变更、需要同步到系统
        Long projectId = persistedGeneratedProject("9932");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "早前意见（在途）", null);
        appService.startFixRun(projectId, "意见A：加导出", "A轮修订：PRD 新增导出功能章节");
        appService.startFixRun(projectId, "意见B：改蓝色", "B轮修订：主色调约定改为蓝");
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        String continuation = command.getAllValues().get(1).prompt();
        assertThat(continuation)
                .contains("1. 意见原文：意见A：加导出"
                        + "\n   需求侧判定（主智能体已收口）：PRD 已修订——A轮修订：PRD 新增导出功能章节")
                .contains("2. 意见原文：意见B：改蓝色"
                        + "\n   需求侧判定（主智能体已收口）：PRD 已修订——B轮修订：主色调约定改为蓝");
    }

    // ---------- 逐轮配对（#55：merge 纯函数——各轮「意见 → 修订说明」成对串联） ----------

    @Test
    void given_handoffs_each_with_revision_when_merged_then_rounds_pair_opinion_with_own_revision() {
        // 跨轮排队合并：各轮判定描述的是当前 PRD 上仍生效的增量（A 轮落盘 X、
        // B 轮在其上再改 Y）——各轮成对、按派发序
        IterationAppService.FixHandoff merged = IterationAppService.FixHandoff.merge(List.of(
                singleHandoff("意见A", "A轮修订：改X"),
                singleHandoff("意见B", "B轮修订：改Y")));
        assertThat(merged.rounds()).containsExactly(
                new IterationAppService.FixHandoff.Round("意见A", "A轮修订：改X"),
                new IterationAppService.FixHandoff.Round("意见B", "B轮修订：改Y"));
    }

    @Test
    void given_handoffs_mixed_revision_when_merged_then_unrevised_round_kept_in_place() {
        // 有改有不改：未修订轮不丢不串位——轮次保留、summary 槽 null（占位呈现归
        // fixRunPrompt，数据层不造「假说明」）；派发序两侧各验一次
        IterationAppService.FixHandoff unrevisedFirst = IterationAppService.FixHandoff.merge(List.of(
                singleHandoff("意见A", null),
                singleHandoff("意见B", "B轮修订：改Y")));
        assertThat(unrevisedFirst.rounds()).containsExactly(
                new IterationAppService.FixHandoff.Round("意见A", null),
                new IterationAppService.FixHandoff.Round("意见B", "B轮修订：改Y"));
        IterationAppService.FixHandoff unrevisedSecond = IterationAppService.FixHandoff.merge(List.of(
                singleHandoff("意见A", "A轮修订：改X"),
                singleHandoff("意见B", null)));
        assertThat(unrevisedSecond.rounds()).containsExactly(
                new IterationAppService.FixHandoff.Round("意见A", "A轮修订：改X"),
                new IterationAppService.FixHandoff.Round("意见B", null));
    }

    @Test
    void given_handoffs_all_without_revision_when_merged_then_all_rounds_unrevised() {
        // 全未修订：各轮槽均 null（每轮各自的「本轮无修订」，非全局一杆子）
        IterationAppService.FixHandoff merged = IterationAppService.FixHandoff.merge(List.of(
                singleHandoff("意见A", null),
                singleHandoff("意见B", null)));
        assertThat(merged.rounds()).hasSize(2)
                .extracting(IterationAppService.FixHandoff.Round::prdRevisionSummary)
                .containsOnlyNulls();
    }

    @Test
    void given_never_generated_or_archived_or_missing_when_fix_then_rejected() {
        Long notGeneratedId = projectRepository.save(Project.create("未生成项目", null,
                9906L, OWNER)).getId();
        Long archivedId = persistedArchivedGeneratedProject("9907");

        assertThatThrownBy(() -> appService.startFixRun(notGeneratedId, "改一下", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RUN_NOT_GENERATED.message());
        assertThatThrownBy(() -> appService.startFixRun(archivedId, "改一下", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.startFixRun(-1L, "改一下", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        // 恢复出口守卫同口径（#48）：归档 / 未生成 / 不存在
        assertThatThrownBy(() -> appService.restartFixRun(archivedId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.restartFixRun(notGeneratedId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RUN_NOT_GENERATED.message());
        assertThatThrownBy(() -> appService.restartFixRun(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        verify(agentClient, never()).converse(any(), any());
    }

    // ---------- 超限终态恢复出口（#48：重派终态那场的交接物） ----------

    @Test
    void given_fix_terminal_failure_when_restart_fix_then_same_handoff_redispatched() {
        Long projectId = persistedGeneratedProject("9913");
        List<Runnable> tracks = givenTrackQueued();
        // 首场 3 次尝试全失败（超限转终态）；恢复轮收口成功
        IllegalStateException persistentFailure = new IllegalStateException("持续失败");
        when(agentClient.converse(any(), any()))
                .thenThrow(persistentFailure, persistentFailure, persistentFailure)
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    finishFixFacts.record(command.workspaceId(), true, "恢复轮完成修正");
                    return new AgentReply(command.runId(), "修正完成");
                });

        appService.startFixRun(projectId, "把主色调改成绿色", "按意见把主色调改为绿");
        tracks.remove(0).run();
        verify(agentClient, times(3)).converse(any(), any());

        // 恢复出口：重派修正 run——交接物沿用（意见清单 + 判定结果、同 coder 会话），
        // 响应 runId 即新 run 首试标识（与新 run 的链路锚，同 /generate 口径）
        IterationAppService.FixDispatch restart = appService.restartFixRun(projectId);
        assertThat(restart.queued()).isFalse();
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(command.capture(), any());
        AgentCommand redispatch = command.getAllValues().get(3);
        assertThat(redispatch.prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(
                        singleHandoff("把主色调改成绿色", "按意见把主色调改为绿")));
        assertThat(redispatch.runId()).isEqualTo(restart.runId());
        assertThat(redispatch.sessionId()).isEqualTo(
                IterationAppService.fixSession(projectId, restart.runId()));
        // 恢复轮成功收工：终态账清（成功后无恢复面，再恢复即 409）+ 轨道释放（下一场可起跑）
        assertThatThrownBy(() -> appService.restartFixRun(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RESTART_UNAVAILABLE.message());
        assertThat(appService.startFixRun(projectId, "下一场", null).queued()).isFalse();
    }

    @Test
    void given_fix_succeeded_or_never_dispatched_when_restart_fix_then_rejected() {
        Long succeededId = persistedGeneratedProject("9914");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();
        appService.startFixRun(succeededId, "列表加筛选", null);
        tracks.remove(0).run();

        Long neverDispatchedId = persistedGeneratedProject("9915");

        // 正常态无恢复面：成功收工 / 从未派过修正（终态账为空）→ 409 指路重提意见
        assertThatThrownBy(() -> appService.restartFixRun(succeededId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RESTART_UNAVAILABLE.message());
        assertThatThrownBy(() -> appService.restartFixRun(neverDispatchedId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RESTART_UNAVAILABLE.message());
        // 拒绝即零动作：成功那场之外不起任何 run
        verify(agentClient, times(1)).converse(any(), any());
    }

    @Test
    void given_fix_in_flight_when_restart_fix_then_rejected() {
        Long projectId = persistedGeneratedProject("9916");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();
        // 轨道已提交未起跑（排队中同在途口径）：恢复出口不可达
        appService.startFixRun(projectId, "第一条", null);

        assertThatThrownBy(() -> appService.restartFixRun(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RESTART_IN_FLIGHT.message());
        verify(agentClient, never()).converse(any(), any());
    }

    @Test
    void given_terminal_then_new_opinion_track_when_restart_fix_then_latest_failure_handoff() {
        // 终态后再提意见起的新轨若又超限：恢复账换新轨的交接物（最近一次终态）
        Long projectId = persistedGeneratedProject("9917");
        List<Runnable> tracks = givenTrackQueued();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("持续失败"));

        appService.startFixRun(projectId, "第一次意见", null);
        tracks.remove(0).run();
        appService.startFixRun(projectId, "第二次意见", null);
        tracks.remove(0).run();

        IterationAppService.FixDispatch restart = appService.restartFixRun(projectId);
        tracks.remove(0).run();
        // 重派轨同样烧满 3 次尝试（全败）：第 7 次调用即重派首试
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(9)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(6).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(singleHandoff("第二次意见", null)));
        assertThat(command.getAllValues().get(6).runId()).isEqualTo(restart.runId());
    }

    @Test
    void given_terminal_failure_with_queued_continuation_when_track_settles_then_no_stale_recovery() {
        // 首场超限但队列有排队意见：轨道合并续派而非收工——续派场成功即整轨收工，
        // 终态账不落（首场的失败不是可恢复面，排队合并那场才是末场事实）
        Long projectId = persistedGeneratedProject("9918");
        List<Runnable> tracks = givenTrackQueued();
        IllegalStateException persistentFailure = new IllegalStateException("持续失败");
        when(agentClient.converse(any(), any()))
                .thenThrow(persistentFailure, persistentFailure, persistentFailure)
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    finishFixFacts.record(command.workspaceId(), true, "合并续派场完成修正");
                    return new AgentReply(command.runId(), "修正完成");
                });

        appService.startFixRun(projectId, "首场意见（将超限）", null);
        assertThat(appService.startFixRun(projectId, "排队意见", null).queued()).isTrue();
        tracks.remove(0).run();

        // 首场 3 败 + 合并续派 1 成；成功收工 → 无恢复面（首场超限不留陈账）
        verify(agentClient, times(4)).converse(any(), any());
        // 中途超限不是终态（#56）：首场烧满即续派合并场，全程不发 run-failed——
        // 「重新修改」出口零闪现（轨道仍在途，出口本就会被 PRJ_025 挡回）
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), any());
        assertThatThrownBy(() -> appService.restartFixRun(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RESTART_UNAVAILABLE.message());
    }

    // ---------- 测试数据 ----------

    /** 单轮交接物（一次派发 = 一轮：一条意见 + 该轮判定）。 */
    private static IterationAppService.FixHandoff singleHandoff(String opinion, String summary) {
        return new IterationAppService.FixHandoff(
                List.of(new IterationAppService.FixHandoff.Round(opinion, summary)));
    }

    /** 已生成形态的项目（迭代的前提事实）。 */
    private Long persistedGeneratedProject(String workspaceId) {
        Project project = Project.create("迭代项目", null, Long.parseLong(workspaceId), OWNER);
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    private Long persistedArchivedGeneratedProject(String workspaceId) {
        Project project = Project.create("归档迭代项目", null, Long.parseLong(workspaceId), OWNER);
        project.markPrdProduced();
        project.markGenerated();
        project.archive();
        return projectRepository.save(project).getId();
    }
}
