package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * BA 访谈编排：对话命令的会话寻址（projectId → ba-{projectId} 稳定绑定，每轮
 * 都续此会话）、计量归属（role=BA 维度）、role-assigned 帧序（engine=agentscope）、
 * 问答答复续跑（挂起帧载荷 + 答复 → resume 从项目侧事实重建恢复私货）。
 */
@SpringBootTest
class BaInterviewAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private BaInterviewAppService appService;

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

    @Test
    void given_project_when_interview_turn_then_command_bound_to_ba_session() {
        Long projectId = persistedProject("9700");
        givenSessionExecutorRunsInline();

        BaInterviewAppService.InterviewRun run = appService.runInterviewTurn(projectId, "做一个官网");

        // 对话命令全要素：BA 会话稳定绑定 + owner 寻址 + 角色卡模型/人格 + 计量归属 + 项目工作区 + 流关联
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        AgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo("做一个官网");
        assertThat(value.sessionId()).isEqualTo("ba-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).contains("ask_user");
        assertThat(value.modelString()).isEqualTo(RolePreset.BA.chatModelString());
        assertThat(value.workspaceId()).isEqualTo("9700");
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).containsEntry("role", "BA");
    }

    @Test
    void given_turn_when_run_then_role_assigned_first_then_converse() {
        Long projectId = persistedProject("9701");
        givenSessionExecutorRunsInline();

        appService.runInterviewTurn(projectId, "梳理需求");

        // 帧序 role-assigned（engine=agentscope）→ task-start（converse 内）
        InOrder order = inOrder(streamAppService, agentClient);
        order.verify(streamAppService).publish(org.mockito.ArgumentMatchers.eq(
                        AgentEventTypes.ROLE_ASSIGNED),
                org.mockito.ArgumentMatchers.anyMap());
        order.verify(agentClient).converse(any(), any());
    }

    @Test
    void given_turn_frames_when_bridge_publishes_then_project_id_injected_per_frame() {
        // 流桥：编排注入的关联字段（projectId）逐帧并入 payload（帧序在前——寻址
        // 字段不覆盖帧本体字段）；发射失败不断流（护栏）
        Long projectId = persistedProject("9702");
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent> sink =
                    invocation.getArgument(1);
            sink.accept(new com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent(
                    "text", new java.util.LinkedHashMap<>(Map.of("runId", "run-x"))));
            return null;
        }).when(agentClient).converse(any(), any());

        appService.runInterviewTurn(projectId, "做一个官网");

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(org.mockito.ArgumentMatchers.eq("text"), payload.capture());
        assertThat(payload.getValue()).containsEntry("projectId", projectId.toString())
                .containsEntry("runId", "run-x");
    }

    @Test
    void given_question_answer_when_resume_then_rebuilt_from_project_facts() {
        // 问答答复续跑：恢复私货从项目侧事实重建（会话/owner/工作区/角色卡/计量），
        // 待确认工具来自挂起帧载荷——不信前端回传的恢复私货
        Long projectId = persistedProject("9703");
        givenSessionExecutorRunsInline();
        List<Map<String, Object>> pendingToolCalls = List.of(Map.of(
                "id", "tc-1", "name", "ask_user",
                "input", Map.of("question", "目标用户是谁？")));

        appService.answerQuestion(projectId, "run-q", "reply-9", pendingToolCalls, "海外企业客户");

        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        AgentResume value = resume.getValue();
        assertThat(value.runId()).isEqualTo("run-q");
        assertThat(value.sessionId()).isEqualTo("ba-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.workspaceId()).isEqualTo("9703");
        assertThat(value.modelString()).isEqualTo(RolePreset.BA.chatModelString());
        assertThat(value.systemPrompt()).isEqualTo(RolePreset.BA.systemPrompt());
        assertThat(value.replyId()).isEqualTo("reply-9");
        assertThat(value.resumeText()).isEqualTo("海外企业客户");
        assertThat(value.confirmResults()).hasSize(1);
        assertThat(value.confirmResults().get(0).getToolCall().getInput())
                .containsEntry("answer", "海外企业客户");
        assertThat(value.usageContext().dims()).containsEntry("role", "BA");
    }

    @Test
    void given_missing_project_when_turn_then_prj_001() {
        assertThatThrownBy(() -> appService.runInterviewTurn(-1L, "梳理需求"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 测试数据 ----------

    private Long persistedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("访谈项目", null, "agentscope",
                Long.parseLong(workspaceId), OWNER));
        return project.getId();
    }
}
