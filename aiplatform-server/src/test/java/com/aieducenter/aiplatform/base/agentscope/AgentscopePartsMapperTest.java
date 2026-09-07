package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * {@link AgentscopePartsMapper} 单点映射表（#77 parts 契约的唯一生产方）：AgentScope
 * 事件 → 消息部件事件（part-text / part-action）。口径：动作卡全生命
 * 周期（开始即出事件——工具调用发起即 started，参数落定 running，结果返回
 * completed/failed，同一 toolCallId 锚定）；解说切段与直播同内核；思考与读类
 * 工具不进部件。步骤分组已退役（#115）：ModelCallStartEvent 不再产 part-step。
 * 每事件 payload 盖 runId/sessionId/engine + 部件字段（扁平，无 data 键）。
 */
class AgentscopePartsMapperTest {

    private static final String RUN_ID = "r1";
    private static final String SESSION_ID = "s1";
    private static final String ENGINE = "agentscope";

    private final AgentscopePartsMapper mapper = new AgentscopePartsMapper(RUN_ID, SESSION_ID, ENGINE);

    private List<String> types(List<AgentEvent> parts) {
        return parts.stream().map(AgentEvent::type).toList();
    }

    @Nested
    class TextParts {

        @Test
        void given_narration_accumulating_when_sentence_settles_then_part_text() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在编写"))).isEmpty();

            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1", "订单管理页面。"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_TEXT);
            assertThat(parts.get(0).payload()).containsAllEntriesOf(java.util.Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ENGINE_FIELD, ENGINE,
                    AgentEventTypes.PART_TEXT_FIELD, "正在编写订单管理页面。"));
        }

        @Test
        void given_tail_without_ender_when_drain_then_single_part_at_run_end() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "马上就好"))).isEmpty();

            List<AgentEvent> tail = mapper.drain();

            assertThat(tail).singleElement().satisfies(part -> {
                assertThat(part.type()).isEqualTo(AgentEventTypes.PART_TEXT);
                assertThat(part.payload()).containsEntry(AgentEventTypes.PART_TEXT_FIELD, "马上就好");
            });
            // 收尾 drain 幂等：不重复出段
            assertThat(mapper.drain()).isEmpty();
        }
    }

    @Nested
    class ActionParts {

        @Test
        void given_write_action_when_full_lifecycle_then_started_running_completed_same_anchor() {
            List<AgentEvent> started = mapper.map(new ToolCallStartEvent("r", "tc-1", "write_file"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-1", "write_file",
                    "{\"path\":\"src/pages/订单管理.tsx\"}"));
            List<AgentEvent> running = mapper.map(new ToolCallEndEvent("r", "tc-1", "write_file"));
            List<AgentEvent> completed = mapper.map(
                    new ToolResultEndEvent("r", "tc-1", "write_file", ToolResultState.SUCCESS));

            assertThat(types(started)).containsExactly(AgentEventTypes.PART_ACTION);
            assertThat(started.get(0).payload()).containsAllEntriesOf(java.util.Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ENGINE_FIELD, ENGINE,
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "tc-1",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "write_file",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_STARTED,
                    // 参数在途：动作对象为通用标签——动作卡出现即「进行中」（#77 补动作开始）
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【代码文件】"));
            // running：参数落定，动作对象具体化（无时态——时态由 state 表达）
            assertThat(running.get(0).payload()).containsAllEntriesOf(java.util.Map.of(
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_RUNNING,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【订单管理】"));
            // completed：复述已锚定的具体对象，不闪回通用标签
            assertThat(completed.get(0).payload()).containsAllEntriesOf(java.util.Map.of(
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_COMPLETED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【订单管理】"));
        }

        @Test
        void given_error_result_when_lifecycle_completes_then_failed_state() {
            mapper.map(new ToolCallStartEvent("r", "tc-2", "command"));
            mapper.map(new ToolCallEndEvent("r", "tc-2", "command"));

            List<AgentEvent> failed = mapper.map(
                    new ToolResultEndEvent("r", "tc-2", "command", ToolResultState.ERROR));

            assertThat(failed.get(0).payload()).containsAllEntriesOf(java.util.Map.of(
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "tc-2",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_FAILED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行命令"));
        }

        @Test
        void given_denied_and_interrupted_results_when_lifecycle_completes_then_failed_state() {
            for (ToolResultState state : List.of(ToolResultState.DENIED, ToolResultState.INTERRUPTED)) {
                AgentscopePartsMapper fresh = new AgentscopePartsMapper(RUN_ID, SESSION_ID, ENGINE);
                fresh.map(new ToolCallStartEvent("r", "tc-9", "command"));
                List<AgentEvent> parts = fresh.map(new ToolResultEndEvent("r", "tc-9", "command", state));
                assertThat(parts.get(0).payload()).containsEntry(
                        AgentEventTypes.PART_ACTION_STATE_FIELD,
                        AgentEventTypes.PART_ACTION_STATE_FAILED);
            }
        }

        @Test
        void given_read_tools_when_mapped_then_no_action_parts() {
            assertThat(mapper.map(new ToolCallStartEvent("r", "tc-3", "read_file"))).isEmpty();
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-3", "read_file"))).isEmpty();
            assertThat(mapper.map(new ToolResultEndEvent("r", "tc-3", "read_file",
                    ToolResultState.SUCCESS))).isEmpty();
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-4", "grep_files"))).isEmpty();
        }
    }

    @Nested
    class StepsAndBoundaries {

        @Test
        void given_model_call_starts_when_mapped_then_no_part_step() {
            // 步骤分组已退役（#115）：ModelCallStartEvent 不再产 part-step——
            // 步骤序号对用户零信息，部件按序竖排
            assertThat(mapper.map(new ModelCallStartEvent("reply-1"))).isEmpty();
            assertThat(mapper.map(new ModelCallStartEvent("reply-2"))).isEmpty();
        }

        @Test
        void given_pending_narration_when_action_arrives_then_drained_before_action_part() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "开始搭数据库"));

            List<AgentEvent> atAction = mapper.map(new ToolCallStartEvent("r", "tc-5", "command"));
            assertThat(types(atAction)).containsExactly(
                    AgentEventTypes.PART_TEXT, AgentEventTypes.PART_ACTION);

            // 步骤分组退役：模型调用开始不再产部件（也不触发叙事切段）
            List<AgentEvent> atStep = mapper.map(new ModelCallStartEvent("reply-2"));
            assertThat(atStep).isEmpty();
        }

        @Test
        void given_thinking_and_unmapped_events_when_mapped_then_no_parts() {
            assertThat(mapper.map(new ThinkingBlockDeltaEvent("r", "b-9", "内部思考"))).isEmpty();
            assertThat(mapper.map(new AgentEndEvent("reply-9"))).isEmpty();
        }
    }

    @Nested
    class WireShape {

        @Test
        void given_any_part_when_built_then_flat_keys_and_no_type_or_data_key() {
            // 信封契约：payload 顶层禁 type 键名；部件载荷扁平（无 data 键——前端
            // 透传收窄以 data 键为准，双发射期部件不被旧前端误收）
            mapper.map(new ToolCallStartEvent("r", "tc-1", "command"));
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1", "一段。"));

            assertThat(parts).singleElement().satisfies(part ->
                    assertThat(part.payload()).containsOnlyKeys(
                            AgentEventTypes.RUN_FIELD, AgentEventTypes.SESSION_FIELD,
                            AgentEventTypes.ENGINE_FIELD, AgentEventTypes.PART_TEXT_FIELD));
        }
    }

    @DisplayName("烟囱：一段真实形态的事件序列出部件有序")
    @Test
    void given_real_shaped_sequence_when_mapped_then_ordered_parts() {
        List<AgentEvent> all = List.of();
        all = concat(all, mapper.map(new ModelCallStartEvent("reply-1"))); // 步骤边界：已退役，不出部件
        all = concat(all, mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在准备演示数据。")));
        all = concat(all, mapper.map(new ToolCallStartEvent("r", "tc-1", "write_file")));
        all = concat(all, mapper.map(new ToolCallDeltaEvent("r", "tc-1", "write_file",
                "{\"path\":\"data/seed.sql\"}")));
        all = concat(all, mapper.map(new ToolCallEndEvent("r", "tc-1", "write_file")));
        all = concat(all, mapper.map(new ToolResultEndEvent("r", "tc-1", "write_file",
                ToolResultState.SUCCESS)));
        all = concat(all, mapper.map(new TextBlockDeltaEvent("r", "b-2", "数据库就绪")));
        all = concat(all, mapper.drain());

        assertThat(all).extracting(AgentEvent::type).containsExactly(
                AgentEventTypes.PART_TEXT,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_TEXT);
    }

    private static List<AgentEvent> concat(List<AgentEvent> base, List<AgentEvent> more) {
        var merged = new java.util.ArrayList<>(base);
        merged.addAll(more);
        return merged;
    }

    @Nested
    class SourceAttribution {

        /** #95 委派位：执行体自身部件不携带 source（缺省 = 执行体）。 */
        @Test
        void given_parent_narration_when_mapped_then_no_source_field() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1", "执行体自述。"));

            assertThat(parts).singleElement().satisfies(part ->
                    assertThat(part.payload()).doesNotContainKey(AgentEventTypes.SOURCE_FIELD));
        }

        /** #95：子智能体解说段带来源归属（过程呈现分角色播的依据）。 */
        @Test
        void given_subagent_narration_when_mapped_then_part_text_carries_source() {
            List<AgentEvent> parts = mapper.map(
                    new TextBlockDeltaEvent("r", "b-1", "自测逐项通过。")
                            .withSource("platform-agent/self-test"));

            assertThat(parts).singleElement().satisfies(part -> {
                assertThat(part.type()).isEqualTo(AgentEventTypes.PART_TEXT);
                assertThat(part.payload()).containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test");
            });
        }

        /** 子智能体动作卡带来源（动作卡分角色播——自测清单式播报的呈现依据）。 */
        @Test
        void given_subagent_action_when_mapped_then_part_action_carries_source() {
            List<AgentEvent> parts = mapper.map(
                    new ToolCallStartEvent("r", "tc-1", "command").withSource("self-test"));

            assertThat(parts).singleElement().satisfies(part -> {
                assertThat(part.type()).isEqualTo(AgentEventTypes.PART_ACTION);
                assertThat(part.payload()).containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test");
            });
        }

        /** 来源切换即段边界：执行体余段先出（执行体来源），子智能体段随后（子智能体来源）。 */
        @Test
        void given_source_switch_mid_narration_when_mapped_then_segments_split_by_source() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "执行体说半句"));

            List<AgentEvent> parts = mapper.map(
                    new TextBlockDeltaEvent("r", "b-2", "子智能体接上。").withSource("self-test"));

            assertThat(parts).hasSize(2);
            assertThat(parts.get(0).payload())
                    .containsEntry(AgentEventTypes.PART_TEXT_FIELD, "执行体说半句")
                    .doesNotContainKey(AgentEventTypes.SOURCE_FIELD);
            assertThat(parts.get(1).payload())
                    .containsEntry(AgentEventTypes.PART_TEXT_FIELD, "子智能体接上。")
                    .containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test");
        }
    }
}
