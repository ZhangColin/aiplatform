package com.semgravio.aiplatform.business.project.application;

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
import java.util.function.Consumer;
import java.util.List;
import java.time.LocalDateTime;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentSuspension;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.project.application.dto.command.MessageAttachment;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.DesignDivergence;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 入口派发编排（#47 三分类，#86 并轨后意见与咨询同主智能体会话）：脚本化智能体
 * 边界——分类调用按会话前缀脚本化回放标签，验证三岔：咨询 → 主智能体答询轮
 * 命令 + 回答（同 main-{projectId} 会话、零派发：无修正 run）；分类失败 / 超时 /
 * 输出不可解析 → 兜底按意见（意见轮照常）；兜底 / 下单意图 → guide-reply 事件
 * （零产物：不起任何 run）+ 下单引导文案。守卫矩阵（#51 后移）：归档全局先于
 * 分类（拒绝即零调用零事件）；订单冻结 / 挂起问答只拦意见（分类后拦——咨询与
 * 兜底随时可答）。
 */
@IntegrationTest
class DispatchAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private DispatchAppService appService;

    @Autowired
    private ConversationHistoryAppService conversationHistory;

    @Autowired
    private CodingRunTrack codingRunTrack;

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

    @BeforeEach
    void ensureChannelKeyForRealResolution() {
        // 真解析链护卫（ADR-0031）：测试环境无 key 注入——幂等补测试 key
        ModelTierSeed.ensureChannelKey(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_design_items");
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());
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
        Project project = projectRepository.save(Project.create("派发项目", null, null, Long.parseLong(workspaceId), OWNER, null));
        return project.getId();
    }

    private Long persistedGeneratedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("派发项目", null, null, Long.parseLong(workspaceId), OWNER, null));
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

        // 分类命令：智能体边界轻量调用——一次性会话、模型档走边缘点档位链（#366：
        // classification 档位库值优先缺省 flash → 解析单点产模型串＋通道注入——
        // 后台改档下一轮派发即生效）、不触项目工作区、计量 agentKind=classify、
        // 短超时、无流关联（空 sink 无事件）
        assertThat(classify.sessionId()).startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX);
        assertThat(classify.modelString()).isEqualTo(ModelTierSeed.FLASH_MODEL_STRING);
        assertThat(classify.modelChannel()).isNotNull(); // 通道注入（key/baseUrl 入 SDK）
        assertThat(classify.workspaceId()).isNull();
        assertThat(classify.agentKey()).isNull();
        assertThat(classify.timeout()).isEqualTo(java.time.Duration.ofSeconds(15));
        assertThat(classify.usageContext().dims()).containsEntry(
                UsageDims.KEY_AGENT_KIND, UsageDims.AGENT_KIND_CLASSIFY);

        // 答询轮命令：main-{projectId} 会话（与意见轮同会话）+ 主智能体配置 + flash 档 + 只读面
        assertThat(inquiry.sessionId()).isEqualTo("main-" + projectId);
        assertThat(inquiry.prompt()).isEqualTo("我后台的地址是什么？");
        assertThat(inquiry.systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt());
        assertThat(inquiry.modelString()).isEqualTo(ModelTierSeed.FLASH_MODEL_STRING);
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
        // #445：意见轮合法收口（挂起问答在场）——不触发悬空纠偏链，converse 恒 2
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 意见轮提交守卫：放行
                .thenReturn(true);  // 意见轮收口观测：挂起问答（合法收口）

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
        // #445：意见轮合法收口——不触发悬空纠偏链，converse 恒 2
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 意见轮提交守卫：放行
                .thenReturn(true);  // 意见轮收口观测：挂起问答（合法收口）

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
        // 对话史（#89）：轻引导也落库（用户发言 + 定型文案两行——刷新后引导不消失）
        // kind 为 Integer code（1=user 6=guide，正本 ConversationEntryKind）
        assertThat(conversationHistory.read(projectId))
                .extracting(entry -> entry.kind(), entry -> entry.text())
                .containsExactly(
                        Tuple.tuple(1, "你好呀"),
                        Tuple.tuple(6, DispatchAppService.GUIDE_GENERIC_TEXT));
    }

    @Test
    void given_order_intent_when_generated_then_guide_points_to_confirm_button() {
        Long projectId = persistedGeneratedProject("9804");
        givenClassification("ORDER_INTENT", "不该出现");

        appService.dispatch(projectId, "我想下单了");

        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY), argThat(
                payload -> String.valueOf(payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))
                        .contains("确认此版本，准备签约")));
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
                        .contains("Demo 生成完成后")));
    }

    @Test
    void given_archived_project_when_dispatch_then_prj_013_before_classification() {
        // 全局守卫（归档 = 指令区物理关闭，咨询与兜底也停）先于分类：拒绝即零调用零事件
        Project project = projectRepository.save(Project.create("归档项目", null, null, 9806L, OWNER, null));
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
    void given_pending_question_without_anchor_when_inquiry_then_prj_024_restart_edge() {
        // 挂起问答期间咨询的处置（#86 复审收敛）：正常竞态（挂起事实在本进程）由
        // MainAgentAppService 转作答复续跑（与作答通道同路，见其灵魂用例）；本测
        // 脚本化丢锚重启边角——挂起仍在但本进程无锚（run 无表丢 runId），同步 409
        // 指路作答（好过异步错误气泡），分类先烧一次 flash 轻调用（接受）
        Long projectId = persistedProject("9808");
        givenClassification("INQUIRY", "不该到");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(true); // 会话挂起在即、锚已丢（重启边角）

        assertThatThrownBy(() -> appService.dispatch(projectId, "我后台的地址是什么？"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());
        verify(agentClient, times(1)).converse(any(), any()); // 仅分类调用
        verify(sessionExecutor, never()).submit(any(), any());
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
        // #445：意见轮合法收口——不触发悬空纠偏链，converse 恒 2
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 意见轮提交守卫：放行
                .thenReturn(true);  // 意见轮收口观测：挂起问答（合法收口）

        appService.dispatch(projectId, "加个导出功能");

        // 意见链：意见轮 converse 到达（分类 converse 在其前静默完成——总量 2）
        verify(agentClient, times(2)).converse(any(), any());
    }

    // ---------- #291 设计物作用域：直达改稿（跨件回溯的会话路由） ----------

    @Test
    void given_design_item_scope_when_dispatched_then_direct_revision_without_classification() {
        // 灵魂用例（#291）：designItem 在场即跳过三分类与主智能体轮——发言直达目标件
        // 设计会话改稿（designer 座、同会话继续、prompt 携意见与新代纪律）；发言照
        // 落对话史（回访可见）；零分类调用（无 classify- 会话）
        Long projectId = persistedDesignScopeProject("9760", 1, 2);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submitGeneration(any(), any());
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null;
            }
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.RUN_START, command.runId(),
                    Map.of("prompt", command.prompt())));
            sink.accept(AgentEventScripts.scripted(AgentEventTypes.RUN_FINISH, command.runId(),
                    Map.of(AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "已出新代", null, List.of());
        });

        DispatchAppService.DispatchRun run = appService.dispatch(projectId,
                "刚才那稿的颜色再亮一点", MessageAttachment.NONE, 1, DesignDivergence.REIMAGINE);

        assertThat(run).isNotNull();
        ArgumentCaptor<AgentCommand> commands = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(1)).converse(commands.capture(), any()); // 零分类、零主智能体轮
        AgentCommand revision = commands.getValue();
        assertThat(revision.sessionId())
                .isEqualTo(DesignProcessAppService.itemSession(projectId, 1));
        assertThat(revision.agentKey()).isEqualTo(AgentProfile.DESIGNER.key());
        assertThat(revision.prompt())
                .contains("刚才那稿的颜色再亮一点")
                .contains("新一代候选")
                .contains("大胆"); // #294 发散度档位进改稿 prompt（chip 同语义通道）
        // 发言落对话史（用户面照见——回访水合）
        assertThat(conversationHistory.read(projectId))
                .anySatisfy(entry -> assertThat(entry.text())
                        .contains("刚才那稿的颜色再亮一点"));
    }

    @Test
    void given_design_item_scope_when_track_in_flight_then_queued() {
        // 作用域改稿在途排队（#291 在途插话）：设计轨在途——发言落库＋意见排队
        //（返回发言锚），当前稿代收口后受理（受理面归 DesignProcessAppServiceTest）
        Long projectId = persistedDesignScopeProject("9761", 2, 2);
        // 手工占位轨道（在途形态）
        assertThat(codingRunTrack.begin(projectId)).isTrue();
        try {
            DispatchAppService.DispatchRun run = appService.dispatch(projectId,
                    "海报文字加大", MessageAttachment.NONE, 2, null);

            assertThat(run).isNotNull();
            verify(agentClient, never()).converse(any(), any()); // 未起 run（排队中）
            assertThat(conversationHistory.read(projectId))
                    .anySatisfy(entry -> assertThat(entry.text()).contains("海报文字加大"));
        }
        finally {
            codingRunTrack.end(projectId);
        }
    }

    @Test
    void given_missing_design_item_scope_when_dispatched_then_rejected() {
        // 作用域守卫：件不存在 404 PRJ_047；件未产出稿（待跑）409 PRJ_048
        Long projectId = persistedDesignScopeProject("9762", 1, 1);

        assertThatThrownBy(() -> appService.dispatch(projectId, "改稿", MessageAttachment.NONE, 9, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.DESIGN_ITEM_NOT_FOUND.message());

        jdbcTemplate.update("UPDATE prj_design_items SET status = 1 WHERE project_id = ?",
                projectId); // 退回待跑（未产出稿）
        assertThatThrownBy(() -> appService.dispatch(projectId, "改稿", MessageAttachment.NONE, 1, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.DESIGN_ITEM_NOT_READY.message());
    }

    /** 设计主线项目＋现行清单件行（closedOrd 件已收口、其余待跑；PRD 锚对齐）。 */
    private Long persistedDesignScopeProject(String workspaceId, int closedOrd, int total) {
        Project project = Project.create("设计作用域项目", null, null, Long.parseLong(workspaceId), OWNER, null);
        project.switchEndpoint(ProjectEndpointType.DESIGN, null);
        project.markPrdProduced();
        Long projectId = projectRepository.save(project).getId();
        LocalDateTime anchor = projectRepository.findById(projectId).orElseThrow()
                .getPrdProducedAt();
        for (int ord = 1; ord <= total; ord++) {
            jdbcTemplate.update("""
                    INSERT INTO prj_design_items (id, project_id, ord, title, status,
                        prd_produced_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, System.nanoTime(), projectId, ord, "设计物" + ord,
                    ord <= closedOrd ? 2 : 1, anchor);
        }
        return projectId;
    }

    // ---------- 悬空接管（ADR-0032，#373）：标记期输入绕过入口三分类 ----------

    @Test
    void given_dangling_marker_when_dispatch_short_confirm_then_straight_to_opinion_without_classification() {
        // 灵魂用例（#373）：访谈悬空标记期内「OK」直达意见轮——零分类调用（无第二个
        // classify- 会话）、零兜底引导事件（不再回与项目状态无关的定型文案——「装忙
        // 复读」症状消除），起意见轮 run（主智能体把关）
        Long projectId = persistedProject("9820");
        givenSessionExecutorRunsInline();
        givenClassification("OPINION", "好的"); // 首轮分类意见 → 意见轮悬空收口置标记
        // #445：首轮意见轮双失格走完纠偏链才置标记（守卫/收口观测/两轮纠偏判据
        // 均 false）；接管轮「OK」合法收口（挂起问答在场）清标记
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 意见轮提交守卫：放行
                .thenReturn(false)  // 意见轮收口观测：悬空（进纠偏链）
                .thenReturn(false)  // 纠偏轮①判据：失格
                .thenReturn(false)  // 纠偏轮②判据：失格（置标记）
                .thenReturn(false)  // 接管轮提交守卫：放行
                .thenReturn(true);  // 接管轮收口观测：挂起问答（合法收口清标记）

        appService.dispatch(projectId, "做一个官网");

        DispatchAppService.DispatchRun ok = appService.dispatch(projectId, "OK");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(5)).converse(command.capture(), any());
        // 会话序：分类 → 意见轮 → 纠偏①②（#445 链内轮，main- 同会话）→ 悬空接管的
        // 意见轮（「OK」零分类直达）
        assertThat(command.getAllValues().get(0).sessionId())
                .startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX);
        assertThat(command.getAllValues().get(1).sessionId()).isEqualTo("main-" + projectId);
        assertThat(command.getAllValues().get(2).sessionId()).isEqualTo("main-" + projectId);
        assertThat(command.getAllValues().get(3).sessionId()).isEqualTo("main-" + projectId);
        AgentCommand takeover = command.getAllValues().get(4);
        assertThat(takeover.sessionId()).isEqualTo("main-" + projectId);
        assertThat(takeover.prompt()).isEqualTo("OK");
        assertThat(takeover.runId()).isEqualTo(ok.runId());
        // 零兜底引导：全程不出定型文案事件（悬空补救是另一族文案，见 MainAgentAppServiceTest）
        verify(eventsAppService, never()).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                argThat(payload -> DispatchAppService.GUIDE_GENERIC_TEXT.equals(
                        payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))));
    }

    @Test
    void given_dangling_marker_cleared_when_legal_close_then_classification_resumes() {
        // 标记清除即恢复分类：悬空后的恢复轮以挂起问答合法收口 → 标记清除 → 后续
        // 输入回入口三分类（FALLBACK 引导照常——接管不是常态）
        Long projectId = persistedProject("9821");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command == null) {
                return null; // 重打桩空参
            }
            if (command.sessionId().startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX)) {
                return new AgentReply(command.runId(),
                        "你好呀".equals(command.prompt()) ? "FALLBACK" : "OPINION");
            }
            if (command.prompt().contains("官网")) { // 首个意见轮：悬空叙述收口
                return new AgentReply(command.runId(), "好的");
            }
            if (command.prompt().contains("平台纠正")) { // #445 纠偏轮：纯文本重答（不落挂起锚）
                return new AgentReply(command.runId(), "已纠正");
            }
            // 恢复轮：再问一问（挂起问答——合法收口清标记）
            return new AgentReply(command.runId(), "先问一下", new AgentSuspension(
                    "reply-1", List.of(Map.of("id", "tc-1", "name", "ask_user"))));
        });
        // #445：首轮意见轮双失格走完纠偏链才置标记（守卫/收口观测/两轮纠偏判据
        // 均 false——纠偏轮纯文本重答不落挂起锚）；恢复轮「继续」合法收口清标记
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 意见轮提交守卫：放行
                .thenReturn(false)  // 意见轮收口观测：悬空（进纠偏链）
                .thenReturn(false)  // 纠偏轮①判据：失格
                .thenReturn(false)  // 纠偏轮②判据：失格（置标记）
                .thenReturn(false)  // 恢复轮提交守卫：放行
                .thenReturn(true);  // 恢复轮收口观测：挂起问答（清标记）

        appService.dispatch(projectId, "做一个官网"); // 分类 OPINION → 意见轮悬空收口
        appService.dispatch(projectId, "继续");        // 接管直达意见轮 → 挂起问答收口（清标记）
        appService.dispatch(projectId, "你好呀");      // 恢复分类 → FALLBACK 引导

        // 会话序：分类 → 意见轮 → 纠偏①② → 接管意见轮（零分类）→ 分类（恢复）＝恰 6 次
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(6)).converse(command.capture(), any());
        List<String> sessions = command.getAllValues().stream()
                .map(AgentCommand::sessionId).toList();
        assertThat(sessions.stream()
                .filter(id -> id.startsWith(DispatchAppService.CLASSIFY_SESSION_PREFIX)))
                .hasSize(2); // 悬空窗口内的「继续」不分类，标记清除后「你好呀」回分类
        // 兜底引导恢复（定型文案照常——接管解除）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.GUIDE_REPLY),
                argThat(payload -> DispatchAppService.GUIDE_GENERIC_TEXT.equals(
                        payload.get(AgentEventTypes.GUIDE_TEXT_FIELD))));
    }

    @Test
    void given_dangling_marker_when_order_frozen_then_takeover_still_guards_ord_006() {
        // 标记期守卫照拦（ADR-0032 验收补齐）：悬空接管直达意见轮——订单冻结守卫
        // 随意见轮自带面照拦（接管不绕守卫），拒绝即零提交
        Long projectId = persistedProject("9822");
        givenSessionExecutorRunsInline();
        givenClassification("OPINION", "好的");

        appService.dispatch(projectId, "做一个官网"); // 意见轮悬空收口置标记
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 1, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9915L, projectId);

        assertThatThrownBy(() -> appService.dispatch(projectId, "OK"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_FROZEN.message());
        // 首个意见轮＋纠偏两轮共三次提交（#445：悬空收口先进纠偏链、双失格才置
        // 标记——hasAskingToolCall 缺省 false 即双失格路径）；被拒接管零新提交
        verify(sessionExecutor, times(3)).submit(any(), any());
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
