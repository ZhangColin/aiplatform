package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 助理职能体（#47 咨询零产物短路）：会话寻址（projectId → assist-{projectId}
 * 稳定绑定）+ 角色卡/模型/只读工作区姿态（workspaceReadOnly——工具面只读的结构
 * 前提）+ 计量归属（agentKind=assistant）+ role-assigned 帧序；
 * <b>零产物的行为验证</b>：一轮应答全程无任何写类事件——不触知识检索、无
 * document-updated、无修正 run（无 coder 会话命令）、PRD 与系统状态位不动。
 */
@SpringBootTest
class AssistantAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private AssistantAppService appService;

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
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_consultation_when_answer_then_command_bound_to_assistant_readonly_session() {
        Project project = projectRepository.save(Project.create("咨询项目", null, 9810L, OWNER));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        // 脚本化边界：回放一段回答并产一帧流事件（帧经流桥到达）
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(new AgentEvent("text", new java.util.LinkedHashMap<>(
                    Map.of("runId", ((AgentCommand) invocation.getArgument(0)).runId()))));
            return new AgentReply(((AgentCommand) invocation.getArgument(0)).runId(),
                    "系统访问地址是 http://localhost:32168/。");
        }).when(agentClient).converse(any(), any());

        AssistantAppService.AssistantRun run = appService.answer(project, "我后台的地址是什么？");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        AgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo("我后台的地址是什么？");
        assertThat(value.sessionId()).isEqualTo("assist-" + project.getId());
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).isEqualTo(RolePreset.ASSISTANT.systemPrompt());
        assertThat(value.modelString()).isEqualTo(RolePreset.ASSISTANT.chatModelString());
        assertThat(value.agentRole()).isEqualTo(RolePreset.ASSISTANT.name());
        assertThat(value.workspaceReadOnly()).isTrue(); // 只读面：写面结构性关闭
        assertThat(value.workspaceId()).isEqualTo("9810");
        assertThat(value.live()).isFalse();
        assertThat(value.usageContext().dims()).isEqualTo(
                UsageDims.of(project.getId(), UsageDims.kindOf(RolePreset.ASSISTANT),
                        "assist-" + project.getId()));

        // 帧序：role-assigned(ASSISTANT) 前置 → 流帧（关联字段注入）
        InOrder order = inOrder(streamAppService, agentClient);
        order.verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED),
                argThat(payload -> "ASSISTANT".equals(payload.get(AgentEventTypes.ROLE_FIELD))));
        order.verify(agentClient).converse(any(), any());
        verify(streamAppService).publish(eq("text"), argThat(payload ->
                projectIdOf(payload).equals(project.getId().toString())));
    }

    @Test
    void given_answer_turn_when_completed_then_zero_artifact_events() {
        // 零产物的行为验证：一轮应答收口后——无 document-updated、无知识检索、
        // 无修正 run（唯一一条 converse 即助理轮本身）、状态位不动
        Project project = projectRepository.save(Project.create("咨询项目", null, 9811L, OWNER));
        project.markPrdProduced();
        project.markGenerated();
        project = projectRepository.save(project);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        when(agentClient.converse(any(), any())).thenAnswer(invocation ->
                new AgentReply(((AgentCommand) invocation.getArgument(0)).runId(), "查不到，不知道"));

        appService.answer(project, "现在的报价是多少？");

        verify(agentClient, times(1)).converse(any(), any()); // 无 coder- 第二轮 = 无修正 run
        verify(streamAppService, never()).publish(
                eq(com.aieducenter.aiplatform.business.project.application.ProjectEventTypes.DOCUMENT_UPDATED),
                anyMap());
        verify(knowledgePort, never()).retrieve(anyString(), anyInt()); // 咨询不知识命中
        Project after = projectRepository.findById(project.getId()).orElseThrow();
        assertThat(after.getPrdProducedAt()).isEqualTo(project.getPrdProducedAt());
        assertThat(after.getGeneratedAt()).isEqualTo(project.getGeneratedAt());
        assertThat(after.getArchivedAt()).isNull();
    }

    private static String projectIdOf(Map<String, Object> payload) {
        return String.valueOf(payload.get(AgentStreamAppService.PROJECT_FIELD));
    }
}
