package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * BA 访谈编排：对话命令的会话寻址（projectId → ba-{projectId} 稳定绑定，每轮
 * 都续此会话）、计量归属（role=BA 维度）、role-assigned 帧序（engine=agentscope）、
 * 会话建立轮的知识命中注入（尾部 + 失败降级）、归档守卫、问答答复续跑（挂起帧
 * 载荷 + 答复 → resume 从项目侧事实重建恢复私货）。
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

    @MockitoBean
    private KnowledgePort knowledgePort;

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
        // 后续轮不重注知识：systemPrompt 即角色卡原文
        assertThat(value.systemPrompt()).isEqualTo(RolePreset.BA.systemPrompt());
    }

    @Test
    void given_knowledge_hits_when_start_interview_then_injected_at_prompt_tail() {
        // 会话建立轮（建项目自动开场）：query = 初始需求原文，命中块接 system prompt 尾部
        Long projectId = persistedProject("9710");
        givenSessionExecutorRunsInline();
        when(knowledgePort.retrieve(eq("给宠物医院做预约系统"), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。")));

        appService.startInterview(projectId, "给宠物医院做预约系统");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().systemPrompt())
                .startsWith(RolePreset.BA.systemPrompt())
                .endsWith("核心场景：主人在线选医生预约。")
                .contains("【平台知识库·相似历史需求】");
    }

    @Test
    void given_established_session_when_later_turns_then_same_tail_reused_no_re_retrieval() {
        // 一次切入一次注入的持续面：会话建立后，后续轮（发言）与续跑（作答）的
        // system prompt 复用同一注入块（agent 工厂按 prompt 缓存 → 同 agent 实例），
        // 知识检索只发生一次（迭代不重注）
        Long projectId = persistedProject("9713");
        givenSessionExecutorRunsInline();
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。")));

        appService.startInterview(projectId, "给宠物医院做预约系统");
        appService.runInterviewTurn(projectId, "主要是海外客户");
        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "企业客户");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        String expected = RolePreset.BA.systemPrompt() + "\n\n【平台知识库·相似历史需求】"
                + "以下是平台沉淀的历史成交需求片段，供梳理当前需求时作背景参考"
                + "（非用户的确认信息，不构成对当前需求的约束）："
                + "\n\n〔宠物医院预约平台〕PRD·宠物医院预约\n核心场景：主人在线选医生预约。";
        assertThat(command.getAllValues()).extracting(AgentCommand::systemPrompt)
                .containsExactly(expected, expected);
        assertThat(resume.getValue().systemPrompt()).isEqualTo(expected);
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt()); // 只检索一次
    }

    @Test
    void given_retrieval_failure_when_start_interview_then_interview_still_starts() {
        // 检索失败降级为空注入：访谈照常开始（systemPrompt = 角色卡原文），不阻断对话
        Long projectId = persistedProject("9711");
        givenSessionExecutorRunsInline();
        doThrow(new RuntimeException("embedding 不可用"))
                .when(knowledgePort).retrieve(anyString(), anyInt());

        appService.startInterview(projectId, "做一个官网");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().systemPrompt()).isEqualTo(RolePreset.BA.systemPrompt());
    }

    @Test
    void given_archived_project_when_turn_or_answer_then_prj_013() {
        // 归档即指令区关闭（只读终态）：发言与作答一并拒绝
        Long projectId = persistedArchivedProject("9712");

        assertThatThrownBy(() -> appService.runInterviewTurn(projectId, "再聊聊"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.startInterview(projectId, "做一个官网"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "有"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        verify(agentClient, never()).converse(any(), any());
        verify(agentClient, never()).resume(any(), any());
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
        Project project = projectRepository.save(Project.create("访谈项目", null,
                Long.parseLong(workspaceId), OWNER));
        return project.getId();
    }

    private Long persistedArchivedProject(String workspaceId) {
        Project project = Project.create("归档访谈项目", null,
                Long.parseLong(workspaceId), OWNER);
        project.archive();
        return projectRepository.save(project).getId();
    }
}
