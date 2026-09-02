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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
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
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 迭代编排（#26 验收 + #46 结束工具收口）：修正 run 与生成同机制（coder-{projectId}
 * 会话稳定绑定 + 同工作区 + CODER 角色卡 + live + 计量 dims + 知识命中前置注入 +
 * 失败自动重试 run-retrying 帧）；run 在途时新任务排队（不即派）、当前 run 收口后
 * 合并为一场修正续派（排队意见不丢、不逐条烧 run）；收口以 finish_fix 工具事实
 * 为准（未调用=未正常收口按重试/终态；changed=false 发「未动系统+原因」帧，
 * changed=true 现有收口行为不回归）；超限终态恢复出口（#48：重派终态那场的交接
 * 物，正常态 / 在途 / 排队均不可达）；守卫组（不存在 / 已归档 / 未生成）。
 */
@SpringBootTest
class IterationAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private IterationAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FinishFixFacts finishFixFacts;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private KnowledgePort knowledgePort;

    /** 通知通道（#49 逐修改刷新的观测缝——preview-updated 在此断言）。 */
    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    /** 工作区 exec（#49 步骤边界探活的脚本化缝；既有修正用例不触 exec 不受影响）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
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

    /** 脚本化智能体边界：run 正常返回即调 finish_fix（coder 会话才记——忠实于
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

    private void givenConverseSucceeds() {
        givenConverseFinishing(true, "已按意见修正");
    }

    @Test
    void given_generated_project_when_fix_then_command_reuses_coder_session_and_workspace() {
        Long projectId = persistedGeneratedProject("9900");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId,
                "把预约列表按时间倒序排列");
        tracks.remove(0).run();

        assertThat(dispatch.queued()).isFalse();
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        AgentCommand value = command.getValue();
        // 修正 run 全要素：复用 coder 会话与同工作区（编码智能体带建系统上下文继续）+
        // CODER 角色卡 + owner + 计量 dims + 流关联 + 直播开（与生成同机制）
        assertThat(value.runId()).isEqualTo(dispatch.runId());
        assertThat(value.prompt()).isEqualTo(IterationAppService.fixRunPrompt(
                List.of("把预约列表按时间倒序排列")));
        assertThat(value.sessionId()).isEqualTo("coder-" + projectId);
        assertThat(value.workspaceId()).isEqualTo("9900");
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).isEqualTo(RolePreset.CODER.systemPrompt());
        assertThat(value.usageContext().dims()).isEqualTo(UsageDims.of(projectId,
                UsageDims.kindOf(RolePreset.CODER), "coder-" + projectId));
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.live()).isTrue();
        // role-assigned CODER 前置（前端编码 run 判定锚——直播/预览刷新联动同生成）
        verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED), argThat(payload ->
                "CODER".equals(payload.get(AgentEventTypes.ROLE_FIELD))
                        && dispatch.runId().equals(payload.get(AgentStreamAppService.RUN_FIELD))));
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

        IterationAppService.FixDispatch first = appService.startFixRun(projectId, "列表加筛选");
        Thread trackWorker = new Thread(tracks.remove(0));
        trackWorker.start();
        assertThat(runInFlight.await(5, TimeUnit.SECONDS)).isTrue();

        // run 在途：后续任务排队、不即派
        IterationAppService.FixDispatch second = appService.startFixRun(projectId, "按钮改蓝色");
        IterationAppService.FixDispatch third = appService.startFixRun(projectId, "加导出");
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
                .isEqualTo(IterationAppService.fixRunPrompt(List.of("按钮改蓝色", "加导出")))
                .contains("1. 按钮改蓝色").contains("2. 加导出");
        assertThat(runs.get(1).sessionId()).isEqualTo("coder-" + projectId);
        assertThat(runs.get(1).runId()).isNotEqualTo(first.runId());
        // 轨道收工（队列空）：在途释放——下一场意见可再起跑
        assertThat(appService.startFixRun(projectId, "再来一轮").queued()).isFalse();
    }

    @Test
    void given_queued_tasks_when_track_not_run_yet_then_in_flight_covers_queued_start() {
        // 已提交未起跑（排队中）也算在途：重复派发仍排队，不并发起第二条轨道
        Long projectId = persistedGeneratedProject("9902");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "第一条（轨道已提交未起跑）");
        assertThat(appService.startFixRun(projectId, "第二条").queued()).isTrue();
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
        appService.startFixRun(projectId, task);
        tracks.remove(0).run();

        // 知识命中前置注入（#24 生成/修正同机制）：query = 修正任务 prompt，命中拼前
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .startsWith("【平台知识库·相似历史需求】")
                .contains("连锁诊所系统")
                .endsWith("————\n\n" + IterationAppService.fixRunPrompt(List.of(task)));
    }

    @Test
    void given_first_attempt_fails_when_fix_then_retrying_frame_then_retry_prompt() {
        Long projectId = persistedGeneratedProject("9904");
        List<Runnable> tracks = givenTrackQueued();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("修正尝试中断"))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    finishFixFacts.record(command.workspaceId(), true, "重试轮完成修正并收口");
                    return new AgentReply(command.runId(), "修正完成");
                });

        appService.startFixRun(projectId, "修正首页布局");
        tracks.remove(0).run();

        // 失败自动重试同生成：run-retrying 帧（话术「遇到问题，正在重试」）+ 重试续作轨
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .isEqualTo(IterationAppService.FIX_RETRY_RUN_PROMPT);
        verify(streamAppService).publish(eq(AgentEventTypes.RUN_RETRYING), argThat(payload ->
                "遇到问题，正在重试".equals(payload.get(AgentEventTypes.RETRY_MESSAGE_FIELD))));

        // 重试成功后轨道正常收工：下一场可再起跑
        assertThat(appService.startFixRun(projectId, "下一场").queued()).isFalse();
    }

    @Test
    void given_all_attempts_fail_when_fix_then_terminal_and_track_released() {
        Long projectId = persistedGeneratedProject("9905");
        List<Runnable> tracks = givenTrackQueued();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("持续失败"));

        appService.startFixRun(projectId, "修不动");
        tracks.remove(0).run();

        verify(agentClient, times(3)).converse(any(), any());
        // 超限转终态后轨道照常收工释放：用户再提意见即重新起轨（兜底口径）
        assertThat(appService.startFixRun(projectId, "再试一场").queued()).isFalse();
    }

    // ---------- 结束工具收口（#46：finish_fix 事实观测 + 「未动系统」如实呈现） ----------

    @Test
    void given_finish_fix_changed_false_when_fix_closes_then_fix_unchanged_frame() {
        // 灵魂用例（#46）：脚本化结束工具 changed=false → 「未动系统+原因」呈现帧
        Long projectId = persistedGeneratedProject("9908");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing(false, "纯文档性修订，系统现状已满足");

        IterationAppService.FixDispatch dispatch = appService.startFixRun(projectId,
                "把首页标题改成「关于我们」");
        tracks.remove(0).run();

        // fix-unchanged 帧：锚定收口 runId + projectId + 原因原文（不解析自由文本）
        verify(streamAppService).publish(eq(AgentEventTypes.FIX_UNCHANGED), argThat(payload ->
                projectId.toString().equals(payload.get(AgentStreamAppService.PROJECT_FIELD))
                        && dispatch.runId().equals(payload.get(AgentStreamAppService.RUN_FIELD))
                        && "纯文档性修订，系统现状已满足"
                                .equals(payload.get(AgentEventTypes.FIX_UNCHANGED_REASON_FIELD))));
        // changed=false 也是正常收口：轨道收工释放，下一场可再起跑
        assertThat(appService.startFixRun(projectId, "下一场").queued()).isFalse();
    }

    @Test
    void given_finish_fix_changed_true_when_fix_closes_then_no_fix_unchanged_frame() {
        Long projectId = persistedGeneratedProject("9909");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseFinishing(true, "已把主色调改为绿色");

        appService.startFixRun(projectId, "把主色调改成绿色");
        tracks.remove(0).run();

        // changed=true：现有收口行为不回归——不发 fix-unchanged（预览刷新/直播收起/
        // 状态位仍由 run-finish 驱动），轨道正常收工
        verify(streamAppService, never()).publish(eq(AgentEventTypes.FIX_UNCHANGED), any());
        assertThat(appService.startFixRun(projectId, "下一场").queued()).isFalse();
    }

    @Test
    void given_no_finish_fix_when_converse_returns_then_treated_as_failed_attempt() {
        Long projectId = persistedGeneratedProject("9910");
        List<Runnable> tracks = givenTrackQueued();
        // 首试正常返回但不调 finish_fix（脚本化「模型忘了收口」）→ 判未正常收口、
        // 按既有重试口径续试；重试轮调了 → 正常收口
        when(agentClient.converse(any(), any()))
                .thenAnswer(invocation -> new AgentReply(
                        ((AgentCommand) invocation.getArgument(0)).runId(), "做完了"))
                .thenAnswer(invocation -> {
                    AgentCommand command = invocation.getArgument(0);
                    finishFixFacts.record(command.workspaceId(), true, "重试轮补上收口");
                    return new AgentReply(command.runId(), "修正完成");
                });

        appService.startFixRun(projectId, "修一下分页");
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .isEqualTo(IterationAppService.FIX_RETRY_RUN_PROMPT);
        // 未正常收口如实表达：补发 error 帧（run-finish 已发，帧序 run-finish →
        // error → run-retrying）+ 重试帧照发（与 converse 异常的重试同一口径）
        verify(streamAppService).publish(eq(AgentEventTypes.ERROR), argThat(payload ->
                projectId.toString().equals(payload.get(AgentStreamAppService.PROJECT_FIELD))
                        && payload.get("message").toString().contains("finish_fix")));
        verify(streamAppService).publish(eq(AgentEventTypes.RUN_RETRYING), any());
        assertThat(appService.startFixRun(projectId, "下一场").queued()).isFalse();
    }

    @Test
    void given_no_finish_fix_all_attempts_when_fix_then_terminal_and_track_released() {
        Long projectId = persistedGeneratedProject("9911");
        List<Runnable> tracks = givenTrackQueued();
        // 全部尝试都不调 finish_fix：每次收口判定不过 → 重试超限转终态，无
        // fix-unchanged（未动系统的如实呈现只认工具事实，不认静默），每次尝试补发
        // error 帧（末次 error 即终态——「链路断了」不得呈现为正常收口），轨道收工释放
        when(agentClient.converse(any(), any()))
                .thenAnswer(invocation -> new AgentReply(
                        ((AgentCommand) invocation.getArgument(0)).runId(), "做完了"));

        appService.startFixRun(projectId, "修不动");
        tracks.remove(0).run();

        verify(agentClient, times(3)).converse(any(), any());
        verify(streamAppService, times(3)).publish(eq(AgentEventTypes.ERROR), any());
        verify(streamAppService, never()).publish(eq(AgentEventTypes.FIX_UNCHANGED), any());
        assertThat(appService.startFixRun(projectId, "再试一场").queued()).isFalse();
    }

    @Test
    void given_stale_fact_from_earlier_track_when_new_track_then_not_consumed_as_verdict() {
        // 轨道起跑清残留：上一轨遗留（或生成 run 误调）的事实不顶本轨的收口判定
        Long projectId = persistedGeneratedProject("9912");
        List<Runnable> tracks = givenTrackQueued();
        finishFixFacts.record("9912", false, "上一轨的旧事实");
        // 本轨全程不调 finish_fix → 旧事实被清、收口判定不过 → 重试超限转终态
        when(agentClient.converse(any(), any()))
                .thenAnswer(invocation -> new AgentReply(
                        ((AgentCommand) invocation.getArgument(0)).runId(), "做完了"));

        appService.startFixRun(projectId, "新一轮意见");
        tracks.remove(0).run();

        verify(agentClient, times(3)).converse(any(), any());
        verify(streamAppService, never()).publish(eq(AgentEventTypes.FIX_UNCHANGED), any());
    }

    // ---------- 逐修改刷新（#49：修正与生成同一口径，刷新挂在共用尝试环） ----------

    @Test
    void given_fix_run_live_steps_and_probe_ok_when_fix_then_preview_updated_notification() {
        Long projectId = persistedGeneratedProject("9913");
        List<Runnable> tracks = givenTrackQueued();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        // 脚本化边界：直播步骤序列（step1 起跑边界 + step2 完整修改落定）经捕获的
        // sink 推入 + finish_fix 收口事实——修正 run 的探活装饰在生成侧同一链上
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(new AgentEvent(AgentEventTypes.LIVE_STEP, Map.of(
                    AgentStreamAppService.RUN_FIELD, command.runId(),
                    AgentEventTypes.LIVE_STEP_FIELD, 1)));
            sink.accept(new AgentEvent(AgentEventTypes.LIVE_STEP, Map.of(
                    AgentStreamAppService.RUN_FIELD, command.runId(),
                    AgentEventTypes.LIVE_STEP_FIELD, 2)));
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });

        appService.startFixRun(projectId, "把预约列表按时间倒序排列");
        tracks.remove(0).run();

        // 完整修改落定的步骤边界（step≥2）→ 平台侧探活通过 → 刷新通知（step1 不算）
        verify(notificationAppService, timeout(5000))
                .publish(eq(ProjectEventTypes.PREVIEW_UPDATED), argThat(payload ->
                        projectId.toString().equals(
                                payload.get(ProjectEventTypes.PROJECT_ID_FIELD))));
        // run 正常收口、轨道收工（刷新装饰不改变修正收口行为）
        assertThat(appService.startFixRun(projectId, "下一场").queued()).isFalse();
    }

    @Test
    void given_never_generated_or_archived_or_missing_when_fix_then_rejected() {
        Long notGeneratedId = projectRepository.save(Project.create("未生成项目", null,
                9906L, OWNER)).getId();
        Long archivedId = persistedArchivedGeneratedProject("9907");

        assertThatThrownBy(() -> appService.startFixRun(notGeneratedId, "改一下"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RUN_NOT_GENERATED.message());
        assertThatThrownBy(() -> appService.startFixRun(archivedId, "改一下"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.startFixRun(-1L, "改一下"))
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

        appService.startFixRun(projectId, "把主色调改成绿色");
        tracks.remove(0).run();
        verify(agentClient, times(3)).converse(any(), any());

        // 恢复出口：重派修正 run——交接物沿用（同任务清单、同 coder 会话），
        // 响应 runId 即新 run 首试标识（与新 run 的链路锚，同 /generate 口径）
        IterationAppService.FixDispatch restart = appService.restartFixRun(projectId);
        assertThat(restart.queued()).isFalse();
        tracks.remove(0).run();

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(command.capture(), any());
        AgentCommand redispatch = command.getAllValues().get(3);
        assertThat(redispatch.prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(List.of("把主色调改成绿色")));
        assertThat(redispatch.runId()).isEqualTo(restart.runId());
        assertThat(redispatch.sessionId()).isEqualTo("coder-" + projectId);
        // 恢复轮成功收工：终态账清（成功后无恢复面，再恢复即 409）+ 轨道释放（下一场可起跑）
        assertThatThrownBy(() -> appService.restartFixRun(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RESTART_UNAVAILABLE.message());
        assertThat(appService.startFixRun(projectId, "下一场").queued()).isFalse();
    }

    @Test
    void given_fix_succeeded_or_never_dispatched_when_restart_fix_then_rejected() {
        Long succeededId = persistedGeneratedProject("9914");
        List<Runnable> tracks = givenTrackQueued();
        givenConverseSucceeds();
        appService.startFixRun(succeededId, "列表加筛选");
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
        appService.startFixRun(projectId, "第一条");

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

        appService.startFixRun(projectId, "第一次意见");
        tracks.remove(0).run();
        appService.startFixRun(projectId, "第二次意见");
        tracks.remove(0).run();

        IterationAppService.FixDispatch restart = appService.restartFixRun(projectId);
        tracks.remove(0).run();
        // 重派轨同样烧满 3 次尝试（全败）：第 7 次调用即重派首试
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(9)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(6).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(List.of("第二次意见")));
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

        appService.startFixRun(projectId, "首场意见（将超限）");
        assertThat(appService.startFixRun(projectId, "排队意见").queued()).isTrue();
        tracks.remove(0).run();

        // 首场 3 败 + 合并续派 1 成；成功收工 → 无恢复面（首场超限不留陈账）
        verify(agentClient, times(4)).converse(any(), any());
        assertThatThrownBy(() -> appService.restartFixRun(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.FIX_RESTART_UNAVAILABLE.message());
    }

    // ---------- 派发阶段帧（#50：修正轨的 fixing / done 发射） ----------

    /** 阶段帧捕获（只收 dispatch-stage，发射序即阶段序）。 */
    private List<String> givenStageCapture() {
        List<String> stages = new ArrayList<>();
        doAnswer(invocation -> {
            if (AgentEventTypes.DISPATCH_STAGE.equals(invocation.getArgument(0))) {
                stages.add(String.valueOf(
                        invocation.<Map<String, Object>>getArgument(1)
                                .get(AgentEventTypes.DISPATCH_STAGE_FIELD)));
            }
            return null;
        }).when(streamAppService).publish(any(), any());
        return stages;
    }

    @Test
    void given_fix_closes_unchanged_when_settled_then_done_stage_changed_false_after_fix_unchanged() {
        // #50 完成态区分：changed=false → done(changed=false) 呈现「未动系统」，
        // 帧序 fix-unchanged（原因通告）→ done（状态条收口）
        Long projectId = persistedGeneratedProject("9930");
        List<Runnable> tracks = givenTrackQueued();
        List<String> stages = givenStageCapture();
        givenConverseFinishing(false, "纯文档性修订，系统现状已满足");

        appService.startFixRun(projectId, "改主色调");
        tracks.remove(0).run();

        assertThat(stages).containsExactly("fixing", "done");
        InOrder order = inOrder(streamAppService);
        order.verify(streamAppService).publish(eq(AgentEventTypes.FIX_UNCHANGED), anyMap());
        order.verify(streamAppService).publish(eq(AgentEventTypes.DISPATCH_STAGE), argThat(payload ->
                "done".equals(payload.get(AgentEventTypes.DISPATCH_STAGE_FIELD))
                        && Boolean.FALSE.equals(payload.get(AgentEventTypes.DISPATCH_CHANGED_FIELD))));
    }

    @Test
    void given_queued_continuation_when_track_merges_then_fixing_and_done_per_run() {
        // #50：每场修正 run 一组 fixing → done（首场 + 排队合并续场同口径——状态条
        // 跟着 run 边界推进，不静默）；changed=true 无 fix-unchanged（既有行为）
        Long projectId = persistedGeneratedProject("9931");
        List<Runnable> tracks = givenTrackQueued();
        List<String> stages = givenStageCapture();
        givenConverseSucceeds();

        appService.startFixRun(projectId, "意见一");
        assertThat(appService.startFixRun(projectId, "意见二").queued()).isTrue();
        tracks.remove(0).run(); // 首场收口即合并续场

        assertThat(stages).containsExactly("fixing", "done", "fixing", "done");
        verify(streamAppService, never()).publish(eq(AgentEventTypes.FIX_UNCHANGED), anyMap());
    }

    // ---------- 测试数据 ----------

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
