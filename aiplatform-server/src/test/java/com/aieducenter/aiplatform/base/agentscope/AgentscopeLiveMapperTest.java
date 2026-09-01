package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * {@link AgentscopeLiveMapper} 单点映射表（#23 直播词汇正本的唯一生产方）：AgentScope
 * 事件 → 平台直播帧（live-text / live-action / live-step）。口径：解说 = 智能体自述
 * 为主（text 逐段成型：句读 / 块变 / 步骤与动作边界 / 长度上限切段）+ 工具动作人话
 * 模板兜底；思考与读类工具不播。每帧 payload 盖 runId/sessionId/engine + 段字段。
 */
class AgentscopeLiveMapperTest {

    private static final String RUN_ID = "r1";
    private static final String SESSION_ID = "s1";
    private static final String ENGINE = "agentscope";

    private final AgentscopeLiveMapper mapper = new AgentscopeLiveMapper(RUN_ID, SESSION_ID, ENGINE);

    private List<String> types(List<AgentEvent> frames) {
        return frames.stream().map(AgentEvent::type).toList();
    }

    @Nested
    class NarrationSegments {

        @Test
        void text_accumulates_and_flushes_on_sentence_ender() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在编写"))).isEmpty();

            List<AgentEvent> frames = mapper.map(new TextBlockDeltaEvent("r", "b-1", "订单管理页面。"));

