package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 入口派发编排（#47 三分类）：脚本化智能体边界——分类调用按会话前缀脚本化回放
 * 标签，验证三岔：咨询 → 助理会话命令 + 回答（零派发：无 BA、无修正 run）；
 * 分类失败 / 超时 / 输出不可解析 → 兜底按意见（BA 链）；兜底 / 下单意图 →
 * guide-reply 帧（零产物：不起任何 run）+ 下单引导文案。守卫先于分类（拒绝即
 * 零调用零帧）。
 */
@SpringBootTest
class DispatchAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private DispatchAppService appService;

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
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }

    /** 脚本化分类（classify-* 会话回放标签；其余会话按普通回复）。 */
    private void givenClassification(String label, String otherReply) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX)) {
                return new AgentReply(command.runId(), label);
            }
            return new AgentReply(command.runId(), otherReply);
        });
    }

    private Long persistedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("派发项目", null,
                Long.parseLong(workspaceId), OWNER));
        return project.getId();
    }

    private Long persistedGeneratedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("派发项目", null,
                Long.parseLong(workspaceId), OWNER));
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    @Test
    void given_inquiry_when_dispatch_then_assistant_session_answers_with_zero_dispatch() {
        // 咨询 → 助理会话命令 + 回答；零派发：无 BA converse、无修正 run、无 savePrd 面
        Long projectId = persistedProject("9800");
        givenSessionExecutorRunsInline();
        givenClassification("INQUIRY", "系统访问地址是 http://localhost:32168/。");

        DispatchAppService.DispatchRun run = appService.dispatch(projectId, "我后台的地址是什么？");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand classify = command.getAllValues().get(0);
        AgentCommand assistant = command.getAllValues().get(1);

        // 分类命令：智能体边界轻量调用——一次性会话、缺省 flash 档、不触项目工作区、
        // 计量 agentKind=classify、短超时、无流关联（空 sink 无帧）
        assertThat(classify.sessionId()).startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX);
        assertThat(classify.modelString()).isNull();
        assertThat(classify.workspaceId()).isNull();
        assertThat(classify.agentRole()).isNull();
        assertThat(classify.live()).isFalse();
        assertThat(classify.timeout()).isEqualTo(java.time.Duration.ofSeconds(15));
        assertThat(classify.usageContext().dims()).containsEntry(
                UsageDims.KEY_AGENT_KIND, UsageDims.AGENT_KIND_CLASSIFY);

        // 助理命令：assist-{projectId} 会话 + ASSISTANT 角色卡 + flash 档 + 只读工作区
        assertThat(assistant.sessionId()).isEqualTo("assist-" + projectId);
        assertThat(assistant.prompt()).isEqualTo("我后台的地址是什么？");
        assertThat(assistant.systemPrompt()).isEqualTo(RolePreset.ASSISTANT.systemPrompt());
        assertThat(assistant.modelString()).isEqualTo(RolePreset.ASSISTANT.chatModelString());
        assertThat(assistant.agentRole()).isEqualTo(RolePreset.ASSISTANT.name());
        assertThat(assistant.workspaceReadOnly()).isTrue();
        assertThat(assistant.workspaceId()).isEqualTo("9800");
        assertThat(assistant.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(assistant.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(RolePreset.ASSISTANT),
                        "assist-" + projectId));
        assertThat(assistant.live()).isFalse();

        // 回答经 SSE 到达（runId = 助理轮）；role-assigned(ASSISTANT) 前置
        assertThat(run.runId()).isEqualTo(assistant.runId());
        verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED),
                argThat(payload -> "ASSISTANT".equals(payload.get(AgentEventTypes.ROLE_FIELD))
                        && RolePreset.ASSISTANT.getName()
                                .equals(payload.get(AgentEventTypes.ROLE_LABEL_FIELD))));
        // 零派发断言：两 converse 之外无任何轨道（修正 run 会是第三条 coder- 会话命令）
        assertThat(command.getAllValues().stream()
                .map(AgentCommand::sessionId)).noneMatch(id -> id.startsWith("coder-"));
        verify(streamAppService, never()).publish(eq(AgentEventTypes.GUIDE_REPLY), anyMap());
    }

    @Test
    void given_classification_failure_when_dispatch_then_opinion_chain() {
        // 分类调用炸（失败/超时同 catch）→ 兜底按意见：BA 链照常（误进意见链有 BA 把关）
        Long projectId = persistedProject("9801");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX)) {
                throw new IllegalStateException("分类超时");
            }
            return new AgentReply(command.runId(), "BA 已受理");
        });

        appService.dispatch(projectId, "把主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand ba = command.getAllValues().get(1);
        assertThat(ba.sessionId()).isEqualTo("ba-" + projectId);
        assertThat(ba.systemPrompt()).isEqualTo(RolePreset.BA.systemPrompt());
        assertThat(ba.workspaceReadOnly()).isFalse();
    }

    @Test
    void given_unparsable_classification_when_dispatch_then_opinion_chain() {
        // 输出不可解析（自由文本）→ 同失败口径：回落意见链
        Long projectId = persistedProject("9802");
        givenSessionExecutorRunsInline();
        givenClassification("这句话既不是标签也不是分类", "BA 已受理");

        appService.dispatch(projectId, "嗯嗯");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).sessionId()).isEqualTo("ba-" + projectId);
    }

    @Test
    void given_fallback_when_dispatch_then_guide_reply_frame_and_zero_runs() {
        // 兜底 → 平台定型引导（guide-reply 帧直达，零产物：不提交任何会话轨道）
        Long projectId = persistedProject("9803");
        givenClassification("FALLBACK", "不该出现");

        DispatchAppService.DispatchRun run = appService.dispatch(projectId, "你好呀");

        verify(agentClient, times(1)).converse(any(), any()); // 仅分类调用
        verify(sessionExecutor, never()).submit(any(), any());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(streamAppService).publish(eq(AgentEventTypes.GUIDE_REPLY), payload.capture());
        assertThat(payload.getValue())
                .containsEntry(AgentStreamAppService.PROJECT_FIELD, projectId.toString())
                .containsEntry(AgentEventTypes.RUN_FIELD, run.runId())
                .containsEntry(AgentEventTypes.GUIDE_PROMPT_FIELD, "你好呀")
                .containsEntry(AgentEventTypes.GUIDE_LABEL_FIELD, DispatchAppService.GUIDE_LABEL)
                .containsEntry(AgentEventTypes.GUIDE_TEXT_FIELD, DispatchAppService.GUIDE_GENERIC_TEXT);
    }

    @Test
    void given_order_intent_when_generated_then_guide_points_to_confirm_button() {
        Long projectId = persistedGeneratedProject("9804");
        givenClassification("ORDER_INTENT", "不该出现");

        appService.dispatch(projectId, "我想下单了");

        verify(streamAppService).publish(eq(AgentEventTypes.GUIDE_REPLY), argThat(
                payload -> String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                        .contains("确认下单")));
        verify(agentClient, times(1)).converse(any(), any());
    }

    @Test
    void given_order_intent_when_not_generated_then_guide_states_entry_timing() {
        // 未生成时如实说明入口出现时机——不指引尚不存在的东西
        Long projectId = persistedProject("9805");
        givenClassification("ORDER_INTENT", "不该出现");

        appService.dispatch(projectId, "多少钱？怎么买");

        verify(streamAppService).publish(eq(AgentEventTypes.GUIDE_REPLY), argThat(
                payload -> String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                        .contains("首次生成完成")));
    }

    @Test
    void given_archived_project_when_dispatch_then_prj_013_before_classification() {
        // 守卫先于分类：拒绝即零调用零帧（分类调用也是要花钱的模型调用）
        Project project = projectRepository.save(Project.create("归档项目", null,
                9806L, OWNER));
        project.archive();
        Long projectId = projectRepository.save(project).getId();

        assertThatThrownBy(() -> appService.dispatch(projectId, "再聊聊"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        verify(agentClient, never()).converse(any(), any());
        verify(streamAppService, never()).publish(any(), anyMap());
    }

    @Test
    void given_opinion_when_dispatch_then_role_assigned_precedes_converse() {
        // 意见链帧序不回归：role-assigned(BA) → converse（分类在帧前静默完成）
        Long projectId = persistedProject("9807");
        givenSessionExecutorRunsInline();
        givenClassification("OPINION", "好的");

        appService.dispatch(projectId, "加个导出功能");

        InOrder order = inOrder(streamAppService, agentClient);
        order.verify(streamAppService).publish(eq(AgentEventTypes.ROLE_ASSIGNED), argThat(
                payload -> "BA".equals(payload.get(AgentEventTypes.ROLE_FIELD))));
        // 帧后的那条 converse 即 BA 轮（分类 converse 在帧前静默完成——总量 2）
        order.verify(agentClient).converse(any(), any());
        verify(agentClient, times(2)).converse(any(), any());
    }

    // ---------- 分类输出解析（容错口径） ----------

    @Test
    void given_classifier_outputs_when_parse_then_first_recognized_token_wins() {
        assertThat(DispatchAppService.parse("OPINION")).isEqualTo(
                new DispatchAppService.Classification(DispatchAppService.MessageClass.OPINION, false));
        assertThat(DispatchAppService.parse("inquiry")).isEqualTo(
                new DispatchAppService.Classification(DispatchAppService.MessageClass.INQUIRY, false));
        assertThat(DispatchAppService.parse("FALLBACK")).isEqualTo(
                new DispatchAppService.Classification(DispatchAppService.MessageClass.FALLBACK, false));
        assertThat(DispatchAppService.parse("ORDER_INTENT")).isEqualTo(
                new DispatchAppService.Classification(DispatchAppService.MessageClass.FALLBACK, true));
        assertThat(DispatchAppService.parse("标签：ORDER")).isEqualTo(
                new DispatchAppService.Classification(DispatchAppService.MessageClass.FALLBACK, true));
        // 带解释的输出（容错：单标签命中即归类）
        assertThat(DispatchAppService.parse("INQUIRY（用户在问地址）")).isEqualTo(
                new DispatchAppService.Classification(DispatchAppService.MessageClass.INQUIRY, false));
        // 多标签（模型违规解释，如「不是 INQUIRY，是 OPINION」）= 歧义 → 按意见：
        // 「误判为咨询会丢变更」不可接受，先出现者获胜会放大该风险
        assertThat(DispatchAppService.parse("不是 INQUIRY，是 OPINION")).isEqualTo(
                new DispatchAppService.Classification(DispatchAppService.MessageClass.OPINION, false));
        assertThat(DispatchAppService.parse("")).isNull();
        assertThat(DispatchAppService.parse(null)).isNull();
        assertThat(DispatchAppService.parse("不知道")).isNull();
    }
}
