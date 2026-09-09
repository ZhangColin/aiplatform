package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import org.assertj.core.api.InstanceOfAssertFactories;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentResume;
import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentSuspension;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.FileChange;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment.AnnotationAnchor;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment.AnnotationBody;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnnotationAttachment.AnnotationRegion;
import com.aieducenter.aiplatform.business.project.application.dto.response.ConversationEntryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 对话史落库（#89 对话史落库④，spec ④）：写口唯一（{@code ConversationHistoryAppService}）
 * + 读口水合序。灵魂场景 = 脚本化多轮（开场追问挂起 → 答复续跑 → 收口自动派修正
 * run 并真收口）后，对话史按写入序完整可回放：用户发言 → 智能体挂起段 → 问答卡
 * （载荷原样 + 作答置位）→ 问答作答 → 续跑收口段 → 收尾卡（#88 同载荷）。用户
 * 发言与作答在提交侧同步落（轮未跑也已在库）；失败轮只留用户发言（智能体回复
 * 不落——重提即兜底）；过程明细（解说段/动作卡流水）不在对话史。
 */
@IntegrationTest
class ConversationHistoryTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private MainAgentAppService mainAgentAppService;

    @Autowired
    private ConversationHistoryAppService conversationHistory;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FinishEditFacts finishFixFacts;

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
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @BeforeEach
    void defaultAgentReplies() {
        // 默认回复形状兜底（个别用例自带脚本后打桩覆盖之——同 MainAgentAppServiceTest 先例）
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            return command != null ? new AgentReply(command.runId(), "好的") : null;
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation ->
                new AgentReply("r", "好的"));
        when(agentClient.hasAskingToolCall(anyString(), anyString())).thenReturn(false);
    }

    private void givenSessionExecutorRunsInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(sessionExecutor).submit(any(), any());
    }

    private Long persistedGeneratedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("对话史项目", null,
                Long.parseLong(workspaceId), OWNER));
        project.markPrdProduced();
        project.markGenerated();
        return projectRepository.save(project).getId();
    }

    @Test
    void given_scripted_rounds_when_read_then_full_conversation_in_write_order() {
        // 灵魂用例（#89 验收①的写读口行为核）：开场意见轮以追问挂起（挂起段 +
        // 问答卡落库）→ 答复续跑收口（作答 + 置位 + 收口段）→ 收口自动派修正 run
        // 真收口（收尾卡 #88 同载荷）→ 对话史读口按写入序完整回放、顺序正确
        Long projectId = persistedGeneratedProject("9800");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) { // 意见轮：以追问挂起
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent(AgentEventTypes.QUESTION_RAISED,
                        new LinkedHashMap<>(Map.of(
                                AgentEventTypes.RUN_FIELD, command.runId(),
                                AgentEventTypes.SESSION_FIELD, command.sessionId(),
                                AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-1",
                                AgentEventTypes.WAIT_SUMMARY_FIELD, "目标用户是谁？",
                                AgentEventTypes.WAIT_DATA_FIELD, Map.of(
                                        "toolCalls", List.of(Map.of(
                                                "id", "tc-1", "name", "ask_user",
                                                "input", Map.of("question", "目标用户是谁？"))),
                                        "questions", List.of(Map.of(
                                                "header", "提问", "question", "目标用户是谁？",
                                                "multiple", false, "custom", true, "options", List.of())))))));
                return new AgentReply(command.runId(), "收到，先确认一个关键点",
                        new AgentSuspension("reply-1", true, List.of(Map.of(
                                "id", "tc-1", "name", "ask_user"))));
            }
            // 收口自动派的修正 run：run-finish 事件 + finish_edit 事实 + 文件变更
            // 观察 → 真收口出收尾卡（被押后的 run-finish 携 closing 释放）
            Consumer<AgentEvent> coderSink = invocation.getArgument(1);
            coderSink.accept(new AgentEvent(AgentEventTypes.RUN_FINISH, Map.of(
                    AgentEventTypes.RUN_FIELD, command.runId(),
                    AgentEventTypes.FINISH_FIELD, "end")));
            finishFixFacts.record(command.workspaceId(), true, "已按意见修正");
            return new AgentReply(command.runId(), "修正完成", null, List.of(
                    new FileChange("/src/App.jsx", 40, 0)));
        });
        when(agentClient.resume(any(), any())).thenAnswer(invocation ->
                new AgentReply(((AgentResume) invocation.getArgument(0)).runId(), "好的，按企业客户梳理"));
        // 挂起事实脚本：提交守卫放行 → 意见轮收口观测见挂起（不派）→ 答复续跑
        // 收口观测无挂起（派修正）——挂起期间不派，收尾卡只在真收口后
        when(agentClient.hasAskingToolCall(Long.toString(OWNER), "main-" + projectId))
                .thenReturn(false)
                .thenReturn(true)
                .thenReturn(false);

        MainAgentAppService.MainAgentRun turn =
                mainAgentAppService.runOpinionTurn(projectId, "给宠物医院做预约系统");
        mainAgentAppService.answerQuestion(projectId, turn.runId(), "reply-1",
                List.of(Map.of("id", "tc-1", "name", "ask_user",
                        "input", Map.of("question", "目标用户是谁？"))), "企业客户");

        List<ConversationEntryResponse> history = conversationHistory.read(projectId);
        assertThat(history).extracting(ConversationEntryResponse::kind)
                .containsExactly(1, 2, 3, 4, 2, 5); // user/agent/question/answer/agent/closing
        // 用户发言（提交侧同步落，runId = 对话轮锚）
        assertThat(history.get(0).text()).isEqualTo("给宠物医院做预约系统");
        assertThat(history.get(0).runId()).isNotBlank();
        // 挂起段先落、答复与续跑段随后（交错成完整叙事——顺序正确）
        assertThat(history.get(1).text()).isEqualTo("收到，先确认一个关键点");
        // 问答卡：载荷原样（engineRef/data 投影），作答即置位 answered
        assertThat(history.get(2).runId()).isEqualTo(history.get(0).runId());
        assertThat(history.get(2).answered()).isTrue();
        assertThat(history.get(2).question())
                .containsEntry(AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-1")
                .containsEntry(AgentEventTypes.WAIT_SUMMARY_FIELD, "目标用户是谁？");
        assertThat(history.get(2).question().get(AgentEventTypes.WAIT_DATA_FIELD))
                .isInstanceOf(Map.class);
        assertThat(history.get(3).text()).isEqualTo("企业客户");
        assertThat(history.get(3).kind()).isEqualTo(4); // answer
        assertThat(history.get(4).text()).isEqualTo("好的，按企业客户梳理");
        // 收尾卡：#88 收口扩载同载荷（判定行 + 变更清单 + 轮末统计），runId = 用户面 run
        assertThat(history.get(5).question()).isNull();
        assertThat(history.get(5).closing())
                .containsEntry("summary", "更新了系统")
                .containsEntry("systemChanged", true)
                .containsEntry("prdChanged", false)
                .containsKey("files")
                .containsKey("durationMs");
        assertThat(history.get(5).closing().get("files")).asInstanceOf(
                        InstanceOfAssertFactories.LIST)
                .containsExactly(Map.of("path", "/src/App.jsx", "added", 40, "removed", 0));
    }

    @Test
    void given_utterance_with_annotations_when_read_then_attachments_round_trip() {
        // #97 圈注落库：用户发言随带的圈注附件落 JSONB、读口按附件数组回放——
        // 刷新/回访后消息回显可重建圈注 chip（结构化定位，非截图）
        Long projectId = persistedGeneratedProject("9802");
        AnnotationAttachment select = new AnnotationAttachment("annotation",
                new AnnotationBody("select",
                        new AnnotationAnchor("button.submit", "提交订单", null), ""));
        AnnotationAttachment circle = new AnnotationAttachment("annotation",
                new AnnotationBody("circle",
                        new AnnotationAnchor(null, null, new AnnotationRegion(120, 340, 300, 80)),
                        "改这里"));

        conversationHistory.recordUserUtterance(projectId, "run-anno", "把这里改成红色",
                List.of(select, circle));

        List<ConversationEntryResponse> history = conversationHistory.read(projectId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).attachments()).hasSize(2);
        assertThat(history.get(0).attachments().get(0)).containsEntry("attachmentType", "annotation");
        assertThat(history.get(0).attachments().get(1).get("annotation"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("kind", "circle")
                .containsEntry("note", "改这里");
    }

    @Test
    void given_queued_turn_when_not_run_yet_then_user_utterance_already_persisted() {
        // 提交侧同步落库：守卫全过后、异步轮提交前即写——轮还没跑（轨道挂起），
        // 用户的话已在库（「落库 ⟺ 说过」；刷新不丢已发送的话）
        Long projectId = persistedGeneratedProject("9801");
        doAnswer(invocation -> null).when(sessionExecutor).submit(any(), any()); // 轨道挂起不跑

        mainAgentAppService.runOpinionTurn(projectId, "把主色调改成绿色");

        List<ConversationEntryResponse> history = conversationHistory.read(projectId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).kind()).isEqualTo(1); // user
        assertThat(history.get(0).text()).isEqualTo("把主色调改成绿色");
    }

    @Test
    void given_pending_question_when_read_then_answerable_card_payload_intact() {
        // 挂起问答卡的刷新重建面（#89：重放缓冲降级后问答卡可重建可作答的唯一来源）：
        // 未答问答卡 answered=false + 载荷原样（前端问答卡回传面 data.toolCalls 完整）
        Long projectId = persistedGeneratedProject("9802");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            Consumer<AgentEvent> sink = invocation.getArgument(1);
            sink.accept(new AgentEvent(AgentEventTypes.QUESTION_RAISED,
                    new LinkedHashMap<>(Map.of(
                            AgentEventTypes.RUN_FIELD, command.runId(),
                            AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-2",
                            AgentEventTypes.WAIT_SUMMARY_FIELD, "要几分账？",
                            AgentEventTypes.WAIT_DATA_FIELD, Map.of(
                                    "toolCalls", List.of(Map.of("id", "tc-9", "name", "ask_user")),
                                    "questions", List.of(Map.of("question", "要几分账？")))))));
            return new AgentReply(command.runId(), "先问一下",
                    new AgentSuspension("reply-2", true, List.of(Map.of(
                            "id", "tc-9", "name", "ask_user"))));
        });

        mainAgentAppService.runOpinionTurn(projectId, "做一个分销系统");

        List<ConversationEntryResponse> history = conversationHistory.read(projectId);
        assertThat(history).extracting(ConversationEntryResponse::kind)
                .containsExactly(1, 2, 3); // user/agent/question
        ConversationEntryResponse card = history.get(2);
        assertThat(card.answered()).isFalse(); // 未答 = 挂起待答（水合后问答卡可作答）
        Map<?, ?> data = (Map<?, ?>) card.question().get(AgentEventTypes.WAIT_DATA_FIELD);
        assertThat(data.get("toolCalls")).asInstanceOf(
                        InstanceOfAssertFactories.LIST)
                .hasSize(1);
    }

    @Test
    void given_failed_turn_when_read_then_user_utterance_only_no_agent_reply() {
        // 失败轮：用户发言已在库、智能体回复不落（重提即兜底）；错误提示是实时呈现
        // 不入对话史
        Long projectId = persistedGeneratedProject("9803");
        doAnswer(invocation -> {
            try {
                ((Runnable) invocation.getArgument(1)).run();
            }
            catch (RuntimeException ignored) {
                // 生产执行器吞掉记日志（异步轨道），失败表达归 error 事件
            }
            return null;
        }).when(sessionExecutor).submit(any(), any());
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("对话轮失败"));

        mainAgentAppService.runOpinionTurn(projectId, "意见（本轮将炸）");

        List<ConversationEntryResponse> history = conversationHistory.read(projectId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).kind()).isEqualTo(1); // user
    }

    @Test
    void given_missing_project_when_read_then_prj_001() {
        assertThatThrownBy(() -> conversationHistory.read(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_no_narration_segments_when_read_then_no_process_details() {
        // 过程明细不落（验收④）：解说段/动作卡流水是 SSE 部件事件面（part-*），对话史
        // 只落对话面——普通意见轮收口后恰两条（发言 + 回复），无任何部件流水行
        Long projectId = persistedGeneratedProject("9804");
        givenSessionExecutorRunsInline();
        when(agentClient.converse(any(), any())).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            if (command.sessionId().startsWith("main-")) {
                // 脚本化过程部件（解说段/动作卡）随事件桥流出——但不应进对话史
                Consumer<AgentEvent> sink = invocation.getArgument(1);
                sink.accept(new AgentEvent(AgentEventTypes.PART_TEXT, Map.of(
                        AgentEventTypes.RUN_FIELD, command.runId(), "text", "解说段")));
                sink.accept(new AgentEvent(AgentEventTypes.PART_ACTION, Map.of(
                        AgentEventTypes.RUN_FIELD, command.runId(), "state", "running")));
                return new AgentReply(command.runId(), "好的");
            }
            finishFixFacts.record(command.workspaceId(), true, "已修正");
            Consumer<AgentEvent> coderSink = invocation.getArgument(1);
            coderSink.accept(new AgentEvent(AgentEventTypes.RUN_FINISH, Map.of(
                    AgentEventTypes.RUN_FIELD, command.runId(),
                    AgentEventTypes.FINISH_FIELD, "end")));
            return new AgentReply(command.runId(), "修正完成");
        });

        mainAgentAppService.runOpinionTurn(projectId, "加个导出按钮");

        assertThat(conversationHistory.read(projectId))
                .extracting(ConversationEntryResponse::kind)
                .containsExactly(1, 2, 5); // user/agent/closing（过程明细不落）
    }
}
