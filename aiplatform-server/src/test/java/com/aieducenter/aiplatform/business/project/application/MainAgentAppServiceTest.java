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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import cn.hutool.core.collection.CollUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentSuspension;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 主智能体单会话编排（#86 并轨，ADR 0006）：对话命令的会话寻址（projectId →
 * main-{projectId} 稳定绑定，追问 / 答询 / 受理意见每轮都续此会话——脚本化多轮
 * 验收「追问 → 咨询 → 提意见全部落在同一会话」）、计量归属（agentKind=main）、
 * 只读工作区姿态（workspaceReadOnly——写面结构性关闭的前提）、会话建立轮的知识
 * 命中注入（尾部 + 失败降级）、归档守卫、问答答复续跑（挂起事件载荷 + 答复 →
 * resume 从项目侧事实重建恢复私货）；<b>咨询零产物短路</b>（#47 语义随并轨保持：
 * 同会话直答、不锚意见、不派修正 run）；链必达收口（#43）——意见轮收口（无挂起
 * 问答）且项目已生成时平台自动派修正 run（模型不调派发工具也必达；未生成止于
 * 对话；在途排队合并；追问挂起待答复收口再派）；交接物补齐（#52）——savePrd 的
 * summary 终值与「本轮无修订」占位口径入修正 run prompt，本轮判定从零起算（上轮
 * 残留不进）；意见轮在途意见排队成轮（#54）——意见锚随会话任务落（在途窗口连发
 * 各自成轮各自派发、零丢失），converse 炸即清锚（重提即兜底）；排队合并逐轮配对
 * （#55）——交接物各轮「意见 → 修订说明」一一对应、未修订轮显式占位（配对由
 * 平台拼装锚定，不靠清单位置对齐）。
 */
