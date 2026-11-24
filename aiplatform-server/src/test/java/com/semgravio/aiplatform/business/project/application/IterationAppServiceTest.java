package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentTurnFailure;
import com.semgravio.aiplatform.base.agentscope.StageDurations;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.AgentscopeHarnessAgentFactory;
import com.semgravio.aiplatform.base.agentscope.FileChange;
import com.semgravio.aiplatform.base.agentscope.KernelSystemPrompt;
import com.semgravio.aiplatform.base.agentscope.RunHeading;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.billing.application.PoolTopupAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 迭代编排（#26 验收 + #46 结束工具收口）：修正 run 与生成同机制（每场新会话
 * coder-{projectId}-fix-{runId}（#114）+ 同工作区 + EXECUTOR 配置 + live + 计量 dims
 * + 知识命中前置注入 +
 * 失败自动静默重试——run 失败为唯一失败终态）；run 在途时新任务排队（不即派）、当前 run 收口后
 * 合并为一场修正续派（排队意见不丢、不逐条烧 run）；收口以平台机器判据双件
 * 为准（未调用=未正常收口按重试/终态；changed=false 发「未动系统+原因」事件，
 * changed=true 现有收口行为不回归）；超限终态恢复出口（#48：重派终态那场的交接
 * 物，正常态 / 在途 / 排队均不可达）；交接物三要素（#52：判定结果 + PRD 路径
 * 引用入修正 run prompt；排队合并 #55 逐轮配对：各轮「意见 → 修订说明」一一
 * 对应、未修订轮显式占位不串位——#53 判定全保留的口径不变，载体从两清单
 * 位置对齐收严为结构化成对）；守卫组（不存在 / 已归档 / 未生成）。
 */
