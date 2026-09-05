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
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 入口派发编排（#47 三分类，#86 并轨后意见与咨询同主智能体会话）：脚本化智能体
 * 边界——分类调用按会话前缀脚本化回放标签，验证三岔：咨询 → 主智能体答询轮
 * 命令 + 回答（同 main-{projectId} 会话、零派发：无修正 run）；分类失败 / 超时 /
 * 输出不可解析 → 兜底按意见（意见轮照常）；兜底 / 下单意图 → guide-reply 事件
 * （零产物：不起任何 run）+ 下单引导文案。守卫矩阵（#51 后移）：归档全局先于
 * 分类（拒绝即零调用零事件）；订单冻结 / 挂起问答只拦意见（分类后拦——咨询与
 * 兜底随时可答）。
 */
@SpringBootTest
class DispatchAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private DispatchAppService appService;

    @Autowired
    private DispatchProperties dispatchProperties;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private EventsAppService eventsAppService;

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

    /** 脚本化分类（classify-* 会话回放标签；其余会话按普通回复）。同测重打桩时
     * when() 的 matcher 空参调用会触发旧 answer——null 直接过，不当真调用。 */
    private void givenClassification(String label, String otherReply) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null; // 重打桩空参（Mockito when() 触发旧 answer）
            }
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
    void given_inquiry_when_dispatch_then_main_session_answers_with_zero_dispatch() {
        // 咨询 → 主智能体答询轮命令 + 回答（同会话直答）；零派发：无修正 run、无 savePrd 面
        Long projectId = persistedProject("9800");
        givenSessionExecutorRunsInline();
        givenClassification("INQUIRY", "系统访问地址是 http://localhost:32168/。");

        DispatchAppService.DispatchRun run = appService.dispatch(projectId, "我后台的地址是什么？");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand classify = command.getAllValues().get(0);
        AgentCommand inquiry = command.getAllValues().get(1);

        // 分类命令：智能体边界轻量调用——一次性会话、模型档由专用配置键决定（#51，
        // 缺省 flash，不吃 agentscope 缺省模型的部署配法）、不触项目工作区、
        // 计量 agentKind=classify、短超时、无流关联（空 sink 无事件）
        assertThat(classify.sessionId()).startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX);
        assertThat(classify.modelString()).isEqualTo(dispatchProperties.getClassificationModel());
        assertThat(dispatchProperties.getClassificationModel())
                .isEqualTo("deepseek:deepseek-v4-flash"); // 缺省即 flash 档（代码保证）
        assertThat(classify.workspaceId()).isNull();
        assertThat(classify.agentKey()).isNull();
        assertThat(classify.timeout()).isEqualTo(java.time.Duration.ofSeconds(15));
        assertThat(classify.usageContext().dims()).containsEntry(
                UsageDims.KEY_AGENT_KIND, UsageDims.AGENT_KIND_CLASSIFY);

        // 答询轮命令：main-{projectId} 会话（与意见轮同会话）+ 主智能体配置 + flash 档 + 只读面
        assertThat(inquiry.sessionId()).isEqualTo("main-" + projectId);
        assertThat(inquiry.prompt()).isEqualTo("我后台的地址是什么？");
        assertThat(inquiry.systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt());
        assertThat(inquiry.modelString()).isEqualTo(AgentProfile.MAIN.chatModelString());
        assertThat(inquiry.agentKey()).isEqualTo(AgentProfile.MAIN.key());
        assertThat(inquiry.workspaceReadOnly()).isTrue();
        assertThat(inquiry.workspaceId()).isEqualTo("9800");
        assertThat(inquiry.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(inquiry.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN),
                        "main-" + projectId));

        // 回答经 SSE 到达（runId = 答询轮）；收缩验收（#82）：role-assigned 零发射
        assertThat(run.runId()).isEqualTo(inquiry.runId());
        verify(eventsAppService, never()).publishAgentEvent(eq("role-assigned"), anyMap());
        // 零派发断言：两 converse 之外无任何轨道（修正 run 会是第三条 coder- 会话命令）
        assertThat(command.getAllValues().stream()
                .map(AgentCommand::sessionId)).noneMatch(id -> id.startsWith("coder-"));
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY), anyMap());
    }

    @Test
    void given_classification_failure_when_dispatch_then_opinion_chain() {
        // 分类调用炸（失败/超时同 catch）→ 兜底按意见：意见链照常（误进意见链有主智能体把关）
        Long projectId = persistedProject("9801");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX)) {
                throw new IllegalStateException("分类超时");
            }
            return new AgentReply(command.runId(), "已受理");
        });

        appService.dispatch(projectId, "把主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand opinion = command.getAllValues().get(1);
        assertThat(opinion.sessionId()).isEqualTo("main-" + projectId);
        assertThat(opinion.systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt());
        assertThat(opinion.workspaceReadOnly()).isTrue(); // 主智能体恒只读面（写面结构性关闭）
    }

    @Test
    void given_unparsable_classification_when_dispatch_then_opinion_chain() {
        // 输出不可解析（自由文本）→ 同失败口径：回落意见链
        Long projectId = persistedProject("9802");
        givenSessionExecutorRunsInline();
        givenClassification("这句话既不是标签也不是分类", "已受理");

        appService.dispatch(projectId, "嗯嗯");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).sessionId()).isEqualTo("main-" + projectId);
    }

    @Test
    void given_fallback_when_dispatch_then_guide_reply_frame_and_zero_runs() {
        // 兜底 → 平台定型引导（guide-reply 事件直达，零产物：不提交任何会话轨道）
        Long projectId = persistedProject("9803");
        givenClassification("FALLBACK", "不该出现");

        DispatchAppService.DispatchRun run = appService.dispatch(projectId, "你好呀");

        verify(agentClient, times(1)).converse(any(), any()); // 仅分类调用
        verify(sessionExecutor, never()).submit(any(), any());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY), payload.capture());
        assertThat(payload.getValue())
                .containsEntry(EventsAppService.PROJECT_FIELD, projectId.toString())
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

        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY), argThat(
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

        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY), argThat(
                payload -> String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                        .contains("首次生成完成")));
    }

    @Test
    void given_archived_project_when_dispatch_then_prj_013_before_classification() {
        // 全局守卫（归档 = 指令区物理关闭，咨询与兜底也停）先于分类：拒绝即零调用零事件
        Project project = projectRepository.save(Project.create("归档项目", null,
                9806L, OWNER));
        project.archive();
        Long projectId = projectRepository.save(project).getId();

        assertThatThrownBy(() -> appService.dispatch(projectId, "再聊聊"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        verify(agentClient, never()).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(any(), anyMap());
    }

    // ---------- 守卫后移矩阵（#51：订单冻结 / 挂起问答只拦意见） ----------

    @Test
    void given_pending_question_when_inquiry_then_answered() {
        // 挂起问答期间咨询照常作答（守卫后移——409 指路对咨询语义错误；答询轮无守卫，
        // 同会话直答。引擎对挂起会话新 converse 的拒绝仅在问答卡未及呈现的窄竞态）
        Long projectId = persistedProject("9808");
        givenSessionExecutorRunsInline();
        givenClassification("INQUIRY", "系统访问地址是 http://localhost:32168/。");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(true); // 会话挂起在即（答询轮不查守卫）

        DispatchAppService.DispatchRun run = appService.dispatch(projectId, "我后台的地址是什么？");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        // 分类 + 答询两跳即全部：意见轨道未触（第三跳会是 main- 意见轮 + 守卫 409）
        assertThat(command.getAllValues().get(1).sessionId()).isEqualTo("main-" + projectId);
        assertThat(run.runId()).isEqualTo(command.getAllValues().get(1).runId());
    }

    @Test
    void given_pending_question_when_fallback_then_guide_reply() {
        // 挂起问答期间兜底照常引导（零产物路径不涉意见链）
        Long projectId = persistedProject("9809");
        givenClassification("FALLBACK", "不该出现");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(true);

        appService.dispatch(projectId, "你好呀");

        verify(agentClient, times(1)).converse(any(), any()); // 仅分类调用
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY), anyMap());
    }

    @Test
    void given_pending_question_when_opinion_then_prj_024_after_classification() {
        // 意见仍 409 指路作答（行为不变）——守卫在分类后拦：被拒意见先烧一次
        // flash 分类调用（秒级轻调用，接受），拒绝即零事件零提交
        Long projectId = persistedProject("9810");
        givenClassification("OPINION", "不该到");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(true);

        assertThatThrownBy(() -> appService.dispatch(projectId, "把主色调改成绿色"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());
        verify(agentClient, times(1)).converse(any(), any()); // 分类先烧、意见轮未触
        verify(sessionExecutor, never()).submit(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(any(), anyMap());
    }

    @Test
    void given_active_order_when_inquiry_or_fallback_then_answerable() {
        // 订单冻结（下单即冻结迭代）只拦意见链：咨询与兜底随时可答
        Long projectId = persistedProject("9811");
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 1, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9911L, projectId);
        givenSessionExecutorRunsInline();
        givenClassification("INQUIRY", "系统访问地址是 http://localhost:32168/。");

        appService.dispatch(projectId, "我后台的地址是什么？"); // 咨询：答询轮照常作答

        givenClassification("FALLBACK", "不该出现");
        appService.dispatch(projectId, "你好呀"); // 兜底：引导照常

        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY), anyMap());
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(command.capture(), any()); // 分类×2 + 答询
        assertThat(command.getAllValues().stream()
                .map(AgentCommand::sessionId)).noneMatch(id -> id.startsWith("coder-"));
    }

    @Test
    void given_active_order_when_opinion_then_ord_006() {
        // 订单处理中意见仍拒收（行为不变）——分类后拦，取消订单即解冻
        Long projectId = persistedProject("9812");
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 1, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9912L, projectId);
        givenClassification("OPINION", "不该到");

        assertThatThrownBy(() -> appService.dispatch(projectId, "再改一个地方"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_FROZEN.message());
        verify(agentClient, times(1)).converse(any(), any()); // 仅分类调用
        verify(sessionExecutor, never()).submit(any(), any());
    }

    @Test
    void given_opinion_when_dispatch_then_opinion_turn_converses_after_classification() {
        // 意见链不回归：意见轮 converse 到达（分类在其前静默完成）
        Long projectId = persistedProject("9807");
        givenSessionExecutorRunsInline();
        givenClassification("OPINION", "好的");

        appService.dispatch(projectId, "加个导出功能");

        // 意见链：意见轮 converse 到达（分类 converse 在其前静默完成——总量 2）
        verify(agentClient, times(2)).converse(any(), any());
    }

    // ---------- 分类输出解析（容错口径） ----------

    @Test
    void given_classifier_outputs_when_parse_then_single_label_hit_and_multi_label_falls_back_to_opinion() {
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
