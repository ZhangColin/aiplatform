package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentscopeEventMapper} 单点映射表（#45 事件桥正本）：AgentScope 事件 →
 * 平台事件。每类映射断言 type + payload 形状（对齐 SSE事件清单·智能体事件族
 * 引擎透传口径：runId/sessionId/engine/data）。
 */
class AgentscopeEventMapperTest {

    private static final String RUN_ID = "r1";
    private static final String SESSION_ID = "s1";
    private static final String ENGINE = "agentscope";

    private final AgentscopeEventMapper mapper = new AgentscopeEventMapper(RUN_ID, SESSION_ID, ENGINE);

    @Nested
    class ProcessEvents {

        @Test
        void given_text_delta_when_map_then_text_frame_with_delta_payload() {
            AgentEvent frame = mapper.map(new TextBlockDeltaEvent("reply-1", "b-1", "你好"));

            assertThat(frame).isNotNull();
            assertThat(frame.type()).isEqualTo("text");
            assertThat(frame.payload()).containsAllEntriesOf(Map.of(
                    "runId", RUN_ID, "sessionId", SESSION_ID, "engine", ENGINE));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload().get("data");
            assertThat(data).containsOnly(
                    Map.entry("delta", "你好"), Map.entry("blockId", "b-1"));
        }

        @Test
        void given_thinking_delta_when_map_then_reasoning_frame() {
            AgentEvent frame = mapper.map(new ThinkingBlockDeltaEvent("reply-1", "b-2", "想一想"));

            assertThat(frame.type()).isEqualTo("reasoning");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload().get("data");
            assertThat(data).containsEntry("delta", "想一想");
        }

        @Test
        void given_tool_call_start_and_end_when_map_then_tool_frames_with_phase() {
            AgentEvent start = mapper.map(new ToolCallStartEvent("reply-1", "tc-1", "write_file"));
            AgentEvent end = mapper.map(new ToolCallEndEvent("reply-1", "tc-1", "write_file"));

            assertThat(start.type()).isEqualTo("tool");
            assertThat(end.type()).isEqualTo("tool");
            @SuppressWarnings("unchecked")
            Map<String, Object> startData = (Map<String, Object>) start.payload().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> endData = (Map<String, Object>) end.payload().get("data");
            assertThat(startData).containsOnly(
                    Map.entry("toolCallId", "tc-1"),
                    Map.entry("toolName", "write_file"),
                    Map.entry("phase", "start"));
            assertThat(endData).containsOnly(
                    Map.entry("toolCallId", "tc-1"),
                    Map.entry("toolName", "write_file"),
                    Map.entry("phase", "end"));
        }

        @Test
        void given_model_call_boundaries_when_map_then_step_frames() {
            AgentEvent start = mapper.map(new ModelCallStartEvent("reply-1"));
            AgentEvent end = mapper.map(new ModelCallEndEvent("reply-1", null));

            assertThat(start.type()).isEqualTo("step-start");
            assertThat(end.type()).isEqualTo("step-finish");
            @SuppressWarnings("unchecked")
            Map<String, Object> endData = (Map<String, Object>) end.payload().get("data");
            assertThat(endData).containsKey("replyId");
        }

        @Test
        void given_unmapped_events_when_map_then_no_frame() {
            // 边界/块尾/结果等未映射类型不产事件（扩展点：HITL 类归 #48）
            assertThat(mapper.map(new TextBlockEndEvent("reply-1", "b-1"))).isNull();
            assertThat(mapper.map(new AgentEndEvent("reply-1"))).isNull();
            assertThat(mapper.map(new AgentResultEvent((Msg) null))).isNull();
        }
    }

    @Nested
    class LifecycleFrames {

        @Test
        void given_run_start_when_built_then_carries_prompt_and_model() {
            AgentEvent frame = AgentscopeEventMapper.runStart(RUN_ID, "写个 PRD", "deepseek:m-1",
                    ENGINE, null);

            assertThat(frame.type()).isEqualTo(AgentEventTypes.RUN_START);
            assertThat(frame.payload()).containsOnly(
                    Map.entry("runId", RUN_ID),
                    Map.entry("prompt", "写个 PRD"),
                    Map.entry("model", "deepseek:m-1"),
                    Map.entry("engine", ENGINE));
        }