@IntegrationTest
class IterationAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private IterationAppService appService;

    /** 尝试环配置（#477 免预算重试上限的测试开关位）。 */
    @Autowired
    private GenerationProperties properties;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 停止面（#359 生成取消）——停止请求的注入口（测试经公开 requestStop 触发）。 */
    @Autowired
    private CodingRunTrack codingRunTrack;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private PoolTopupAppService poolTopupAppService;

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

    @BeforeEach
    void stubWorkspace() {
        // #601 内核收尾判据双件（#597：pnpm build 过＋8081 探活）默认成功——失败态
        // 用例在测试体内后注册桩覆盖（Mockito 后桩优先）
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && (cmd.command().contains("pnpm build")
                        || cmd.command().contains("curl")))))
                .thenReturn(new ExecResultResponse("", "", 0));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_agent_configs");
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_demo_versions");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
        // 计费闸用例行清理（bil_ 弱引用直落，随测试项目一并清）
        jdbcTemplate.update("DELETE FROM bil_balance_holds");
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    /** 轨道任务排队不跑（起跑侧只登记在途）；手动 run 模拟异步轨道执行。 */
    private List<Runnable> givenTrackQueued() {
        List<Runnable> tracks = new ArrayList<>();
        doAnswer(invocation -> {
            tracks.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            tracks.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());
        return tracks;
    }

    /** 脚本化智能体边界：run 正常返回即收口（内核座单发——收口判定归平台机器判据双件）。 */
    private void givenConverseFinishing() {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
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
        givenConverseFinishing();
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
        // 修正 run 全要素（#601 内核形态）：每场新会话（#114 fix-{runId}）与同工作区 +
        // owner + 计量 dims + 流关联（与生成同机制）
        assertThat(value.runId()).isEqualTo(dispatch.runId());
        assertThat(value.prompt()).isEqualTo(IterationAppService.kernelFixRunPrompt(
                singleHandoff("把预约列表按时间倒序排列", null)));
        assertThat(value.sessionId()).isEqualTo(
                IterationAppService.fixSession(projectId, dispatch.runId()));
        assertThat(value.workspaceId()).isEqualTo("9900");
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        // 内核形态四件：权威系统提示＋内核装配键＋kernelForm＋无工具规格
        assertThat(value.systemPrompt()).isEqualTo(KernelSystemPrompt.text());
        assertThat(value.agentKey())
                .isEqualTo(AgentscopeHarnessAgentFactory.KERNEL_AGENT_NAME);
        assertThat(value.kernelForm()).isTrue();
        assertThat(value.toolSpec()).isNull();
        assertThat(value.usageContext().dims()).isEqualTo(UsageDims.of(projectId,
                UsageDims.kindOf(AgentProfile.EXECUTOR),
                IterationAppService.fixSession(projectId, dispatch.runId())));
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
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
                .isEqualTo(IterationAppService.kernelFixRunPrompt(new IterationAppService.FixHandoff(
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
        // #466 多类同池：PRD 历史需求＋激活升格的沉淀物同块前置注入，条带 kind 区分
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "连锁诊所系统", "PRD·连锁诊所管理", "范围边界：不含库存。"),
                new KnowledgeHit("RULE", "平台沉淀", "预约类业务的取消规则",
                        "开场前 2 小时内不可取消。")));
        givenConverseSucceeds();

        String task = "给库存页加分页";
        appService.startFixRun(projectId, task, null);
        tracks.remove(0).run();

        // 知识命中前置注入（#24 生成/修正同机制，#601 内核形态顺延）：query = 内核
        // 修正任务 prompt，命中拼前
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .startsWith("【平台知识库·相似知识】")
                .contains("〔连锁诊所系统｜历史需求〕PRD·连锁诊所管理")
                .contains("〔平台沉淀｜业务规则〕预约类业务的取消规则")
                .endsWith("————\n\n"
                        + IterationAppService.kernelFixRunPrompt(singleHandoff(task, null)));
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
        verify(agentClient, times(1)).converse(attempts.capture(), any()); // #604 单发：一次尝试即终态
        // 终态收口事件（#56）：run-failed 恰一次、锚该场 run 的用户面标识——前端
        // 「重新修改」出口只认本事件（点击即恢复出口重派链路，不被 PRJ_025 挡回）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                projectId.toString().equals(payload.get(EventsAppService.PROJECT_FIELD))
                        && dispatch.runId().equals(payload.get(EventsAppService.RUN_FIELD))));
        // 转终态后轨道照常收工释放：用户再提意见即重新起轨（兜底口径）
        assertThat(appService.startFixRun(projectId, "再试一场", null).queued()).isFalse();
    }

    /** 付费项目＋指定余额（负余额软模型——测试直接置池值，不经结算）。 */
    private void toPaidWithBalance(Long projectId, long balanceFen) {
        poolTopupAppService.topup(null, projectId, 1, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT);
        jdbcTemplate.update("UPDATE bil_pools SET balance = ? WHERE project_id = ?",
                balanceFen, projectId);
    }

    @Test
    void given_paid_exhausted_when_start_fix_run_then_stashed_with_card_and_zero_run() {
        // 段边界门拦下修正派发（#537 计费③）：intent 暂存＋余额提醒卡（run 场无留存
        // ——holdId 不携带）＋零 run 起跑；充值回正 drain 自动重派（不手动重试）
        Long projectId = persistedGeneratedProject("9950");
        toPaidWithBalance(projectId, -500);
        List<Runnable> tracks = givenTrackQueued();

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId,
                "把列表加导出", null);

        assertThat(dispatch.queued()).isFalse();
        assertThat(dispatch.runId()).isNotBlank(); // 卡事件锚（无 run 起跑）
        verify(agentClient, never()).converse(any(), any()); // 零 run
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.BALANCE_REMINDER), argThat(
                payload -> payload.get(AgentEventTypes.BALANCE_HOLD_ID_FIELD) == null
                        && Long.valueOf(-500)
                                .equals(payload.get(AgentEventTypes.BALANCE_POOL_BALANCE_FIELD))));
        assertThat(tracks).isEmpty(); // 暂存未起跑

        // 充值回正自动重派（drain 直调断言恢复事实；事件→AFTER_COMMIT 监听同链）
        poolTopupAppService.topup(null, projectId, 10_000, null, null);
        appService.drainBalanceBlocked();

        assertThat(tracks).hasSize(1); // 暂存 intent 已重派（轨道任务已提交）
    }

    @Test
    void given_terminal_handoff_when_paid_exhausted_restart_then_bill_025_and_auto_redispatch_on_topup() {
        // 恢复出口同守段间硬门：欠费不空翻终态账——BILL_025 指路充值，交接物转
        // 余额闸暂存；充值回正自动重派（不手动重试）
        Long projectId = persistedGeneratedProject("9951");
        List<Runnable> tracks = givenTrackQueued();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("持续失败"));
        appService.startFixRun(projectId, "修不动", null);
        tracks.remove(0).run(); // 超限终态（终态账在案）
        toPaidWithBalance(projectId, -500);

        assertThatThrownBy(() -> appService.restartFixRun(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.BALANCE_BLOCKED.message());

        poolTopupAppService.topup(null, projectId, 10_000, null, null);
        appService.drainBalanceBlocked();

        assertThat(tracks).hasSize(1); // 终态账已转暂存并自动重派
    }

    @Test
    void given_instant_terminal_failure_when_queued_opinions_then_no_redispatch_merged_into_recovery() {
        // #477 重派上限（修正轨「转终态→重派→秒败」循环的治）的 #604 单发形态：整场
        // run 全尝试 llmMs=0（通道全死、未发生真实模型调用）的终态失败后不再续派
        // 排队意见——排队残局并入终态账（恢复出口「继续更新」一次带走，意见零丢失），
        // 不形成秒败循环；有真实模型工作的失败不受影响（排队合并续派口径不变）
        Long projectId = persistedGeneratedProject("9907");
        List<Runnable> tracks = givenTrackQueued();
        AgentTurnFailure channelFail = new AgentTurnFailure(
                "智能体调用失败：Connection refused",
                new IllegalStateException("Connection refused"),
                List.of(), StageDurations.zero());
        when(agentClient.converse(any(), any())).thenThrow(channelFail);

        appService.startFixRun(projectId, "列表加筛选", null);
        appService.startFixRun(projectId, "按钮改蓝色", null); // 在途排队
        tracks.remove(0).run(); // 轨道执行：首场秒败转终态（单发一次尝试即收场）

        // 首场即停：排队意见不再作为续派 run 入场（旧口径：合并续派再烧秒败）
        verify(agentClient, times(1)).converse(any(), any());
        verify(eventsAppService, times(1))
                .publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), anyMap());

        // 恢复出口可达且一次带走全部意见（首场＋排队）：通道恢复后「继续更新」
        // 重派的交接物含两条意见原文（意见零丢失）
        assertThat(appService.restartFixRun(projectId).runId()).isNotBlank();
        AtomicBoolean channelRecovered = new AtomicBoolean(true);
        doAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            return new AgentReply(command.runId(), "已按意见修正");
        }).when(agentClient).converse(any(), any());
        tracks.remove(0).run();
        ArgumentCaptor<AgentCommand> restarted = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(restarted.capture(), any());
        assertThat(restarted.getAllValues().get(1).prompt())
                .contains("列表加筛选")
                .contains("按钮改蓝色");
    }

    // ---------- 更新停止（#359 生成取消：失败账口径——终态账可达「继续更新」） ----------

    @Test
    void given_stop_requested_mid_fix_when_run_then_stopped_terminal_account_and_restartable() {
        // 灵魂用例（#359 AC②③·修正轨）：更新在途被叫停——不重试（一次尝试即收场）、
        // run-failed(stopped=true) 收口、失败账口径记终态账——「继续更新」出口照常
        // 可达（restart 不被 PRJ_026 挡回，交接物沿用同任务重派）
        Long projectId = persistedGeneratedProject("9930");
        List<Runnable> tracks = givenTrackQueued();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            codingRunTrack.requestStop(projectId);
            throw new IllegalStateException("java.lang.InterruptedException");
        });

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId,
                "把按钮改成蓝色", null);
        tracks.remove(0).run();

        // 停止不重试：恰一次 converse
        verify(agentClient, times(1)).converse(any(), any());
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FAILED), argThat(payload ->
                dispatch.runId().equals(payload.get(EventsAppService.RUN_FIELD))
                        && Boolean.TRUE.equals(payload.get(AgentEventTypes.RUN_STOPPED_FIELD))));
        // 终态账在场：「继续更新」恢复出口不被 PRJ_026 挡回（失败账口径）
        assertThat(appService.restartFixRun(projectId).runId()).isNotBlank();
        // 重派同任务：交接物沿用（「把按钮改成蓝色」），续跑可完成——重打桩走
        // doAnswer 形（when() 形会先调一次在桩的停止脚本，误触发停止）
        doAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0, AgentCommand.class);
            return new AgentReply(command.runId(), "已按意见修正");
        }).when(agentClient).converse(any(), any());
        tracks.remove(0).run();
        ArgumentCaptor<AgentCommand> restarted = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(restarted.capture(), any());
        assertThat(restarted.getAllValues().get(1).prompt()).contains("把按钮改成蓝色");
        // 停止面已清：新一场照常可起
        assertThat(appService.startFixRun(projectId, "下一场", null).queued()).isFalse();
    }

    // ---------- 收口判据（平台机器双件；收口扩载归 #88） ----------

    @Test
    void given_fix_run_when_closes_then_demo_seed_applied_and_failure_not_blocking() {
        // #340 演示种子预置：迭代收口（系统实动后可能有新空表）施用种子——changed
        // 真假两态都收口不炸；施用失败也不阻断收工（轨道释放、下一场可派）
        Long projectId = persistedGeneratedProject("9916");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing();
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("/opt/seed/apply.sh"))))
                .thenThrow(new IllegalStateException("容器 exec 失败"));

        appService.startFixRun(projectId, "列表页加一列状态", null);
        tracks.remove(0).run();

        // 施用确已发起（exec 命中种子入口）且失败被吞——轨道正常收工
        verify(workspaceLifecycleAppService, times(1)).exec(eq("9916"),
                argThat(cmd -> cmd != null && cmd.command().contains("/opt/seed/apply.sh")));
        assertThat(appService.startFixRun(projectId, "下一场", null).queued()).isFalse();
    }

    // ---------- 收口扩载（#617 五字段：判据 + 内核自述 + 变更清单 + 用时，服务端权威） ----------

    /**
     * 脚本化收口轮（#88 验收缝）：真实事件序（run-start → 过程部件 → run-finish）
     * + 回复携带文件变更观察（真实客户端的 AgentReply.changes 面）。
     */
    private void givenConverseClosing(List<FileChange> changes) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(scripted(AgentEventTypes.PART_SIGNAL, command.runId(),
                    Map.of(AgentEventTypes.PART_SIGNAL_SIGNAL_FIELD,
                            AgentEventTypes.PART_SIGNAL_DERAILED)));
            sink.accept(scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "修正完成", null, changes);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_update_round_when_fix_closes_then_run_finish_carries_five_field_closing() {
        // 灵魂用例（#617/#619）：脚本化更新轮收口——五字段骨架齐（用时/判据逐件/
        // 内核自述/变更清单/预览入口由前端查询提供）；判定键与平台句式摘要退役；
        // 变更清单来源 = 工作区真实 diff（跑前快照基线 + 收口 numstat），迭代另携
        // 单文件只读 patch——工具参数观察不再参与
        Long projectId = persistedGeneratedProject("9915");
        List<Runnable> tracks = givenTrackQueued();
        givenSnapshotAndDiff(
                "12\t3\tsrc/pages/Orders.jsx\n4\t0\tsrc/App.jsx\n",
                "diff --git a/src/App.jsx b/src/App.jsx\n"
                        + "--- a/src/App.jsx\n+++ b/src/App.jsx\n@@ -1 +1,4 @@\n+新增\n"
                        + "diff --git a/src/pages/Orders.jsx b/src/pages/Orders.jsx\n"
                        + "--- a/src/pages/Orders.jsx\n+++ b/src/pages/Orders.jsx\n"
                        + "@@ -1,3 +1,12 @@\n-旧\n+新\n");
        // 工具参数观察与真实 diff 刻意不同——收尾卡必须取 diff
        givenConverseClosing(List.of(new FileChange("/src/pages/Orders.jsx", 99, 99)));

        appService.startFixRun(projectId, "下单页加配送范围说明", "配送范围改为全国");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        // 扩载随被押后的 run-finish 一体到达（收口判据落定后——真收口才扩载）
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        // 内核自述 = 内核末次文本（本轮 AgentReply.text）
        assertThat(closing).containsEntry("narrative", "修正完成");
        // 判据逐件如实（build＋预览双件）
        assertThat((Map<String, Object>) closing.get("criteria"))
                .containsEntry("buildPassed", true)
                .containsEntry("previewPassed", true);
        // 旧平台句式摘要与「需求文档／系统」判定键退役
        assertThat(closing).doesNotContainKeys(
                "summary", "prdChanged", "prdNote", "systemChanged", "systemNote");
        // 变更清单：真实 diff 行数、路径排序稳定、单文件只读 patch 在场
        List<Map<String, Object>> files = (List<Map<String, Object>>) closing.get("files");
        assertThat(files).extracting(file -> file.get("path"))
                .containsExactly("/src/App.jsx", "/src/pages/Orders.jsx");
        assertThat(files.get(0)).containsEntry("added", 4).containsEntry("removed", 0);
        assertThat(files.get(1)).containsEntry("added", 12).containsEntry("removed", 3);
        assertThat((String) files.get(0).get("patch")).contains("+新增");
        assertThat((String) files.get(1).get("patch")).contains("-旧").contains("+新");
        assertThat(files).allSatisfy(file -> assertThat(file).doesNotContainKey("patchOmitted"));
        assertThat(closing).doesNotContainKey("filesUnavailable");
        // 轮末统计：时长在场（≥0——起跑到收口的活动量，非墙钟断言）
        assertThat(closing.get("durationMs")).isInstanceOf(Long.class);
        assertThat((Long) closing.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_patch_over_limit_when_fix_closes_then_patch_omitted_but_lines_kept() {
        // #619 AC：单文件 patch 超限（300 行）省略但保留行数并标注——前端呈现
        // 「diff 过大」；行数事实来自 numstat 不受 patch 省略影响
        Long projectId = persistedGeneratedProject("9921");
        List<Runnable> tracks = givenTrackQueued();
        StringBuilder huge = new StringBuilder("diff --git a/src/Big.jsx b/src/Big.jsx");
        for (int index = 0; index < 301; index++) {
            huge.append("\n+line ").append(index);
        }
        givenSnapshotAndDiff("500\t0\tsrc/Big.jsx\n", huge.toString());
        givenConverseClosing(List.of());

        appService.startFixRun(projectId, "大改一页", null);
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat((List<Map<String, Object>>) closing.get("files")).singleElement()
                .satisfies(file -> {
                    assertThat(file).containsEntry("path", "/src/Big.jsx")
                            .containsEntry("added", 500)
                            .containsEntry("removed", 0)
                            .containsEntry("patchOmitted", true)
                            .doesNotContainKey("patch");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_scripted_update_round_when_fix_closes_then_closing_has_no_version_anchor() {
        // 收口自动成版（Run-Id trailer）随审查与收口组退役（#605）：收尾卡不再携
        // version 键；#619 跑前快照会提交 git（平台标识、无 trailer）——快照不是
        // 用户面版本，版本序列成员判据（Rollback-From）天然过滤
        Long projectId = persistedGeneratedProject("9917");
        List<Runnable> tracks = givenTrackQueued();
        givenSnapshotAndDiff("12\t3\tsrc/pages/Orders.jsx\n", "");
        givenConverseClosing(List.of());

        appService.startFixRun(projectId, "下单页加配送范围说明", "配送范围改为全国");
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).doesNotContainKey("version");
        ArgumentCaptor<WorkspaceExecCommand> execs =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, atLeast(0)).exec(any(), execs.capture());
        // 只允许无 trailer 的跑前快照提交；不得出现带 Rollback-From 的版本提交
        assertThat(execs.getAllValues().stream().map(WorkspaceExecCommand::command))
                .noneSatisfy(command -> assertThat(command).contains("Rollback-From"));
        assertThat(execs.getAllValues().stream().map(WorkspaceExecCommand::command))
                .anySatisfy(command -> assertThat(command).contains("-m 'snapshot "));
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_no_file_changes_when_fix_closes_then_closing_has_empty_change_list() {
        // #617/#619：系统无实际文件改动的轮——真实 diff 为空清单（不再有「系统无需
        // 改动」判定行）；判据双件仍全过才收口，内核自述照常取末次文本
        Long projectId = persistedGeneratedProject("9916");
        List<Runnable> tracks = givenTrackQueued();
        givenSnapshotAndDiff("", "");
        givenConverseClosing(List.of());

        appService.startFixRun(projectId, "把配送范围改成全国", null);
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry("narrative", "修正完成");
        assertThat((Map<String, Object>) closing.get("criteria"))
                .containsEntry("buildPassed", true)
                .containsEntry("previewPassed", true);
        assertThat(closing).doesNotContainKeys(
                "summary", "prdChanged", "prdNote", "systemChanged", "systemNote");
        assertThat((List<Map<String, Object>>) closing.get("files")).isEmpty();
        assertThat(closing).doesNotContainKey("filesUnavailable"); // 空清单不是降级
    }

    @Test
    @SuppressWarnings("unchecked")
    void given_diff_fails_when_fix_closes_then_run_succeeds_and_changes_unavailable() {
        // #619 降级：快照或 diff 失败只降级变更清单（标注「变更清单不可用」）——
        // 修正 run 照常成功收口，其余字段照常
        Long projectId = persistedGeneratedProject("9922");
        List<Runnable> tracks = givenTrackQueued();
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("-m 'snapshot "))))
                .thenReturn(new ExecResultResponse("a1b2c3d", "", 0));
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("--numstat"))))
                .thenReturn(new ExecResultResponse("", "fatal: bad revision", 128));
        givenConverseClosing(List.of());

        appService.startFixRun(projectId, "列表页加一列状态", null);
        tracks.remove(0).run();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.RUN_FINISH),
                payload.capture());
        Map<String, Object> closing =
                (Map<String, Object>) payload.getValue().get(AgentEventTypes.CLOSING_FIELD);
        assertThat(closing).containsEntry("filesUnavailable", true)
                .containsEntry("narrative", "修正完成");
        assertThat((List<Map<String, Object>>) closing.get("files")).isEmpty();
        assertThat((Map<String, Object>) closing.get("criteria"))
                .containsEntry("buildPassed", true);
    }

    /** 跑前快照成功（回读 hash）＋收口 numstat 与完整 patch 脚本（#619 diff 缝）。 */
    private void givenSnapshotAndDiff(String numstat, String patch) {
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("-m 'snapshot "))))
                .thenReturn(new ExecResultResponse("a1b2c3d", "", 0));
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("--numstat"))))
                .thenReturn(new ExecResultResponse(numstat, "", 0));
        when(workspaceLifecycleAppService.exec(any(),
                argThat(cmd -> cmd != null && cmd.command().contains("diff --cached --no-renames '"))))
                .thenReturn(new ExecResultResponse(patch, "", 0));
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
        // kernelFixRunPrompt，数据层不造「假说明」）；派发序两侧各验一次
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
        Long notGeneratedId = projectRepository.save(Project.create("未生成项目", null, 9906L, OWNER, null)).getId();
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
        // 首场失败（#604 单发：一次尝试即超限转终态）；恢复轮收口成功
        IllegalStateException persistentFailure = new IllegalStateException("持续失败");
        when(agentClient.converse(any(), any()))
                .thenThrow(persistentFailure)
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    return new AgentReply(command.runId(), "修正完成");
                });

        appService.startFixRun(projectId, "把主色调改成绿色", "按意见把主色调改为绿");
        tracks.remove(0).run();
        verify(agentClient, times(1)).converse(any(), any());

        // 恢复出口：重派修正 run——交接物沿用（意见清单 + 判定结果、同 coder 会话），
        // 响应 runId 即新 run 首试标识（与新 run 的链路锚，同 /generate 口径）
        IterationAppService.FixDispatch restart = appService.restartFixRun(projectId);
        assertThat(restart.queued()).isFalse();
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand redispatch = command.getAllValues().get(1);
        assertThat(redispatch.prompt())
                .isEqualTo(IterationAppService.kernelFixRunPrompt(
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
        // 重派轨同样单发失败（#604 全败）：第 3 次调用即重派首试
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(2).prompt())
                .isEqualTo(IterationAppService.kernelFixRunPrompt(singleHandoff("第二次意见", null)));
        assertThat(command.getAllValues().get(2).runId()).isEqualTo(restart.runId());
    }

    @Test
    void given_terminal_failure_with_queued_continuation_when_track_settles_then_no_stale_recovery() {
        // 首场失败但队列有排队意见：轨道合并续派而非收工——续派场成功即整轨收工，
        // 终态账不落（首场的失败不是可恢复面，排队合并那场才是末场事实）
        Long projectId = persistedGeneratedProject("9918");
        List<Runnable> tracks = givenTrackQueued();
        IllegalStateException persistentFailure = new IllegalStateException("持续失败");
        when(agentClient.converse(any(), any()))
                .thenThrow(persistentFailure)
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    return new AgentReply(command.runId(), "修正完成");
                });

        appService.startFixRun(projectId, "首场意见（将超限）", null);
        assertThat(appService.startFixRun(projectId, "排队意见", null).queued()).isTrue();
        tracks.remove(0).run();

        // 首场单发即败 + 合并续派 1 成；成功收工 → 无恢复面（首场失败不留陈账）
        verify(agentClient, times(2)).converse(any(), any());
        // 中途失败不是终态（#56）：首场即续派合并场，全程不发 run-failed——
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
        Project project = Project.create("迭代项目", null, Long.parseLong(workspaceId), OWNER, null);
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    /** PRD 版本行在场（#333 Demo 版本锚——迭代起跑时最新构建基准的取数事实）。 */
    private void seedPrdVersionRow(Long projectId, int versionNo) {
        seedPrdVersionRowContent(projectId, versionNo, "# PRD");
    }

    /** PRD 版本行在场（自定义全文——记行形态的版本行夹具：锚捕获读「最新版」）。 */
    private void seedPrdVersionRowContent(Long projectId, int versionNo, String content) {
        jdbcTemplate.update("""
                INSERT INTO prj_prd_versions (id, project_id, version_no, content, confirmed_at,
                    prd_produced_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, System.nanoTime(), projectId, versionNo, content);
    }

    private Long persistedArchivedGeneratedProject(String workspaceId) {
        Project project = Project.create("归档迭代项目", null, Long.parseLong(workspaceId), OWNER, null);
        project.markPrdProduced();
        project.markGenerated();
        project.archive();
        return projectRepository.save(project).getId();
    }

    // ---------- Demo 版本化（#333）：迭代轮收口自动成版 ----------

    @Test
    void given_changed_fix_runs_when_close_then_demo_versions_increment_with_prd_anchor() {
        // 灵魂例（#333 迭代每轮自动成版）：changed=true 收口即递增成版、锚本场起跑时
        // 最新 PRD 版本（#462 去门化＝最新构建基准记行）——v1（PRD v1 基准）→ 新基准
        // v2 记行后下一轮锚 v2（迭代成版链）
        Long projectId = persistedGeneratedProject("9950");
        seedPrdVersionRow(projectId, 1);
        // 工作区 PRD==最新记行全文（同文——起跑记行幂等跳过，不产生新基准）
        givenWorkspacePrd("# PRD");
        List<Runnable> tracks = givenTrackQueued();
        // #601 内核接线：系统改没改＝回复携带的平台可观测文件改动事实——实动才成版
        givenConverseClosing(List.of(new FileChange("/src/App.jsx", 1, 0)));

        IterationAppService.FixDispatch first = appService.startFixRun(projectId, "列表按时间倒序", null);
        tracks.remove(0).run();
        // 需求侧修订后的新基准记行（模拟 PRD 已修订、随起跑自动记 v2）——下一场锚 v2
        seedPrdVersionRow(projectId, 2);
        IterationAppService.FixDispatch second = appService.startFixRun(projectId, "加筛选", null);
        tracks.remove(0).run();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version_no, prd_version_no, run_id FROM prj_demo_versions"
                        + " WHERE project_id = ? ORDER BY version_no",
                projectId);
        assertThat(rows).extracting(row -> row.get("version_no")).containsExactly(1, 2);
        // 各版 PRD 关联正确：v1 锚 v1、v2 锚 v2；run 锚＝各场修正 run
        assertThat(rows.get(0)).containsEntry("prd_version_no", 1)
                .containsEntry("run_id", first.runId());
        assertThat(rows.get(1)).containsEntry("prd_version_no", 2)
                .containsEntry("run_id", second.runId());
    }

    @Test
    void given_unchanged_fix_run_when_close_then_no_demo_version() {
        // 系统无改动（平台可观测文件改动为空）＝照常收口、无新版本（#605 起收口不再自动成版）
        Long projectId = persistedGeneratedProject("9951");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing();

        appService.startFixRun(projectId, "列表按时间倒序", null);
        tracks.remove(0).run();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_demo_versions WHERE project_id = ?", Integer.class,
                projectId);
        assertThat(count).isZero();
    }

    // ---------- 迭代需求基准（#462 去门化）：恒工作区 PRD 正本（单形） ----------

    /** 最新版本行全文 v1（基准断言用的独特正文——区别于新草稿与占位）。 */
    private static final String PRD_V1_ROW = "# PRD v1 基准\n\n## 功能清单\n\n1. 首页\n";

    /** 范围级新草稿（≠v1 全文——工作区已修订、未随起跑记行的形态）。 */
    private static final String SCOPE_DRAFT =
            "# PRD v1 基准\n\n## 功能清单\n\n1. 首页\n2. 管理后台\n";

    /** 工作区 PRD 读取桩（PRD 读端点同形：stat 首行＋cat 余文——覆盖 AGENTS.md 桩，写路径只看退出码）。 */
    private void givenWorkspacePrd(String prdMarkdown) {
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100000\n" + prdMarkdown, "", 0));
    }

    @Test
    void given_pending_draft_when_small_iteration_runs_then_prompt_baselines_workspace_draft() {
        // 去门化（#462，ADR-0039——#334 停派分流退役）：范围级新草稿在场不再停派
        // 待确认——受理即迭代，需求基准恒为工作区 PRD 正本（新草稿＝新构建基准，
        // 下一起跑自动记行；不再内联旧确认版快照）
        Long projectId = persistedGeneratedProject("9960");
        seedPrdVersionRowContent(projectId, 1, PRD_V1_ROW);
        givenWorkspacePrd(SCOPE_DRAFT);
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "按钮改成蓝色", null);
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .contains("需求正本 = 工作区")
                .doesNotContain("本轮需求基准");
    }

    @Test
    void given_draft_equals_latest_row_when_iteration_runs_then_prompt_reads_workspace() {
        // 工作区正本==最新版本行全文（同文形态，无新草稿在场）：照旧以工作区正本
        // 为需求正本（单形——不内联快照、不区分草稿/成版两形）
        Long projectId = persistedGeneratedProject("9961");
        seedPrdVersionRowContent(projectId, 1, PRD_V1_ROW);
        givenWorkspacePrd(PRD_V1_ROW);
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "按钮改成蓝色", null);
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .contains("需求正本 = 工作区")
                .doesNotContain("本轮需求基准");
    }

    @Test
    void given_no_version_rows_when_iteration_runs_then_prompt_reads_workspace() {
        // legacy（无版本记行起跑，如记行失败/极老项目）：不虚构记行——照旧以
        // 工作区正本为需求正本（Demo 版本锚 null 同口径）
        Long projectId = persistedGeneratedProject("9962");
        givenWorkspacePrd("# PRD\n\n## 功能清单\n\n1. 首页\n");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "按钮改成蓝色", null);
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, atLeast(1)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(0).prompt())
                .contains("需求正本 = 工作区")
                .doesNotContain("本轮需求基准");
    }

}