@SpringBootTest
class MainAgentAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private MainAgentAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FinishEditFacts finishFixFacts;

    @Autowired
    private PrdRevisionFacts prdRevisions;

    @MockitoBean
    private AgentscopeAgentClient agentClient;

    @MockitoBean
    private EventsAppService eventsAppService;

    @MockitoBean
    private AgentSessionExecutor sessionExecutor;

    @MockitoBean
    private KnowledgePort knowledgePort;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @BeforeEach
    void defaultAgentReplies() {
        // 轮落定点读 converse/resume 返回维护挂起问答会合锚——默认回复形状兜底
        // （个别用例自带脚本后打桩覆盖之，Mockito 后桩胜出）。同测重打桩时 when()
        // 的 matcher 空参调用会触发旧 answer——null 直接过，不当真调用（同
        // DispatchAppServiceTest.givenClassification 先例）
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return command != null ? new AgentReply(command.runId(), "好的") : null;
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation -> {
            AgentResume resume = invocation.getArgument(0);
            return resume != null ? new AgentReply(resume.runId(), "好的") : null;
        });
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }

    /** 同生产语义吞掉轨道异常（异步轨道失败经 error 事件表达，执行器只记日志）——
     * 炸轮用例的执行器形态。 */
    private void givenSessionExecutorSwallowsFailures() {
        doAnswer(invocation -> {
            try {
                ((Runnable) invocation.getArgument(1)).run();
            }
            catch (RuntimeException e) {
                // 生产执行器吞掉记日志（异步轨道），此处同语义
            }
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }

    /** 脚本化智能体边界（#46 收口以 finish_edit 事实为准）：主智能体正常回复；
     * 自动派发的修正 run 收口即调 finish_edit（changed=true——coder 会话才记，
     * 忠实于工具面）。 */
    private void givenConverseMainRepliesAndExecutorFinishes(String mainReply) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("coder-")) {
                finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            }
            return new AgentReply(command.runId(), mainReply);
        });
    }

    /** 脚本化智能体边界（#52 修订事实观测）：主智能体轮内调 savePrd(content,
     * summary) 的工具执行事实（真引擎内工具执行在此 mock 收口——登记即调用事实），
     * 一轮多次调用按序落多条（终值胜出）；修正 run 收口脚本沿用。 */
    private void givenConverseMainSavesPrdAndExecutorFinishes(String... summaries) {
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                for (String summary : summaries) {
                    prdRevisions.record(command.workspaceId(), summary);
                }
                return new AgentReply(command.runId(), "已按意见修订 PRD");
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });
    }

    /** 已生成形态的项目（迭代期收口派修正的前提事实）。 */
    private Long persistedGeneratedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("迭代访谈项目", null,
                Long.parseLong(workspaceId), OWNER));
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    @Test
    void given_project_when_opinion_turn_then_command_bound_to_main_session() {
        Long projectId = persistedProject("9700");
        givenSessionExecutorRunsInline();

        MainAgentAppService.MainAgentRun run = appService.runOpinionTurn(projectId, "做一个官网");

        // 对话命令全要素：主智能体会话稳定绑定 + owner 寻址 + 配置模型/协议 + 计量归属
        // + 项目工作区 + 流关联 + 只读面（写面结构性关闭）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        AgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo("做一个官网");
        assertThat(value.sessionId()).isEqualTo("main-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.systemPrompt()).contains("ask_user");
        assertThat(value.modelString()).isEqualTo(AgentProfile.MAIN.chatModelString());
        assertThat(value.agentKey()).isEqualTo(AgentProfile.MAIN.key());
        assertThat(value.workspaceReadOnly()).isTrue();
        assertThat(value.workspaceId()).isEqualTo("9700");
        assertThat(value.streamCorrelation()).containsEntry("projectId", projectId.toString());
        assertThat(value.usageContext().subject()).isEqualTo(projectId.toString());
        assertThat(value.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), "main-" + projectId));
        // 后续轮不重注知识：systemPrompt 即配置原文
        assertThat(value.systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt());
    }

    @Test
    void given_scripted_multi_turn_when_followup_inquiry_opinion_then_all_same_session() {
        // 灵魂用例（#86 验收①：脚本化多轮——追问 → 咨询 → 提意见全部落在同一会话，
        // 连续不换会话）：开场追问挂起（question-raised）→ 答复续跑（resume 同会话）
        // → 咨询直答（同会话 converse，零产物不派 run）→ 意见受理（同会话 converse，
        // savePrd 修订 + 收口自动派修正 run）——全程 main-{projectId} 恒定，修正 run
        // 才走 coder-{projectId}
        Long projectId = persistedGeneratedProject("9705");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                if (command.prompt().contains("预约系统")) { // 开场轮：追问挂起
                    Consumer<AgentEvent> sink = invocation.getArgument(1);
                    sink.accept(new AgentEvent(AgentEventTypes.QUESTION_RAISED,
                            new java.util.LinkedHashMap<>(Map.of(
                                    "runId", command.runId(),
                                    AgentEventTypes.WAIT_SUMMARY_FIELD, "给谁用、谁来操作？"))));
                }
                if (command.prompt().contains("主色调")) { // 意见轮：修订 PRD
                    prdRevisions.record(command.workspaceId(), "主色调约定改为绿");
                }
                return new AgentReply(command.runId(), "好的");
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });
        // 挂起事实脚本：开场提交放行 → 开场收口见挂起（不派）→ 答复收口无挂起（派）
        // → 意见轮提交放行 → 意见轮收口无挂起（派）
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)
                .thenReturn(true)
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(false);

        appService.startConversation(projectId, "给宠物医院做预约系统");
        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "宠物主人自己操作");
        appService.answerInquiry(projectRepository.findById(projectId).orElseThrow(),
                "我后台的地址是什么？");
        appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");

        // 会话恒定：主智能体的三轮 converse（开场 / 咨询 / 意见）与一次 resume 全在
        // main-{projectId}；修正 run（开场意见自答后收口派 + 意见轮收口派）走 coder-
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(5)).converse(command.capture(), any());
        List<AgentCommand> all = command.getAllValues();
        assertThat(all).extracting(AgentCommand::sessionId)
                .containsExactly("main-" + projectId, "coder-" + projectId,
                        "main-" + projectId, "main-" + projectId, "coder-" + projectId);
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().sessionId()).isEqualTo("main-" + projectId);
        assertThat(resume.getValue().workspaceReadOnly()).isTrue();
        // 意见轮交接物：意见原文 + 修订说明（savePrd 事实）随修正 run 下发
        assertThat(all.get(4).prompt())
                .contains("把系统的主色调改成绿色")
                .contains("PRD 已修订")
                .contains("主色调约定改为绿");
    }

    @Test
    void given_knowledge_hits_when_start_conversation_then_injected_at_prompt_tail() {
        // 会话建立轮（建项目自动开场）：query = 初始需求原文，命中块接 system prompt 尾部
        Long projectId = persistedProject("9710");
        givenSessionExecutorRunsInline();
        when(knowledgePort.retrieve(eq("给宠物医院做预约系统"), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。")));

        appService.startConversation(projectId, "给宠物医院做预约系统");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().systemPrompt())
                .startsWith(AgentProfile.MAIN.systemPrompt())
                .endsWith("核心场景：主人在线选医生预约。")
                .contains("【平台知识库·相似历史需求】");
    }

    @Test
    void given_established_session_when_later_turns_then_same_tail_reused_no_re_retrieval() {
        // 一次切入一次注入的持续面：会话建立后，后续轮（发言/答询）与续跑（作答）的
        // system prompt 复用同一注入块（agent 工厂按 prompt 缓存 → 同 agent 实例），
        // 知识检索只发生一次（迭代不重注）
        Long projectId = persistedProject("9713");
        givenSessionExecutorRunsInline();
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。")));

        appService.startConversation(projectId, "给宠物医院做预约系统");
        appService.runOpinionTurn(projectId, "主要是海外客户");
        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "企业客户");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        String expected = AgentProfile.MAIN.systemPrompt() + "\n\n【平台知识库·相似历史需求】"
                + "以下是平台沉淀的历史成交需求片段，供梳理当前需求时作背景参考"
                + "（非用户的确认信息，不构成对当前需求的约束）："
                + "\n\n〔宠物医院预约平台〕PRD·宠物医院预约\n核心场景：主人在线选医生预约。";
        assertThat(command.getAllValues()).extracting(AgentCommand::systemPrompt)
                .containsExactly(expected, expected);
        assertThat(resume.getValue().systemPrompt()).isEqualTo(expected);
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt()); // 只检索一次
    }

    @Test
    void given_retrieval_failure_when_start_conversation_then_conversation_still_starts() {
        // 检索失败降级为空注入：访谈照常开始（systemPrompt = 配置原文），不阻断对话
        Long projectId = persistedProject("9711");
        givenSessionExecutorRunsInline();
        doThrow(new RuntimeException("embedding 不可用"))
                .when(knowledgePort).retrieve(anyString(), anyInt());

        appService.startConversation(projectId, "做一个官网");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any());
        assertThat(command.getValue().systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt());
    }

    @Test
    void given_archived_project_when_turn_or_answer_then_prj_013() {
        // 归档即对话区关闭（只读终态）：发言与作答一并拒绝
        Long projectId = persistedArchivedProject("9712");

        assertThatThrownBy(() -> appService.runOpinionTurn(projectId, "再聊聊"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThatThrownBy(() -> appService.startConversation(projectId, "做一个官网"))
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
    void given_active_order_when_turn_or_answer_then_ord_006_frozen() {
        // 下单即冻结迭代：未终结订单在即，对话区新意见与作答一并拒收（取消即解冻）
        Long projectId = persistedProject("9716");
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 1, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9913L, projectId);

        assertThatThrownBy(() -> appService.runOpinionTurn(projectId, "再改一个地方"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_FROZEN.message());
        assertThatThrownBy(() -> appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "有"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(OrderMessage.ORDER_FROZEN.message());
        verify(agentClient, never()).converse(any(), any());
        verify(agentClient, never()).resume(any(), any());
    }

    @Test
    void given_cancelled_order_when_turn_then_unfrozen() {
        // 取消即解冻：终态订单不再拦意见，迭代继续
        Long projectId = persistedProject("9714");
        jdbcTemplate.update(
                "INSERT INTO ord_orders (id, project_id, status, prd_snapshot, created_at, updated_at) "
                        + "VALUES (?, ?, 5, '# PRD', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                9914L, projectId);
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "继续改");

        verify(agentClient).converse(any(), any());
    }

    @Test
    void given_turn_when_run_then_no_role_assigned_emitted() {
        // 收缩验收（#82）：角色键已并入 run-start（converse 内发射），编排层不再
        // 前置 role-assigned——退役族零发射
        Long projectId = persistedProject("9701");
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "梳理需求");

        verify(agentClient).converse(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(eq("role-assigned"), anyMap());
    }

    @Test
    void given_turn_frames_when_bridge_publishes_then_project_id_injected_per_frame() {
        // 流桥：编排注入的关联字段（projectId）逐事件并入 payload（事件序在前——寻址
        // 字段不覆盖事件本体字段）；发射失败不断流（护栏）
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
            return new AgentReply(((AgentCommand) invocation.getArgument(0)).runId(), "好的");
        }).when(agentClient).converse(any(), any());

        appService.runOpinionTurn(projectId, "做一个官网");

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventsAppService).publishAgentEvent(org.mockito.ArgumentMatchers.eq("text"), payload.capture());
        assertThat(payload.getValue()).containsEntry("projectId", projectId.toString())
                .containsEntry("runId", "run-x");
    }

    @Test
    void given_question_answer_when_resume_then_rebuilt_from_project_facts() {
        // 问答答复续跑：恢复私货从项目侧事实重建（会话/owner/工作区/配置/计量），
        // 待确认工具来自挂起事件载荷——不信前端回传的恢复私货
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
        assertThat(value.sessionId()).isEqualTo("main-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.workspaceId()).isEqualTo("9703");
        assertThat(value.modelString()).isEqualTo(AgentProfile.MAIN.chatModelString());
        assertThat(value.systemPrompt()).isEqualTo(AgentProfile.MAIN.systemPrompt());
        assertThat(value.agentKey()).isEqualTo(AgentProfile.MAIN.key());
        assertThat(value.workspaceReadOnly()).isTrue(); // 续跑同只读面（#86：不漂移成读写面）
        assertThat(value.replyId()).isEqualTo("reply-9");
        assertThat(value.resumeText()).isEqualTo("海外企业客户");
        assertThat(value.confirmResults()).hasSize(1);
        // #34：答复走 block metadata（模型不可见通道），不进 input——input 只留原问题
        assertThat(value.confirmResults().get(0).getToolCall().getInput())
                .containsEntry("question", "目标用户是谁？")
                .doesNotContainKey("answer");
        assertThat(value.confirmResults().get(0).getToolCall().getMetadata())
                .containsEntry(AgentscopeAgentClient.ANSWER_METADATA_KEY, "海外企业客户");
        assertThat(value.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), "main-" + projectId));
    }

    @Test
    void given_missing_project_when_turn_then_prj_001() {
        assertThatThrownBy(() -> appService.runOpinionTurn(-1L, "梳理需求"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_pending_question_when_turn_then_prj_024_and_no_submission() {
        // 挂起问答守卫（#40 / ADR-0005）：问答待答期间意见新输入不盲提交 converse
        // ——引擎按 ASKING 态拒时 REST 已返 200、只见异步 error 事件；同步 409 指路
        // 作答。任何事件也不发（守卫先于提交）
        Long projectId = persistedProject("9715");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(true);

        assertThatThrownBy(() -> appService.runOpinionTurn(projectId, "测试：请继续"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());
        assertThatThrownBy(() -> appService.startConversation(projectId, "做一个官网"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());

        verify(agentClient, never()).converse(any(), any());
        verify(sessionExecutor, never()).submit(any(), any());
        verify(eventsAppService, never()).publishAgentEvent(any(), any());
        verify(eventsAppService, never()).publishNotification(any(), any());
    }

    // ---------- 咨询车道（#47 零产物短路，#86 并轨单会话） ----------

    @Test
    void given_consultation_when_answered_then_same_main_session_zero_artifact() {
        // 咨询与意见同会话（#86）：答询轮绑同一 main-{projectId}、只读面、配置=MAIN；
        // 零产物——已生成项目也不派修正 run（无第二轮 coder converse）、无
        // document-updated、不触知识检索、状态位不动
        Long projectId = persistedGeneratedProject("9810");
        Project project = projectRepository.findById(projectId).orElseThrow();
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return new AgentReply(command.runId(), "系统访问地址是 http://localhost:32168/。");
        });

        MainAgentAppService.MainAgentRun run = appService.answerInquiry(project,
                "我后台的地址是什么？");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient).converse(command.capture(), any()); // 唯一一条 = 答询轮本身
        AgentCommand value = command.getValue();
        assertThat(value.runId()).isEqualTo(run.runId());
        assertThat(value.prompt()).isEqualTo("我后台的地址是什么？");
        assertThat(value.sessionId()).isEqualTo("main-" + projectId);
        assertThat(value.userId()).isEqualTo(Long.toString(OWNER));
        assertThat(value.modelString()).isEqualTo(AgentProfile.MAIN.chatModelString());
        assertThat(value.agentKey()).isEqualTo(AgentProfile.MAIN.key());
        assertThat(value.workspaceReadOnly()).isTrue();
        assertThat(value.usageContext().dims()).isEqualTo(
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), "main-" + projectId));

        verify(eventsAppService, never()).publishNotification(
                eq(ProjectEventTypes.DOCUMENT_UPDATED), anyMap());
        verify(knowledgePort, never()).retrieve(anyString(), anyInt()); // 咨询不知识命中
        Project after = projectRepository.findById(projectId).orElseThrow();
        assertThat(after.getPrdProducedAt()).isEqualTo(project.getPrdProducedAt());
        assertThat(after.getGeneratedAt()).isEqualTo(project.getGeneratedAt());
        assertThat(after.getArchivedAt()).isNull();
    }

    @Test
    void given_suspended_question_when_inquiry_arrives_in_render_race_then_rerouted_as_answer_resume() {
        // 灵魂用例（#86 复审：渲染竞态收敛——错误气泡对对话体验不可接受）：意见轮
        // 以 ask_user 挂起（挂起事件已发、前端问答卡未及呈现的窗口）里经派发口到达
        // 的咨询不炸对话——转作答复续跑：同挂起 run 与 engineRef/工具面、咨询文本
        // 即答复文本，与作答通道完全同路；续跑收口（正常回复）后锚清，后续咨询回
        // 正常答询轮（新 converse）
        Long projectId = persistedGeneratedProject("9706");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) { // 意见轮：以 ask_user 挂起
                return new AgentReply(command.runId(), "先问一下", new AgentSuspension(
                        "reply-77", true, List.of(Map.of("id", "tc-9", "name", "ask_user"))));
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation ->
                new AgentReply(((AgentResume) invocation.getArgument(0)).runId(), "进展顺利"));
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 意见轮提交守卫：放行
                .thenReturn(true)   // 意见轮收口观测：挂起在即 → 不派、锚保留
                .thenReturn(false); // 答复续跑收口观测：无挂起 → 派修正（锚含咨询文本并入）

        MainAgentAppService.MainAgentRun opinion = appService.runOpinionTurn(projectId,
                "把系统的主色调改成绿色");
        MainAgentAppService.MainAgentRun inquiry = appService.answerInquiry(
                projectRepository.findById(projectId).orElseThrow(), "现在进展如何？");
        assertThat(inquiry.runId()).isEqualTo(opinion.runId()); // 响应锚 = 挂起 run（续跑同 run 收口）

        // 转答而非新 converse：唯一 main- converse 是意见轮本身（第二条是收口自动派的修正）
        ArgumentCaptor<AgentResume> resume = ArgumentCaptor.forClass(AgentResume.class);
        verify(agentClient).resume(resume.capture(), any());
        assertThat(resume.getValue().runId()).isEqualTo(opinion.runId());
        assertThat(resume.getValue().sessionId()).isEqualTo("main-" + projectId);
        assertThat(resume.getValue().replyId()).isEqualTo("reply-77");
        assertThat(resume.getValue().resumeText()).isEqualTo("现在进展如何？");
        assertThat(resume.getValue().confirmResults()).hasSize(1);
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues()).extracting(AgentCommand::sessionId)
                .containsExactly("main-" + projectId, "coder-" + projectId);

        // 续跑收口（正常回复）清锚：后续咨询回正常答询轮（新 captor——capture() 跨
        // verify 累积，复用旧 captor 会读到重复段）
        MainAgentAppService.MainAgentRun next = appService.answerInquiry(
                projectRepository.findById(projectId).orElseThrow(), "谢谢，没事了");
        ArgumentCaptor<AgentCommand> third = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(third.capture(), any());
        assertThat(third.getAllValues().get(2).sessionId()).isEqualTo("main-" + projectId);
        assertThat(third.getAllValues().get(2).runId()).isEqualTo(next.runId());
    }

    @Test
    void given_pending_question_without_anchor_when_inquiry_then_prj_024_restart_edge() {
        // 丢锚重启边角：挂起仍在（状态库 ASKING）但本进程没有挂起事实（run 无表丢
        // runId，无法代答）——同步 409 指路作答，好过异步错误气泡；拒绝即零提交零事件
        Long projectId = persistedProject("9707");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(true);

        assertThatThrownBy(() -> appService.answerInquiry(
                projectRepository.findById(projectId).orElseThrow(), "现在进展如何？"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.QUESTION_PENDING.message());
        verify(agentClient, never()).converse(any(), any());
        verify(agentClient, never()).resume(any(), any());
        verify(sessionExecutor, never()).submit(any(), any());
    }

    // ---------- 链必达收口（#43：主智能体无派发权，平台意见轮收口观测自动派修正） ----------

    @Test
    void given_generated_project_when_opinion_turn_closes_without_dispatch_tool_then_fix_run_dispatched() {
        // 灵魂用例（#43 缺陷的行为化验证）：脚本化智能体边界——模型只收口（哪怕
        // 只存了 PRD、不调任何派发工具，主智能体也没有派发工具），平台在轮收口时
        // 自动派修正 run：意见原文为任务，coder 会话 + 执行体配置
        Long projectId = persistedGeneratedProject("9720");
        givenSessionExecutorRunsInline();
        givenConverseMainRepliesAndExecutorFinishes("好的，会把主色调改成绿色");

        appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        AgentCommand fix = command.getAllValues().get(1);
        assertThat(fix.sessionId()).isEqualTo("coder-" + projectId);
        assertThat(fix.systemPrompt()).isEqualTo(AgentProfile.EXECUTOR.systemPrompt());
        assertThat(fix.prompt()).isEqualTo(IterationAppService.fixRunPrompt(
                handoff("把系统的主色调改成绿色", null)));
        assertThat(fix.agentKey()).isEqualTo("executor"); // run-start 携配置键（引擎信息归一）
    }

    // ---------- 受理动作卡（#87：受理轮过程呈现——迭代期意见轮开场受理事件） ----------

    @Test
    void given_scripted_acceptance_round_when_opinion_then_acceptance_event_then_fix_run() {
        // 灵魂用例（#87 验收①）：意见 → 受理事件（受理动作卡呈现源）→ 主智能体受理
        // （改 PRD——savePrd 事实）→ 收口自动派更新 run（衔接工作消息）。受理事件
        // 锚定本轮 runId、恰一次，且先于受理动作本身发出（意见已接住的动作卡先出，
        // 解说随后——对话区连续可见的呈现序）
        Long projectId = persistedGeneratedProject("9760");
        givenSessionExecutorRunsInline();
        givenConverseMainSavesPrdAndExecutorFinishes("主色调约定改为绿");

        MainAgentAppService.MainAgentRun run = appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");

        InOrder order = inOrder(eventsAppService, agentClient);
        order.verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.ACCEPTANCE_START),
                argThat(payload -> projectId.toString().equals(payload.get(EventsAppService.PROJECT_FIELD))
                        && run.runId().equals(payload.get(EventsAppService.RUN_FIELD))));
        order.verify(agentClient, times(2)).converse(any(), any());
        // 衔接：受理轮收口自动派更新 run（executor 配置键 = 工作消息的呈现锚）
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).agentKey()).isEqualTo("executor");
    }

    @Test
    void given_scripted_acceptance_with_question_when_answer_settles_then_acceptance_event_once_and_fix_run() {
        // 灵魂用例（#87 验收①追问分岔）：意见 → 受理事件 → 追问挂起（需求不清则
        // 追问——挂起期间不派）→ 答复续跑收口（改 PRD 落定）→ 派更新 run。挂起-
        // 续跑是同一受理轮的过程：受理事件全程恰一次、锚定意见轮 runId（续跑不重发卡）
        Long projectId = persistedGeneratedProject("9761");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) { // 意见轮：以追问挂起
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent(AgentEventTypes.QUESTION_RAISED,
                        new java.util.LinkedHashMap<>(Map.of(
                                "runId", command.runId(),
                                AgentEventTypes.WAIT_SUMMARY_FIELD, "主色调想要哪种绿？"))));
                return new AgentReply(command.runId(), "先问一下");
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation -> {
            AgentResume resume = invocation.getArgument(0);
            prdRevisions.record(resume.workspaceId(), "主色调约定改为绿"); // 答复后落 PRD 修订
            return new AgentReply(resume.runId(), "已按答复修订 PRD");
        });
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 提交守卫：放行本轮
                .thenReturn(true)   // 意见轮收口观测：挂起在即 → 不派
                .thenReturn(false); // 答复续跑收口观测：无挂起 → 派
        when(agentClient.hasAskingToolCall(any(), argThat(s -> s != null && !s.startsWith("main-"))))
                .thenReturn(false); // 其余会话（防御面默认值，不影响脚本序）

        MainAgentAppService.MainAgentRun run = appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");
        appService.answerQuestion(projectId, run.runId(), "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "要薄荷绿");

        verify(eventsAppService, times(1)).publishAgentEvent(eq(AgentEventTypes.ACCEPTANCE_START),
                argThat(payload -> projectId.toString().equals(payload.get(EventsAppService.PROJECT_FIELD))
                        && run.runId().equals(payload.get(EventsAppService.RUN_FIELD))));
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).agentKey()).isEqualTo("executor");
    }

    @Test
    void given_not_generated_project_when_opinion_turn_then_no_acceptance_event() {
        // 场景矩阵（#87 验收②）：纯追问轮（访谈期意见轮——受理对象尚不存在，轮的
        // 形态是每轮一问的追问梳理）不出受理动作卡
        Long projectId = persistedProject("9762");
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "把主色调改成绿色");

        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.ACCEPTANCE_START), anyMap());
    }

    @Test
    void given_inquiry_when_answered_then_no_acceptance_event() {
        // 场景矩阵（#87 验收②）：咨询轮（零产物短路——同会话直答）不出受理动作卡
        Long projectId = persistedGeneratedProject("9763");
        givenSessionExecutorRunsInline();

        appService.answerInquiry(projectRepository.findById(projectId).orElseThrow(),
                "系统的访问地址是什么？");

        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.ACCEPTANCE_START), anyMap());
    }

    // ---------- 交接物补齐（#52：需求侧判定结果入修正 run） ----------

    @Test
    void given_main_saves_prd_with_summary_when_turn_closes_then_handoff_carries_summary_and_prd_path() {
        // 灵魂用例（#41 Testing Decisions / #52）：脚本化主智能体流调
        // savePrd(content, summary="…") → 修正 run prompt（交接物）含 summary
        // 文本与 PRD 路径引用（不注全文）
        Long projectId = persistedGeneratedProject("9740");
        givenSessionExecutorRunsInline();
        givenConverseMainSavesPrdAndExecutorFinishes("按意见把主色调相关约定从蓝改为绿（覆盖本条意见）");

        appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .contains("PRD 已修订")
                .contains("按意见把主色调相关约定从蓝改为绿（覆盖本条意见）")
                .contains(ProjectArtifacts.PRD);
    }

    @Test
    void given_main_without_save_prd_when_turn_closes_then_handoff_states_prd_not_revised() {
        // 主智能体流不调 savePrd：交接物如实含「本轮无修订」占位口径，修正 run 照派
        Long projectId = persistedGeneratedProject("9741");
        givenSessionExecutorRunsInline();
        givenConverseMainRepliesAndExecutorFinishes("好的，会处理的");

        appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .contains("本轮无修订")
                .contains("把系统的主色调改成绿色")
                .doesNotContain("PRD 已修订");
    }

    @Test
    void given_queued_rounds_with_and_without_revision_when_merged_then_prompt_pairs_per_round() {
        // 灵魂用例（#55 story 14 收严，边界脚本化各轮「改/不改」）：修正 run
        // 在途时两条意见先后收口排队——轮2 的主智能体改了 PRD（savePrd 落事实）、
        // 轮3 未改（无事实）→ 合并续派 run 的 prompt 逐轮配对：意见二↔轮2 修订说明、
        // 意见三↔「本轮无修订」显式占位——配对由平台拼装锚定，不靠意见清单与
        // 说明清单的位置对齐（某轮零修订不再让后续意见错认说明）
        Long projectId = persistedGeneratedProject("9744");
        List<Runnable> coderTracks = CollUtil.newArrayList();
        doAnswer(invocation -> {
            Runnable task = (Runnable) invocation.getArgument(1);
            if (((String) invocation.getArgument(0)).startsWith("coder-")) {
                coderTracks.add(task); // 修正轨道挂起不跑（模拟首场在途）
                return null;
            }
            task.run(); // 主智能体轮直通
            return null;
        }).when(sessionExecutor).submit(any(), any());
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                // 脚本化判定动作：意见二的轮调 savePrd（登记修订事实），其余轮不调
                if ("意见二：改蓝色".equals(command.prompt())) {
                    prdRevisions.record(command.workspaceId(), "B轮修订：主色调约定改为蓝");
                }
                return new AgentReply(command.runId(), "好的");
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });

        appService.runOpinionTurn(projectId, "意见一：加导出");
        appService.runOpinionTurn(projectId, "意见二：改蓝色");
        appService.runOpinionTurn(projectId, "意见三：加导出格式");
        coderTracks.remove(0).run(); // 首场收口 → 合并排队两轮续派

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(5)).converse(command.capture(), any());
        // 首场（意见一，未修订）：占位配对
        assertThat(command.getAllValues().get(3).prompt())
                .contains("1. 意见原文：意见一：加导出"
                        + "\n   需求侧判定（主智能体已收口）：本轮无修订（未触发 PRD 变更）");
        // 合并续派场：意见二↔其修订说明、意见三↔占位，逐轮一一对应
        assertThat(command.getAllValues().get(4).prompt())
                .contains("1. 意见原文：意见二：改蓝色"
                        + "\n   需求侧判定（主智能体已收口）：PRD 已修订——B轮修订：主色调约定改为蓝")
                .contains("2. 意见原文：意见三：加导出格式"
                        + "\n   需求侧判定（主智能体已收口）：本轮无修订（未触发 PRD 变更）");
    }

    @Test
    void given_multiple_save_prd_in_one_turn_when_consumed_then_final_summary_only() {
        // 一轮多次 savePrd：交接物取终值不混杂（终版说明进 prompt，被覆盖的旧说明不进）
        Long projectId = persistedGeneratedProject("9742");
        givenSessionExecutorRunsInline();
        givenConverseMainSavesPrdAndExecutorFinishes("第一次修订说明（将被覆盖）",
                "终版修订说明：主色调章节改为绿");

        appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        String prompt = command.getAllValues().get(1).prompt();
        assertThat(prompt).contains("终版修订说明：主色调章节改为绿");
        assertThat(prompt).doesNotContain("第一次修订说明");
    }

    @Test
    void given_stale_revision_fact_from_aborted_turn_when_next_turn_settles_then_not_carried() {
        // 本轮判定从零起算：上一轮主智能体调了 savePrd 但轮炸（收口不跑、事实滞留）
        // → 下一轮不带残留（任务首行清残——滞留的修订事实不冒充本轮「已修订」）。
        // 单一脚本分轮：首轮 record 后抛，次轮正常回复（Mockito 重打桩会以 null
        // 参调旧 answer，故不分设）
        Long projectId = persistedGeneratedProject("9743");
        givenSessionExecutorSwallowsFailures();
        AtomicBoolean firstMainTurn = new AtomicBoolean(true);
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                if (firstMainTurn.getAndSet(false)) {
                    prdRevisions.record(command.workspaceId(), "上一轮的修订说明");
                    throw new IllegalStateException("对话轮失败");
                }
                return new AgentReply(command.runId(), "好的");
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });

        appService.runOpinionTurn(projectId, "上一条意见（本轮将炸）");
        appService.runOpinionTurn(projectId, "这一条意见不用改 PRD");

        // 首轮炸不派（既有口径）；次轮收口派修正：prompt 如实「本轮无修订」无残留
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(3)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(2).prompt())
                .contains("本轮无修订")
                .doesNotContain("上一轮的修订说明");
    }

    @Test
    void given_not_generated_project_when_opinion_turn_closes_then_stops_at_conversation() {
        // 守卫沿用：未生成止于对话（访谈期收口是常态路径，静默不派——不是异常）
        Long projectId = persistedProject("9721");
        givenSessionExecutorRunsInline();

        appService.runOpinionTurn(projectId, "把主色调改成绿色");

        verify(agentClient, times(1)).converse(any(), any());
    }

    @Test
    void given_pending_question_after_turn_when_close_then_no_dispatch_until_answer_settles() {
        // 追问挂起 = 本轮未收口：意见不派；答复续跑再挂起（多轮追问）也不派；
        // 最终收口后派——交接任务锚定意见原文 + 全部追问答复（逐条累积）
        Long projectId = persistedGeneratedProject("9722");
        givenSessionExecutorRunsInline();
        givenConverseMainRepliesAndExecutorFinishes("先问一下");
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)  // 提交守卫：无挂起（放行本轮）
                .thenReturn(true)   // 本轮收口观测：挂起在即 → 不派
                .thenReturn(true)   // 答复①续跑收口观测：再挂起 → 不派
                .thenReturn(false); // 答复②续跑收口观测：无挂起 → 派

        appService.runOpinionTurn(projectId, "把系统的主色调改成绿色");
        verify(agentClient, times(1)).converse(any(), any());

        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "要薄荷绿");
        appService.answerQuestion(projectId, "run-q", "reply-2",
                List.of(Map.of("id", "tc-2", "name", "ask_user")), "偏冷一点的");

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(handoff(
                        "把系统的主色调改成绿色；用户对追问的答复：要薄荷绿"
                                + "；用户对追问的答复：偏冷一点的", null)));
    }

    @Test
    void given_fix_in_flight_when_next_opinion_closes_then_queued_and_merged() {
        // 守卫沿用：在途 run 排队下一轮合并（意见收口路径的连续两条意见不丢、
        // 不逐条烧 run）
        Long projectId = persistedGeneratedProject("9723");
        List<Runnable> tracks = CollUtil.newArrayList();
        doAnswer(invocation -> {
            Runnable task = (Runnable) invocation.getArgument(1);
            if (("coder-" + projectId).equals(invocation.getArgument(0))) {
                tracks.add(task); // 修正轨道挂起不跑（模拟在途）
                return null;
            }
            task.run(); // 主智能体轨道直通
            return null;
        }).when(sessionExecutor).submit(any(), any());
        givenConverseMainRepliesAndExecutorFinishes("好的");

        appService.runOpinionTurn(projectId, "意见一：加导出");
        appService.runOpinionTurn(projectId, "意见二：改蓝色");
        // 两条意见轮已跑、修正只起了一条轨道（第二条意见未即派新 run）
        verify(agentClient, times(2)).converse(any(), any());

        tracks.remove(0).run(); // 修正轨道起跑：第一条跑完即合并第二条续派

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(2).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(handoff("意见一：加导出", null)));
        assertThat(command.getAllValues().get(3).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(handoff("意见二：改蓝色", null)));
    }

    // ---------- 意见轮在途意见排队成轮（#54：锚随任务落 + 失败清锚） ----------

    @Test
    void given_second_opinion_during_turn_in_flight_when_turns_close_then_each_dispatches_own_opinion() {
        // 灵魂用例（#54 缺陷的行为化验证）：意见轮在途（REST 已返、收口未至）窗口
        // 连发两条意见——各自成轮、各自收口派发，交接物各含自己的意见（现状：
        // REST 线程 put 覆盖单槽锚，轮1 交接物只剩意见二、轮2 锚空防御不派——
        // 意见一零丢失承诺被破）；轮2 派发撞在途修正 run → queued（#53 合并续派）
        Long projectId = persistedGeneratedProject("9725");
        List<Runnable> mainTracks = CollUtil.newArrayList();
        List<Runnable> coderTracks = CollUtil.newArrayList();
        doAnswer(invocation -> {
            Runnable task = (Runnable) invocation.getArgument(1);
            if (((String) invocation.getArgument(0)).startsWith("main-")) {
                mainTracks.add(task); // 主智能体轨道挂起不跑（模拟轮1 在途窗口）
            } else {
                coderTracks.add(task); // 修正轨道挂起不跑（模拟在途）
            }
            return null;
        }).when(sessionExecutor).submit(any(), any());
        givenConverseMainRepliesAndExecutorFinishes("好的");

        appService.runOpinionTurn(projectId, "意见一：加导出");
        appService.runOpinionTurn(projectId, "意见二：改蓝色"); // 轮1 在途窗口再发
        assertThat(mainTracks).hasSize(2);

        mainTracks.remove(0).run(); // 轮1 收口：派发交接物含意见一
        mainTracks.remove(0).run(); // 轮2 收口：第二次派发、含意见二 → 撞在途修正 → 排队

        coderTracks.remove(0).run(); // 修正轨道起跑：第一场收口即合并排队的意见二续派
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(4)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(2).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(handoff("意见一：加导出", null)));
        assertThat(command.getAllValues().get(3).prompt())
                .isEqualTo(IterationAppService.fixRunPrompt(handoff("意见二：改蓝色", null)));
    }

    @Test
    void given_converse_failure_when_turn_then_anchor_cleared_no_stale_opinion_consumed() {
        // 失败即清锚（#54，对齐「收口即消费」）：converse 炸 → 锚即清——后续收口
        // （此处脚本化：炸轮后经问答答复续跑收口）不消费到滞留的旧意见；重提即
        // 兜底，不自动重试；error 事件语义保持（converse 内已发）
        Long projectId = persistedGeneratedProject("9726");
        givenSessionExecutorSwallowsFailures();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                throw new IllegalStateException("对话轮失败"); // 首个（唯一）对话轮即炸
            }
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成");
        });

        appService.runOpinionTurn(projectId, "意见（本轮将炸）");
        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "对追问的答复");

        // 答复续跑收口派修正：交接物意见腿只含答复（自立），不含炸轮滞留的旧意见
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .contains("对追问的答复")
                .doesNotContain("意见（本轮将炸）");
    }

    @Test
    void given_turn_error_when_close_then_no_dispatch() {
        // 对话轮失败不派修正（意见未被处理；error 事件已表达，用户重提即兜底）——
        // 内联执行器同生产语义吞掉轨道异常
        Long projectId = persistedGeneratedProject("9724");
        givenSessionExecutorSwallowsFailures();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("对话轮失败"));

        appService.runOpinionTurn(projectId, "把主色调改成绿色");

        verify(agentClient, times(1)).converse(any(), any());
    }

    @Test
    void given_question_raised_when_answer_settles_then_dispatch_waits_and_carries_reply() {
        // 挂起边界（行为核）：追问挂起期间收口不派修正（链停在等答复），答复续跑
        // 收口后派发——交接物意见腿含原意见与追问答复
        Long projectId = persistedGeneratedProject("9732");
        givenSessionExecutorRunsInline();
        // 主智能体轮流上挂起事件（question-raised，#83 拆分后无 kind 键）；修正 run
        // 收口脚本沿用（changed=true）
        doAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent(AgentEventTypes.QUESTION_RAISED,
                        new java.util.LinkedHashMap<>(Map.of(
                                "runId", command.runId(),
                                AgentEventTypes.WAIT_SUMMARY_FIELD, "主色调想要哪种绿？"))));
                return new AgentReply(command.runId(), "先问一下");
            }
            finishFixFacts.record(command.workspaceId(), true, "已修正");
            return new AgentReply(command.runId(), "修正完成");
        }).when(agentClient).converse(any(), any());
        // 提交守卫放行 → 本轮收口观测见挂起（不派）→ 答复续跑收口观测无挂起（派）
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)
                .thenReturn(true)
                .thenReturn(false);

        appService.runOpinionTurn(projectId, "把主色调改成绿色");
        verify(agentClient, times(1)).converse(any(), any()); // 挂起期间无修正 run

        appService.answerQuestion(projectId, "run-q", "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user")), "要薄荷绿");
        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(agentClient, times(2)).converse(command.capture(), any());
        assertThat(command.getAllValues().get(1).prompt())
                .contains("把主色调改成绿色")
                .contains("要薄荷绿"); // 答复并入交接物随派发
    }

    @Test
    void given_start_fix_run_failure_when_close_then_track_survives_no_coder_run() {
        // #51 派发失败（行为核）：收口派修正 run 炸——意见锚已消费、不自动重试，
        // 用户重提即兜底；对话轨道不被炸穿（会话执行器吞掉），修正 run 未起跑
        Long projectId = persistedGeneratedProject("9735");
        givenConverseMainRepliesAndExecutorFinishes("好的，会处理的");
        doAnswer(invocation -> {
            String sessionId = invocation.getArgument(0);
            Runnable task = invocation.getArgument(1);
            if (sessionId.startsWith("coder-")) {
                throw new IllegalStateException("修正轨道提交失败");
            }
            task.run(); // 主智能体轨道直通
            return null;
        }).when(sessionExecutor).submit(any(), any());

        appService.runOpinionTurn(projectId, "把主色调改成绿色");

        verify(agentClient, times(1)).converse(any(), any()); // 无 coder converse
        verify(eventsAppService, never()).publishAgentEvent(
                eq(AgentEventTypes.RUN_FAILED), anyMap()); // 派发失败不是 run 终态
        // 失败家族归位（#82）：dispatch-failed 阶段族退役后，失败信号归 error
        // 事件——锚定收口轮、如实呈现重提（不静默）
        verify(eventsAppService).publishAgentEvent(eq(AgentEventTypes.ERROR), argThat(payload ->
                projectId.toString().equals(payload.get(EventsAppService.PROJECT_FIELD))
                        && "意见派发失败，请重新发送".equals(
                                payload.get(AgentEventTypes.ERROR_MESSAGE_FIELD))));
    }

    // ---------- 测试数据 ----------

    /** 单轮交接物（一次派发 = 一轮：一条意见 + 该轮判定）。 */
    private static IterationAppService.FixHandoff handoff(String opinion, String summary) {
        return new IterationAppService.FixHandoff(
                List.of(new IterationAppService.FixHandoff.Round(opinion, summary)));
    }

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
