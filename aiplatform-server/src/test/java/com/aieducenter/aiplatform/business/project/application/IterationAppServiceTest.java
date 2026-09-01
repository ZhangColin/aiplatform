package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 迭代编排（#26 验收）：修正 run 与生成同机制（coder-{projectId} 会话稳定绑定 +
 * 同工作区 + CODER 角色卡 + live + 计量 dims + 知识命中前置注入 + 失败自动重试
 * run-retrying 帧）；run 在途时新任务排队（不即派）、当前 run 收口后合并为一场
 * 修正续派（排队意见不丢、不逐条烧 run）；守卫组（不存在 / 已归档 / 未生成）。
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

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private AgentStreamAppService streamAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private KnowledgePort knowledgePort;

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

    private void givenConverseSucceeds() {
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("fix-run", "修正完成"));
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
            runInFlight.countDown();
            releaseRun.await();
            return new AgentReply("fix-run", "修正完成");
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
                .thenReturn(new AgentReply("fix-run-2", "修正完成"));

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
        verify(agentClient, never()).converse(any(), any());
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
