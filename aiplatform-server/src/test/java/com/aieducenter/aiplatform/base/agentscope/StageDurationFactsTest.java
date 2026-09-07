package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

/**
 * {@link StageDurationFacts}（#111 阶段耗时事实观察面）：模型调用按 replyId 配对
 * 累计进 LLM 桶；工具执行（callEnd→resultEnd 纯执行窗，参数在途的模型生成时间归
 * LLM 桶）按工具名分桶、command 工具按命令归组（install/dev/test/other）；带
 * source 的委派事件不进执行体桶、只记委派窗（首末跨距）。计时源 = 引擎事件
 * createdAt——只计配对闭合区间，解析不出/未配对即不计（不可观测即不计）。
 */
class StageDurationFactsTest {

    private static String ts(long epochMilli) {
        return Instant.ofEpochMilli(epochMilli).toString();
    }

    private static ModelCallStartEvent modelStart(String replyId, long at) {
        return new ModelCallStartEvent("evt-ms-" + replyId + "-" + at, ts(at), replyId);
    }

    private static ModelCallEndEvent modelEnd(String replyId, long at) {
        return new ModelCallEndEvent("evt-me-" + replyId + "-" + at, ts(at), replyId, null);
    }

    private static ToolCallDeltaEvent toolDelta(String toolCallId, String toolName,
            String delta, long at) {
        return new ToolCallDeltaEvent("evt-td-" + toolCallId + "-" + at, ts(at),
                "reply-1", toolCallId, toolName, delta);
    }

    private static ToolCallEndEvent toolCallEnd(String toolCallId, String toolName, long at) {
        return new ToolCallEndEvent("evt-tce-" + toolCallId + "-" + at, ts(at),
                "reply-1", toolCallId, toolName);
    }

    private static ToolResultEndEvent toolResultEnd(String toolCallId, String toolName,
            ToolResultState state, long at) {
        return new ToolResultEndEvent("evt-tre-" + toolCallId + "-" + at, ts(at),
                "reply-1", toolCallId, toolName, state);
    }

    @Test
    void given_model_call_pairs_when_snapshot_then_llm_ms_summed() {
        // LLM 桶：模型调用起止按 replyId 配对累计，多次调用求和
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(modelStart("r-1", 1_000));
        facts.onEvent(modelEnd("r-1", 1_250));
        facts.onEvent(modelStart("r-2", 2_000));
        facts.onEvent(modelEnd("r-2", 2_080));

        assertThat(facts.snapshot().llmMs()).isEqualTo(250 + 80);
    }

    @Test
    void given_unpaired_model_events_when_snapshot_then_ignored() {
        // 只计配对闭合区间：无起点的终点、未闭合的起点都不计
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(modelEnd("r-orphan", 5_000));
        facts.onEvent(modelStart("r-open", 6_000));

        assertThat(facts.snapshot().llmMs()).isZero();
    }

    @Test
    void given_tool_executions_when_snapshot_then_tool_ms_by_name() {
        // 工具桶：callEnd（参数落定）→ resultEnd（结果落定）纯执行窗按工具名分桶，
        // 同名多次求和；失败态照样计（失败的执行照样耗时——归因不粉饰）
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(toolCallEnd("tc-1", "write_file", 1_000));
        facts.onEvent(toolResultEnd("tc-1", "write_file", ToolResultState.SUCCESS, 1_040));
        facts.onEvent(toolCallEnd("tc-2", "read_file", 2_000));
        facts.onEvent(toolResultEnd("tc-2", "read_file", ToolResultState.SUCCESS, 2_005));
        facts.onEvent(toolCallEnd("tc-3", "write_file", 3_000));
        facts.onEvent(toolResultEnd("tc-3", "write_file", ToolResultState.ERROR, 3_020));

        StageDurations durations = facts.snapshot();
        assertThat(durations.toolsMs())
                .containsEntry("write_file", 60L)
                .containsEntry("read_file", 5L)
                .hasSize(2);
        assertThat(durations.commandMs()).isEmpty();
    }

    @Test
    void given_command_tool_when_snapshot_then_grouped_by_command_text() {
        // command 桶按命令归组（不进 toolsMs 平铺）：命令文本从参数增量累积解析
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(toolDelta("tc-1", "command", "{\"command\":\"npm ", 1_000));
        facts.onEvent(toolDelta("tc-1", "command", "install\"}", 1_010));
        facts.onEvent(toolCallEnd("tc-1", "command", 1_020));
        facts.onEvent(toolResultEnd("tc-1", "command", ToolResultState.SUCCESS, 61_020));
        facts.onEvent(toolDelta("tc-2", "command", "{\"command\":\"npm run dev\"}", 62_000));
        facts.onEvent(toolCallEnd("tc-2", "command", 62_010));
        facts.onEvent(toolResultEnd("tc-2", "command", ToolResultState.RUNNING, 62_800));
        facts.onEvent(toolCallEnd("tc-3", "command", 63_000)); // 无参数增量（不可观测）→ 其他
        facts.onEvent(toolResultEnd("tc-3", "command", ToolResultState.SUCCESS, 63_100));

        StageDurations durations = facts.snapshot();
        assertThat(durations.commandMs())
                .containsEntry("install", 60_000L)
                .containsEntry("dev", 790L)
                .containsEntry("other", 100L)
                .hasSize(3);
        assertThat(durations.toolsMs()).isEmpty();
    }

