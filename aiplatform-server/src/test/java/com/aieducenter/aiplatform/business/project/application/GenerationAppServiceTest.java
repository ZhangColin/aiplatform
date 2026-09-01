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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 生成编排（#22 验收 + #24 知识命中前置注入）：编码命令全要素（coder-{projectId}
 * 会话稳定绑定、CODER 角色卡与长任务超时、owner 寻址、计量 dims（projectId +
 * agentKind=coder + sessionId）、项目工作区、流关联）、工作区布局资产就位先于
 * 首试下发（AGENTS.md 平台约定幂等覆写）、知识命中前置注入首试任务 prompt
 * （query = 任务 prompt；检索失败降级空注入不阻断）、重试不重注入（续同会话，
 * 注入块已在会话历史）、成功收口落 generated_at、失败自动重试（task-retrying 帧
 * + 话术 + 重试 prompt 换轨）、超限转终态（generated_at 不落、在途守卫释放可
 * 重新发起）、守卫组（不存在 / 已归档 / 已生成 / 在途重复触发）。
 */
@SpringBootTest
class GenerationAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private GenerationAppService appService;

    @Autowired
    private GenerationProperties properties;

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
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private KnowledgePort knowledgePort;

    /** 两命中的检索桩（下发前置注入 happy path）。 */
    private void givenKnowledgeHits() {
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。"),
                new KnowledgeHit("PRD", "连锁诊所系统", "PRD·连锁诊所管理", "范围边界：不含库存。")));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }

    private void givenAgentsMdWriteSucceeds() {
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
    }

    @Test
    void given_project_when_generate_then_command_bound_to_coder_session() {
        Long projectId = persistedProject("9800");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 编码命令全要素：coder 会话稳定绑定 + owner 寻址 + CODER 角色卡（平台技术
        // 约定）+ 长任务超时 + 计量 dims（#24：projectId + agentKind=coder +
        // sessionId）+ 项目工作区 + 流关联
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        AgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo(GenerationAppService.GENERATE_TASK_PROMPT);
        assertThat(value.sessionId()).isEqualTo("coder-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).isEqualTo(RolePreset.CODER.systemPrompt())
                .contains("0.0.0.0:8081").contains("docs/PRD.md");
        assertThat(value.modelString()).isEqualTo(RolePreset.CODER.chatModelString());
        assertThat(value.timeout()).isEqualTo(properties.getTimeout());
        assertThat(value.workspaceId()).isEqualTo("9800");
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).isEqualTo(UsageDims.of(projectId,
                UsageDims.kindOf(RolePreset.CODER), "coder-" + projectId));
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        // 编码 run 开直播（#23）：过程帧外并产直播帧；BA 对话命令不带（对话不流式）
        assertThat(value.live()).isTrue();
    }

    @Test
    void given_knowledge_hits_when_generate_then_prefix_injected_before_task_prompt() {
        // 知识命中前置注入（#24）：query = 首试任务 prompt，命中块拼在任务 prompt 前
        Long projectId = persistedProject("9810");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenKnowledgeHits();

        appService.startGeneration(projectId);

        verify(knowledgePort).retrieve(eq(GenerationAppService.GENERATE_TASK_PROMPT), eq(5));
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .startsWith("【平台知识库·相似历史需求】")
                .contains("宠物医院预约平台").contains("非用户的确认信息")
                .endsWith("————\n\n" + GenerationAppService.GENERATE_TASK_PROMPT);
    }

    @Test
    void given_retrieval_failure_when_generate_then_degraded_plain_prompt_run_proceeds() {
        // 检索失败降级空注入：任务 prompt 原样下发，run 不被知识面阻断
        Long projectId = persistedProject("9811");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        doThrow(new RuntimeException("pgvector 抖动")).when(knowledgePort)
                .retrieve(anyString(), anyInt());

        appService.startGeneration(projectId);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().prompt())
                .isEqualTo(GenerationAppService.GENERATE_TASK_PROMPT);
    }

    @Test
    void given_generate_when_start_then_agents_md_written_before_first_converse() {
        // 工作区布局资产就位先于首试下发：AGENTS.md 平台约定写入工作区根（幂等覆写）
        Long projectId = persistedProject("9801");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();

        appService.startGeneration(projectId);

        InOrder order = inOrder(workspaceLifecycleAppService, agentClient);
        order.verify(workspaceLifecycleAppService).exec(eq("9801"),
                argThat((WorkspaceExecCommand cmd) ->
                        cmd.command().contains("/workspace/AGENTS.md")
                                && cmd.command().contains("工作区平台约定")
                                && cmd.command().contains("8081")));
        order.verify(agentClient).converse(any(), any());
    }

    @Test
    void given_success_when_run_finishes_then_generated_at_persisted() {
        Long projectId = persistedProject("9802");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();

        appService.startGeneration(projectId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_first_attempt_fails_when_retry_then_retrying_frame_then_second_succeeds() {
        Long projectId = persistedProject("9803");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        givenKnowledgeHits(); // 首试带前置注入，重试不重注入（注入块已在会话历史）
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("首次尝试中断"))
                .thenReturn(new AgentReply("run-2", "系统已生成"));

        GenerationAppService.GenerationRun run = appService.startGeneration(projectId);

        // 重试换新 runId（首试 runId 只属于第一次尝试）、prompt 换重试续作轨
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        List<AgentCommand> attempts = command.getAllValues();
        assertThat(attempts.get(0).runId()).isEqualTo(run.runId());
        assertThat(attempts.get(0).prompt())
                .endsWith("————\n\n" + GenerationAppService.GENERATE_TASK_PROMPT);
        assertThat(attempts.get(1).runId()).isNotEqualTo(run.runId());
        assertThat(attempts.get(1).prompt()).isEqualTo(GenerationAppService.RETRY_TASK_PROMPT);
        assertThat(attempts.get(1).sessionId()).isEqualTo("coder-" + projectId);
        // 一次下发一次注入：重试续同会话不重检索
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt());

        // 重试帧：锚定失败的那次尝试、携带下一尝试序号与话术（SSE事件清单 task-retrying 行）
        verify(streamAppService).publish(eq(AgentEventTypes.TASK_RETRYING), argThat(payload ->
                run.runId().equals(payload.get(AgentStreamAppService.RUN_FIELD))
                        && Integer.valueOf(2).equals(
                                payload.get(AgentEventTypes.RETRY_ATTEMPT_FIELD))
                        && "遇到问题，正在重试".equals(
                                payload.get(AgentEventTypes.RETRY_MESSAGE_FIELD))));

        // 第二次尝试成功 → generated_at 落位
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_all_attempts_fail_when_exceed_limit_then_terminal_without_generated_at() {
        Long projectId = persistedProject("9804");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("持续失败"));

        appService.startGeneration(projectId);

        // 超限转终态：恰 maxAttempts 次尝试、重试帧 maxAttempts-1 次、generated_at 不落
        int maxAttempts = properties.getMaxAttempts();
        verify(agentClient, times(maxAttempts)).converse(any(), any());
        verify(streamAppService, times(maxAttempts - 1))
                .publish(eq(AgentEventTypes.TASK_RETRYING), anyMap());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNull();

        // 在途守卫已释放：用户重新发起兜底路径可再走（generated_at 未落 = 按钮口径仍在）
        // ——重打桩走 doReturn（旧 thenThrow 仍在效期，when() 内调用会先抛）
        doReturn(new AgentReply("run-again", "系统已生成"))
                .when(agentClient).converse(any(), any());
        appService.startGeneration(projectId);
        verify(agentClient, times(maxAttempts + 1)).converse(any(), any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_converse_ok_but_service_unreachable_when_generate_then_no_generated_at_and_reinitiate_exit() {
        // 假完成（#35）：converse 正常结束（模型道歉式放弃 / 被 maxIters 掐断）但 8081
        // 不可达——核验不过不落 generated_at，走既有重试/终态失败路径，重新发起出口仍在。
        Long projectId = persistedProject("9812");
        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds(); // AGENTS.md 写入（无 curl 字样）成功
        // 收口核验探针（curl 8081）不可达 → 核验不过（按命令内容区分，不依赖调用次序）
        when(workspaceLifecycleAppService.exec(any(), argThat((WorkspaceExecCommand cmd) ->
                cmd.command().contains("curl"))))
                .thenReturn(new ExecResultResponse("", "Connection refused", 7));
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("run-fake", "很抱歉，目前系统尚未真正实现出来"));

        appService.startGeneration(projectId);

        // 核验不过 → 重试到超限转终态：converse 满 maxAttempts 次、重试帧 maxAttempts-1 次
        int maxAttempts = properties.getMaxAttempts();
        verify(agentClient, times(maxAttempts)).converse(any(), any());
        verify(streamAppService, times(maxAttempts - 1))
                .publish(eq(AgentEventTypes.TASK_RETRYING), anyMap());
        // 假完成不落 generated_at（AC①：8081 不可达不再落 generated_at）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNull();

        // 项目不被空壳锁死（AC②）：generated_at 未落 = 重新发起出口在——核验改可达后重发即成功
        doReturn(new ExecResultResponse("", "", 0))
                .when(workspaceLifecycleAppService).exec(any(), any());
        appService.startGeneration(projectId);
        verify(agentClient, times(maxAttempts + 1)).converse(any(), any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId)).isNotNull();
    }

    @Test
    void given_generation_in_flight_when_trigger_again_then_prj_017() {
        // 在途守卫（含已提交未起跑）：异步轨道占位期间重复触发拒绝
        Long projectId = persistedProject("9805");
        givenAgentsMdWriteSucceeds();
        List<Runnable> queued = new ArrayList<>();
        doAnswer(invocation -> {
            queued.add((Runnable) invocation.getArgument(1));
            return null;
        }).when(sessionExecutor).submit(any(), any());

        appService.startGeneration(projectId);

        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
        verify(agentClient, never()).converse(any(), any());

        // 排队任务收尾（守卫释放，不污染同上下文的后续测试）
        when(agentClient.converse(any(), any()))
                .thenReturn(new AgentReply("run-x", "系统已生成"));
        queued.forEach(Runnable::run);
    }

    @Test
    void given_archived_or_generated_project_when_generate_then_rejected() {
        Long archivedId = persistedArchivedProject("9806");
        Long generatedId = persistedGeneratedProject("9807");

        assertThatThrownBy(() -> appService.startGeneration(archivedId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.startGeneration(generatedId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_ALREADY_REQUESTED.message());
        verify(agentClient, never()).converse(any(), any());
    }

    @Test
    void given_prd_never_produced_when_generate_then_prj_018() {
        // 「无门」指待定项不设门；PRD 从未产出 = 动作成立的前置事实缺失——直连调用
        // 也拦（编码 run 的任务就是读 PRD，无 PRD 起跑只会空烧重试）
        Long projectId = projectRepository.save(Project.create("无 PRD 项目", null,
                9809L, OWNER)).getId();

        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.GENERATION_PRD_NOT_PRODUCED.message());
        verify(agentClient, never()).converse(any(), any());
    }

    @Test
    void given_missing_project_when_generate_then_prj_001() {
        assertThatThrownBy(() -> appService.startGeneration(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_agents_md_write_fails_when_generate_then_no_run_and_guard_released() {
        // 资产就位失败如实上抛（环境故障口径），不起跑 run、在途守卫释放
        Long projectId = persistedProject("9808");
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "disk full", 1));

        assertThatThrownBy(() -> appService.startGeneration(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
        verify(agentClient, never()).converse(any(), any());

        givenSessionExecutorRunsInline();
        givenAgentsMdWriteSucceeds();
        doReturn(new AgentReply("run-y", "系统已生成"))
                .when(agentClient).converse(any(), any());
        appService.startGeneration(projectId);
        verify(agentClient).converse(any(), any());
    }

    // ---------- 测试数据 ----------

    /** 可生成形态的项目（PRD 已产出、未生成、未归档）。 */
    private Long persistedProject(String workspaceId) {
        Project project = Project.create("生成项目", null, Long.parseLong(workspaceId), OWNER);
        project.markPrdProduced();
        return projectRepository.save(project).getId();
    }

    private Long persistedArchivedProject(String workspaceId) {
        Project project = Project.create("归档生成项目", null, Long.parseLong(workspaceId), OWNER);
        project.archive();
        return projectRepository.save(project).getId();
    }

    private Long persistedGeneratedProject(String workspaceId) {
        Project project = Project.create("已生成项目", null, Long.parseLong(workspaceId), OWNER);
        project.markGenerated();
        return projectRepository.save(project).getId();
    }
}