        /** #77 引擎信息归一：角色键并入 run-start（可空不携带——无角色语境的一次性调用）。 */
        @Test
        void given_run_start_with_role_when_built_then_carries_role_key() {
            AgentEvent frame = AgentscopeEventMapper.runStart(RUN_ID, "做系统", "deepseek:m-1",
                    ENGINE, "CODER");

            assertThat(frame.payload()).containsEntry("role", "CODER");
        }

        @Test
        void given_run_finish_when_built_then_carries_finish_token() {
            AgentEvent frame = AgentscopeEventMapper.runFinish(RUN_ID, SESSION_ID, "end", ENGINE);

            assertThat(frame.type()).isEqualTo(AgentEventTypes.RUN_FINISH);
            assertThat(frame.payload()).containsOnly(
                    Map.entry("runId", RUN_ID),
                    Map.entry("sessionId", SESSION_ID),
                    Map.entry("engine", ENGINE),
                    Map.entry("finish", "end"));
        }

        @Test
        void given_error_when_built_then_carries_message() {
            AgentEvent frame = AgentscopeEventMapper.error(RUN_ID, "模型超时");

            assertThat(frame.type()).isEqualTo(AgentEventTypes.ERROR);
            assertThat(frame.payload()).containsOnly(
                    Map.entry("runId", RUN_ID),
                    Map.entry("message", "模型超时"));
        }
    }

    @Nested
    class FinishToken {

        @Test
        void given_exceed_max_iters_when_finish_token_then_terminal_token() {
            assertThat(mapper.finishToken(new ExceedMaxItersEvent("reply-1", 10, 10)))
                    .contains("exceed_max_iters");
        }

        @Test
        void given_other_events_when_finish_token_then_empty() {
            assertThat(mapper.finishToken(new TextBlockDeltaEvent("r", "b", "d"))).isEmpty();
            assertThat(mapper.finishToken(new AgentEndEvent("reply-1"))).isEmpty();
        }
    }

    @Nested
    class SuspensionFrames {

        @Test
        void given_non_ask_user_confirm_when_suspension_then_permission_required_event() {
            // #83 事件拆分：工具操作确认（非提问）→ 独立 permission-required（确认卡）
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-9", java.util.List.of(
                    toolCall("tc-1", "command", Map.of("command", "rm -rf /workspace/data"))));

            AgentEvent frame = mapper.suspension(event);