            assertThat(types(frames)).containsExactly(AgentEventTypes.LIVE_TEXT);
            assertThat(frames.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ROLE_ENGINE_FIELD, ENGINE,
                    AgentEventTypes.LIVE_TEXT_FIELD, "正在编写订单管理页面。"));
        }

        @Test
        void tail_without_ender_flushes_once_at_run_end() {
            // 无句读、无边界事件：map 不出段，run 收尾 flush 出尾段（恰一帧）
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "马上就好"))).isEmpty();

            List<AgentEvent> tail = mapper.flush();

            assertThat(tail).singleElement().satisfies(frame -> {
                assertThat(frame.type()).isEqualTo(AgentEventTypes.LIVE_TEXT);
                assertThat(frame.payload()).containsEntry(
                        AgentEventTypes.LIVE_TEXT_FIELD, "马上就好");
            });
            // 收尾 flush 幂等：不重复出段
            assertThat(mapper.flush()).isEmpty();
        }

        @Test
        void multi_sentence_delta_settles_one_frame_per_sentence() {
            // 粗粒度增量（一次到达多句）：句读落定即出段——每句一帧，不憋整块
            List<AgentEvent> frames = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "第一句。第二句！第三句"));

            assertThat(frames).extracting(AgentEvent::type)
                    .containsExactly(AgentEventTypes.LIVE_TEXT, AgentEventTypes.LIVE_TEXT);
            assertThat(frames.get(0).payload()).containsEntry(
                    AgentEventTypes.LIVE_TEXT_FIELD, "第一句。");
            assertThat(frames.get(1).payload()).containsEntry(
                    AgentEventTypes.LIVE_TEXT_FIELD, "第二句！");
            // 无句读尾部留缓冲：收尾 flush 整段出
            assertThat(mapper.flush()).singleElement().satisfies(frame ->
                    assertThat(frame.payload()).containsEntry(
                            AgentEventTypes.LIVE_TEXT_FIELD, "第三句"));
        }

        @Test
        void block_change_flushes_pending_segment() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "第一段"));

            List<AgentEvent> frames = mapper.map(new TextBlockDeltaEvent("r", "b-2", "第二段。"));

            // 块变即段边界：先出「第一段」余段，新块再按句读出段
            assertThat(frames).extracting(AgentEvent::type)
                    .containsExactly(AgentEventTypes.LIVE_TEXT, AgentEventTypes.LIVE_TEXT);
            assertThat(frames.get(0).payload()).containsEntry(AgentEventTypes.LIVE_TEXT_FIELD, "第一段");
            assertThat(frames.get(1).payload()).containsEntry(AgentEventTypes.LIVE_TEXT_FIELD, "第二段。");
        }

        @Test
        void oversize_segment_flushes_without_sentence_ender() {
            // 长度上限切段（直播时延有界）：超限即出段，不等句读
            String longDelta = "字".repeat(200);

            List<AgentEvent> frames = mapper.map(new TextBlockDeltaEvent("r", "b-1", longDelta));

            assertThat(types(frames)).containsExactly(AgentEventTypes.LIVE_TEXT);
            assertThat(frames.get(0).payload()).containsEntry(
                    AgentEventTypes.LIVE_TEXT_FIELD, longDelta);
        }

        @Test
        void blank_narration_produces_no_frame() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "  "))).isEmpty();
            assertThat(mapper.flush()).isEmpty();
        }
    }

    @Nested
    class ActionLines {

        @Test
        void write_file_end_yields_action_with_label_from_path() {
            // 参数增量分片到达（真实流形态），调用落定点拼全解析
            mapper.map(new ToolCallStartEvent("r", "tc-1", "write_file"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-1", "write_file",
                    "{\"path\":\"src/pages/订单管理.tsx\","));
            mapper.map(new ToolCallDeltaEvent("r", "tc-1", "write_file",
                    "\"content\":\"...\"}"));
            List<AgentEvent> frames = mapper.map(new ToolCallEndEvent("r", "tc-1", "write_file"));

            assertThat(types(frames)).containsExactly(AgentEventTypes.LIVE_ACTION);
            assertThat(frames.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ROLE_ENGINE_FIELD, ENGINE,
                    AgentEventTypes.LIVE_ACTION_FIELD, "正在编写【订单管理】"));
        }

        @Test
        void edit_file_shares_the_writing_template() {
            mapper.map(new ToolCallStartEvent("r", "tc-2", "edit_file"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-2", "edit_file",
                    "{\"path\":\"/workspace/src/orders.tsx\"}"));

            List<AgentEvent> frames = mapper.map(new ToolCallEndEvent("r", "tc-2", "edit_file"));

            assertThat(frames.get(0).payload()).containsEntry(
                    AgentEventTypes.LIVE_ACTION_FIELD, "正在编写【orders】");
        }

        @Test
        void command_yields_plain_action_line() {
            mapper.map(new ToolCallStartEvent("r", "tc-3", "command"));

            List<AgentEvent> frames = mapper.map(new ToolCallEndEvent("r", "tc-3", "command"));

            assertThat(types(frames)).containsExactly(AgentEventTypes.LIVE_ACTION);
            assertThat(frames.get(0).payload()).containsEntry(
                    AgentEventTypes.LIVE_ACTION_FIELD, "正在运行命令");
        }

        @Test
        void write_file_without_parsable_args_falls_back_to_generic_line() {
            // 参数流缺失（非流式参数形态）：同一模板、通用标签兜底
            mapper.map(new ToolCallStartEvent("r", "tc-4", "write_file"));

            List<AgentEvent> frames = mapper.map(new ToolCallEndEvent("r", "tc-4", "write_file"));

            assertThat(frames.get(0).payload()).containsEntry(
                    AgentEventTypes.LIVE_ACTION_FIELD, "正在编写【代码文件】");
        }

        @Test
        void read_tools_are_not_broadcast() {
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-5", "read_file"))).isEmpty();
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-6", "grep_files"))).isEmpty();
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-7", "glob_files"))).isEmpty();
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-8", "list_files"))).isEmpty();
        }
    }

    @Nested
    class StepsAndBoundaries {

        @Test
        void model_call_starts_count_steps_from_one() {
            List<AgentEvent> first = mapper.map(new ModelCallStartEvent("reply-1"));
            List<AgentEvent> second = mapper.map(new ModelCallStartEvent("reply-2"));

            assertThat(first).singleElement().satisfies(frame -> {
                assertThat(frame.type()).isEqualTo(AgentEventTypes.LIVE_STEP);
                assertThat(frame.payload()).containsEntry(AgentEventTypes.LIVE_STEP_FIELD, 1);
            });
            assertThat(second).singleElement().satisfies(frame ->
                    assertThat(frame.payload()).containsEntry(AgentEventTypes.LIVE_STEP_FIELD, 2));
        }

        @Test
        void pending_narration_flushes_before_action_and_step_frames() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "开始搭数据库"));

            List<AgentEvent> frames = mapper.map(new ToolCallEndEvent("r", "tc-9", "command"));

            assertThat(types(frames)).containsExactly(
                    AgentEventTypes.LIVE_TEXT, AgentEventTypes.LIVE_ACTION);
        }

        @Test
        void thinking_is_never_broadcast() {
            assertThat(mapper.map(new ThinkingBlockDeltaEvent("r", "b-9", "内部思考"))).isEmpty();
        }

        @Test
        void unmapped_events_produce_nothing() {
            assertThat(mapper.map(new AgentEndEvent("reply-9"))).isEmpty();
        }
    }

    @Nested
    class WireShape {

        @Test
        void every_frame_carries_correlation_and_no_type_key_in_payload() {
            mapper.map(new ToolCallEndEvent("r", "tc-1", "command"));

            // 信封契约：payload 顶层禁 type 键名（关联字段 + 段字段即全部）
            // ——此处借 containsOnly 锁死全键集
            List<AgentEvent> frames = mapper.map(new TextBlockDeltaEvent("r", "b-1", "一段。"));

            assertThat(frames).singleElement().satisfies(frame ->
                    assertThat(frame.payload()).containsOnlyKeys(
                            AgentEventTypes.RUN_FIELD, AgentEventTypes.SESSION_FIELD,
                            AgentEventTypes.ROLE_ENGINE_FIELD, AgentEventTypes.LIVE_TEXT_FIELD));
        }
    }

    @DisplayName("烟囱：一段真实形态的事件序列出帧有序")
    @Test
    void full_sequence_emits_ordered_frames() {
        List<AgentEvent> all = List.of();
        all = concat(all, mapper.map(new ModelCallStartEvent("reply-1")));
        all = concat(all, mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在准备演示数据。")));
        all = concat(all, mapper.map(new ToolCallStartEvent("r", "tc-1", "write_file")));
        all = concat(all, mapper.map(new ToolCallDeltaEvent("r", "tc-1", "write_file",
                "{\"path\":\"data/seed.sql\"}")));
        all = concat(all, mapper.map(new ToolCallEndEvent("r", "tc-1", "write_file")));
        all = concat(all, mapper.map(new TextBlockDeltaEvent("r", "b-2", "数据库就绪")));
        all = concat(all, mapper.flush().stream().toList());

        assertThat(all).extracting(AgentEvent::type).containsExactly(
                AgentEventTypes.LIVE_STEP,
                AgentEventTypes.LIVE_TEXT,
                AgentEventTypes.LIVE_ACTION,
                AgentEventTypes.LIVE_TEXT);
    }

    private static List<AgentEvent> concat(List<AgentEvent> base, List<AgentEvent> more) {
        var merged = new java.util.ArrayList<>(base);
        merged.addAll(more);
        return merged;
    }
}