    @Test
    void given_command_texts_when_classified_then_groups_by_command_kind() {
        // 归组形态（粗分组首版）：依赖安装 / dev server / 测试 / 其他兜底；
        // 复合命令按段左到右首个命中定组
        assertThat(StageDurationFacts.classify("npm install")).isEqualTo("install");
        assertThat(StageDurationFacts.classify("npm i --legacy-peer-deps")).isEqualTo("install");
        assertThat(StageDurationFacts.classify("pnpm add lodash")).isEqualTo("install");
        assertThat(StageDurationFacts.classify("pip install psycopg")).isEqualTo("install");
        assertThat(StageDurationFacts.classify("npm run dev")).isEqualTo("dev");
        assertThat(StageDurationFacts.classify("npm start")).isEqualTo("dev");
        assertThat(StageDurationFacts.classify("nohup npm run dev > app.log 2>&1 &"))
                .isEqualTo("dev");
        assertThat(StageDurationFacts.classify("npx vite --port 8081")).isEqualTo("dev");
        assertThat(StageDurationFacts.classify("npm test")).isEqualTo("test");
        assertThat(StageDurationFacts.classify("npm run test")).isEqualTo("test");
        assertThat(StageDurationFacts.classify("npx vitest run")).isEqualTo("test");
        assertThat(StageDurationFacts.classify("pytest tests/")).isEqualTo("test");
        assertThat(StageDurationFacts.classify("curl -s http://localhost:8081")).isEqualTo("other");
        assertThat(StageDurationFacts.classify("npm run build")).isEqualTo("other");
        assertThat(StageDurationFacts.classify("git init")).isEqualTo("other");
        // 复合命令：cd 不命中，左到右首个命中段定组
        assertThat(StageDurationFacts.classify("cd /app && npm install")).isEqualTo("install");
        assertThat(StageDurationFacts.classify("npm install && npm test")).isEqualTo("install");
        assertThat(StageDurationFacts.classify("")).isEqualTo("other");
    }

    @Test
    void given_sourced_events_when_snapshot_then_subagent_window_and_excluded_from_main_buckets() {
        // 委派窗：带 source 事件（子智能体转发进父流）记首末跨距（末段名归一），
        // 其模型/工具事件不进执行体的 LLM/工具桶（窗内细节即子智能体账）
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(modelStart("r-1", 1_000));
        facts.onEvent(new TextBlockDeltaEvent("evt-st-1", ts(1_100), "r-sub", "b-1", "自测开始")
                .withSource("platform-agent/self-test"));
        facts.onEvent(modelStart("r-sub", 1_200).withSource("platform-agent/self-test"));
        facts.onEvent(modelEnd("r-sub", 1_500).withSource("platform-agent/self-test"));
        facts.onEvent(toolCallEnd("tc-sub", "command", 1_600)
                .withSource("platform-agent/self-test"));
        facts.onEvent(toolResultEnd("tc-sub", "command", ToolResultState.SUCCESS, 1_900)
                .withSource("platform-agent/self-test"));
        facts.onEvent(modelEnd("r-1", 2_000));

        StageDurations durations = facts.snapshot();
        assertThat(durations.subagentMs()).containsExactly(Map.entry("self-test", 800L));
        assertThat(durations.llmMs()).isEqualTo(1_000L); // 只含执行体的 r-1
        assertThat(durations.toolsMs()).isEmpty();
        assertThat(durations.commandMs()).isEmpty();
    }

    @Test
    void given_malformed_created_at_when_event_then_unobserved() {
        // 不可观测即不计：createdAt 解析不出的事件不产生任何账
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(new ModelCallStartEvent("evt-bad-1", "not-a-date", "r-1"));
        facts.onEvent(new ModelCallEndEvent("evt-bad-2", "not-a-date", "r-1", null));

        assertThat(facts.snapshot()).isEqualTo(StageDurations.zero());
    }

    @Test
    void given_no_events_when_snapshot_then_zero() {
        assertThat(new StageDurationFacts().snapshot()).isEqualTo(StageDurations.zero());
    }
}