            assertThat(frame.type()).isEqualTo(AgentEventTypes.PERMISSION_REQUIRED);
            assertThat(frame.payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.WAIT_SUMMARY_FIELD, "rm -rf /workspace/data",
                    AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-9",
                    "engine", ENGINE));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload()
                    .get(AgentEventTypes.WAIT_DATA_FIELD);
            // 引擎载荷：待确认工具清单（作答复跑侧据此重建 ConfirmResult；恢复入参
            // 由业务编排从项目侧事实重建，不随事件携带）——无 questions 投影
            assertThat(data).containsOnlyKeys("toolCalls");
            assertThat(data.get("toolCalls")).isEqualTo(java.util.List.of(
                    Map.of("id", "tc-1", "name", "command",
                            "input", Map.of("command", "rm -rf /workspace/data"))));
        }

        @Test
        void given_non_command_tool_when_suspension_then_summary_falls_back_to_tool_name() {
            // 非命令工具（无 command 入参）摘要回落工具名
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-13", java.util.List.of(
                    toolCall("tc-1", "write_file", Map.of("path", "docs/PRD.md"))));

            AgentEvent frame = mapper.suspension(event);

            assertThat(frame.type()).isEqualTo(AgentEventTypes.PERMISSION_REQUIRED);
            assertThat(frame.payload()).containsEntry(
                    AgentEventTypes.WAIT_SUMMARY_FIELD, "write_file");
        }

        @Test
        void given_ask_user_tool_when_suspension_then_question_raised_event() {
            // 向用户提问（ask_user）= question-raised（问答卡，问答作答通道）
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-10", java.util.List.of(
                    toolCall("tc-2", "ask_user", Map.of("question", "用哪个框架?"))));

            AgentEvent frame = mapper.suspension(event);

            assertThat(frame.type()).isEqualTo(AgentEventTypes.QUESTION_RAISED);
            // 摘要 = 问题文本（问答口径，非工具名）；kind 已随拆分退役（type 即判别）
            assertThat(frame.payload()).containsEntry(
                    AgentEventTypes.WAIT_SUMMARY_FIELD, "用哪个框架?");
            assertThat(frame.payload()).doesNotContainKey("kind");
        }

        @Test
        void given_ask_user_question_body_when_suspension_then_pending_questions_shape() {
            // #40：QUESTION body 增 questions 投影（前端问答卡契约 header/question/
            // multiple/custom/options[{label}]——custom 必须显式 true，否则无选项题
            // 整题被前端丢弃）；toolCalls 面不动（答复续跑侧仍按它重建）
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-14", java.util.List.of(
                    toolCall("tc-q", "ask_user", Map.of(
                            "header", "目标用户",
                            "question", "这个官网主要面向谁?",
                            "options", java.util.List.of("企业客户", "个人用户")))));

            AgentEvent frame = mapper.suspension(event);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload()
                    .get(AgentEventTypes.WAIT_DATA_FIELD);
            assertThat(data).containsOnlyKeys("toolCalls", "questions");
            assertThat(data.get("questions")).isEqualTo(java.util.List.of(Map.of(
                    "header", "目标用户",
                    "question", "这个官网主要面向谁?",
                    "multiple", false,
                    "custom", true,
                    "options", java.util.List.of(
                            Map.of("label", "企业客户"), Map.of("label", "个人用户")))));
            assertThat(data.get("toolCalls")).isEqualTo(java.util.List.of(Map.of(
                    "id", "tc-q", "name", "ask_user",
                    "input", Map.of("header", "目标用户",
                            "question", "这个官网主要面向谁?",
                            "options", java.util.List.of("企业客户", "个人用户")))));
        }

        @Test
        void given_ask_user_without_header_or_options_when_suspension_then_still_answerable() {
            // header 缺省中性兜底、options 空 + custom=true：纯开放题前端仍可自由输入作答
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-15", java.util.List.of(
                    toolCall("tc-o", "ask_user", Map.of("question", "还有什么要补充的?"))));

            AgentEvent frame = mapper.suspension(event);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload()
                    .get(AgentEventTypes.WAIT_DATA_FIELD);
            assertThat(data.get("questions")).isEqualTo(java.util.List.of(Map.of(
                    "header", "提问",
                    "question", "还有什么要补充的?",
                    "multiple", false,
                    "custom", true,
                    "options", java.util.List.of())));
        }

        @Test
        void given_ask_user_multiple_flag_when_suspension_then_projected_to_question() {
            // #19 多选问答：multiple 从 ask_user 入参投影（问答卡多选勾选提交），
            // 缺省 false（单选点即答）
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-16", java.util.List.of(
                    toolCall("tc-m", "ask_user", Map.of(
                            "header", "核心功能",
                            "question", "先做哪些能力?",
                            "multiple", true,
                            "options", java.util.List.of("预约", "提醒", "会员")))));

            AgentEvent frame = mapper.suspension(event);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) frame.payload()
                    .get(AgentEventTypes.WAIT_DATA_FIELD);
            assertThat(data.get("questions")).isEqualTo(java.util.List.of(Map.of(
                    "header", "核心功能",
                    "question", "先做哪些能力?",
                    "multiple", true,
                    "custom", true,
                    "options", java.util.List.of(
                            Map.of("label", "预约"), Map.of("label", "提醒"),
                            Map.of("label", "会员")))));
        }

        @Test
        void given_confirm_event_when_map_then_no_passthrough_frame() {
            // 挂起不是过程事件：挂起事件由调用方显式发射，map() 不重复产事件
            RequireUserConfirmEvent event = new RequireUserConfirmEvent("reply-11", java.util.List.of(
                    toolCall("tc-3", "write_file", Map.of())));

            assertThat(mapper.map(event)).isNull();
        }

        @Test
        void given_confirm_event_when_stream_completes_then_suspension_not_finish() {
            // 挂起轮的流终止不是终态：无结煞语（挂起事件由调用方显式发射）
            assertThat(mapper.finishToken(new RequireUserConfirmEvent("reply-12",
                    java.util.List.of(toolCall("tc-4", "write_file", Map.of()))))).isEmpty();
        }

        private io.agentscope.core.message.ToolUseBlock toolCall(String id, String name,
                Map<String, Object> input) {
            return new io.agentscope.core.message.ToolUseBlock(id, name, input);
        }
    }
}
