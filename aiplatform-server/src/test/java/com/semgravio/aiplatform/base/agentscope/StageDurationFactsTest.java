package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;

/**
 * {@link StageDurationFacts}（#111 阶段耗时事实观察面）：模型调用按 replyId 配对
 * 累计进 LLM 桶；工具执行（callEnd→resultEnd 纯执行窗，参数在途的模型生成时间归
 * LLM 桶）按工具名分桶、command 工具按命令归组（#417 七族：install/build/dev/
 * test/db/http/script/other）；带 source 的委派事件不进执行体桶、按源归集该源
 * 自身配对区间（#417 弃首末单窗跨距——并行父流时间不入验证桶）。计时源 = 引擎
 * 事件 createdAt——只计配对闭合区间，解析不出/未配对即不计（不可观测即不计）。
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
        facts.onEvent(toolDelta("tc-1", "execute", "{\"command\":\"npm ", 1_000));
        facts.onEvent(toolDelta("tc-1", "execute", "install\"}", 1_010));
        facts.onEvent(toolCallEnd("tc-1", "execute", 1_020));
        facts.onEvent(toolResultEnd("tc-1", "execute", ToolResultState.SUCCESS, 61_020));
        facts.onEvent(toolDelta("tc-2", "execute", "{\"command\":\"npm run dev\"}", 62_000));
        facts.onEvent(toolCallEnd("tc-2", "execute", 62_010));
        facts.onEvent(toolResultEnd("tc-2", "execute", ToolResultState.RUNNING, 62_800));
        facts.onEvent(toolCallEnd("tc-3", "execute", 63_000)); // 无参数增量（不可观测）→ 其他
        facts.onEvent(toolResultEnd("tc-3", "execute", ToolResultState.SUCCESS, 63_100));

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
        // 归组形态（#417 扩族——other 不再吞掉全部命令耗时）：install / dev / test
        // / build（构建）/ script（node/npx 一次性运行）/ db / http / other 兜底；
        // 复合命令按段左到右、段内 install > build > dev > test > db > http > script
        // 首个命中定组
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
        assertThat(StageDurationFacts.classify("npm run build")).isEqualTo("build");
        assertThat(StageDurationFacts.classify("npx next build")).isEqualTo("build");
        assertThat(StageDurationFacts.classify("vite build")).isEqualTo("build");
        assertThat(StageDurationFacts.classify("npx tsc --noEmit")).isEqualTo("build");
        assertThat(StageDurationFacts.classify("node scripts/seed.mjs")).isEqualTo("script");
        assertThat(StageDurationFacts.classify("npx shadcn@latest add button")).isEqualTo("script");
        assertThat(StageDurationFacts.classify("psql \"$DATABASE_URL\" -c 'select 1'"))
                .isEqualTo("db");
        assertThat(StageDurationFacts.classify("pg_dump app > dump.sql")).isEqualTo("db");
        assertThat(StageDurationFacts.classify("curl -s http://localhost:8081/health"))
                .isEqualTo("http");
        assertThat(StageDurationFacts.classify("wget -q -O /dev/null http://localhost:3000"))
                .isEqualTo("http");
        assertThat(StageDurationFacts.classify("git init")).isEqualTo("other");
        assertThat(StageDurationFacts.classify("ls -la")).isEqualTo("other");
        // 复合命令：cd 不命中，左到右首个命中段定组
        assertThat(StageDurationFacts.classify("cd /app && npm install")).isEqualTo("install");
        assertThat(StageDurationFacts.classify("npm install && npm test")).isEqualTo("install");
        assertThat(StageDurationFacts.classify("npm run build && npm start")).isEqualTo("build");
        assertThat(StageDurationFacts.classify("")).isEqualTo("other");
    }

    @Test
    void given_sourced_events_when_snapshot_then_subagent_account_and_excluded_from_main_buckets() {
        // 委派源账（#417 计量口径修复）：带 source 事件按源归集——该源模型调用与
        // 工具执行配对累计（本例 300 + 300），弃「首末单窗跨距」口径（旧口径窗
        // 1_100→1_900 = 800——把窗内并行父流 r-1 的时间圈进验证桶，重叠双计）；
        // 其模型/工具事件不进执行体的 LLM/工具桶
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(modelStart("r-1", 1_000));
        facts.onEvent(new TextBlockDeltaEvent("evt-st-1", ts(1_100), "r-sub", "b-1", "验证开始")
                .withSource("platform-agent/verify"));
        facts.onEvent(modelStart("r-sub", 1_200).withSource("platform-agent/verify"));
        facts.onEvent(modelEnd("r-sub", 1_500).withSource("platform-agent/verify"));
        facts.onEvent(toolCallEnd("tc-sub", "execute", 1_600)
                .withSource("platform-agent/verify"));
        facts.onEvent(toolResultEnd("tc-sub", "execute", ToolResultState.SUCCESS, 1_900)
                .withSource("platform-agent/verify"));
        facts.onEvent(modelEnd("r-1", 2_000));

        StageDurations durations = facts.snapshot();
        assertThat(durations.subagentMs()).containsExactly(Map.entry("verify", 600L));
        assertThat(durations.llmMs()).isEqualTo(1_000L); // 并行父流 r-1 全额进 LLM 桶、不进验证桶
        assertThat(durations.toolsMs()).isEmpty();
        assertThat(durations.commandMs()).isEmpty();
    }

    @Test
    void given_unpaired_sourced_intervals_when_snapshot_then_dropped() {
        // 源账同执行体口径：只计配对闭合区间——无起点的终点、未闭合的起点不入账
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(modelEnd("r-orphan", 5_000).withSource("platform-agent/verify"));
        facts.onEvent(modelStart("r-open", 6_000).withSource("platform-agent/verify"));
        facts.onEvent(toolResultEnd("tc-open", "execute", ToolResultState.SUCCESS, 7_000)
                .withSource("platform-agent/verify"));

        assertThat(facts.snapshot().subagentMs()).containsExactly(Map.entry("verify", 0L));
    }

    @Test
    void given_interleaved_parallel_stream_when_snapshot_then_buckets_and_remainder_close() {
        // 「桶计＋差值≈durationMs」缝测守卫（#417——合成事件流→四桶＋差值闭合，
        // 口径回归被拦）：生产形态交织流——agent_send 30s 超时后台化后父流继续
        // 推理（与 verify 源事件真并行重叠 2900），wait_async_results 等待屏障
        // 跨越 verify 尾段；时间线（ms）：
        //   父流  r-1 [0,1000] · install [1100,4100] · r-2 [4200,5200] ·
        //         agent_send [5300,5600] · r-3 [5700,6700] · build [6800,8800] ·
        //         script [8900,9500] · wait_async_results [9600,13600] ·
        //         r-4 [13700,14300] · write_file [14400,14800]
        //   verify  r-v1 [5750,6650]∥r-3 · 浏览器 [6800,8800]∥build ·
        //           r-v2 [9700,10000] · psql [10100,10500]（屏障内）
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(modelStart("r-1", 0));
        facts.onEvent(toolDelta("tc-1", "execute", "{\"command\":\"npm install\"}", 900));
        facts.onEvent(modelEnd("r-1", 1_000));
        facts.onEvent(toolCallEnd("tc-1", "execute", 1_100));
        facts.onEvent(toolResultEnd("tc-1", "execute", ToolResultState.SUCCESS, 4_100));
        facts.onEvent(modelStart("r-2", 4_200));
        facts.onEvent(modelEnd("r-2", 5_200));
        facts.onEvent(toolCallEnd("tc-2", "agent_send", 5_300));
        facts.onEvent(toolResultEnd("tc-2", "agent_send", ToolResultState.SUCCESS, 5_600));
        facts.onEvent(modelStart("r-3", 5_700));
        facts.onEvent(modelStart("r-v1", 5_750).withSource("platform-agent/verify"));
        facts.onEvent(modelEnd("r-v1", 6_650).withSource("platform-agent/verify"));
        facts.onEvent(modelEnd("r-3", 6_700));
        facts.onEvent(toolDelta("tc-3", "execute", "{\"command\":\"npm run build\"}", 6_750));
        facts.onEvent(toolCallEnd("tc-3", "execute", 6_800));
        facts.onEvent(toolCallEnd("tc-v1", "execute", 6_800).withSource("platform-agent/verify"));
        facts.onEvent(toolResultEnd("tc-3", "execute", ToolResultState.SUCCESS, 8_800));
        facts.onEvent(toolResultEnd("tc-v1", "execute", ToolResultState.SUCCESS, 8_800)
                .withSource("platform-agent/verify"));
        facts.onEvent(toolDelta("tc-4", "execute", "{\"command\":\"node scripts/seed.mjs\"}", 8_850));
        facts.onEvent(toolCallEnd("tc-4", "execute", 8_900));
        facts.onEvent(toolResultEnd("tc-4", "execute", ToolResultState.SUCCESS, 9_500));
        facts.onEvent(toolCallEnd("tc-5", "wait_async_results", 9_600));
        facts.onEvent(modelStart("r-v2", 9_700).withSource("platform-agent/verify"));
        facts.onEvent(modelEnd("r-v2", 10_000).withSource("platform-agent/verify"));
        facts.onEvent(toolDelta("tc-v2", "execute", "{\"command\":\"psql -c 'select 1'\"}", 10_050)
                .withSource("platform-agent/verify"));
        facts.onEvent(toolCallEnd("tc-v2", "execute", 10_100).withSource("platform-agent/verify"));
        facts.onEvent(toolResultEnd("tc-v2", "execute", ToolResultState.SUCCESS, 10_500)
                .withSource("platform-agent/verify"));
        facts.onEvent(toolResultEnd("tc-5", "wait_async_results", ToolResultState.SUCCESS, 13_600));
        facts.onEvent(modelStart("r-4", 13_700));
        facts.onEvent(modelEnd("r-4", 14_300));
        facts.onEvent(toolCallEnd("tc-6", "write_file", 14_400));
        facts.onEvent(toolResultEnd("tc-6", "write_file", ToolResultState.SUCCESS, 14_800));

        StageDurations durations = facts.snapshot();
        // 四桶精确值：llm＝父流模型调用（3600）；toolsMs＝按工具名（委派族留观察
        // 面原始账——收口装配侧除名）；commandMs＝install/build/script 归组；源账
        // verify＝其自身配对区间 900+2000+300+400＝3600——旧「首末单窗跨距」口径
        // 会得 5750→10500＝4750（圈入并行父流 1150＝窗内父流占比），回归即被拦
        assertThat(durations.llmMs()).isEqualTo(3_600L);
        assertThat(durations.toolsMs()).containsExactly(
                Map.entry("agent_send", 300L),
                Map.entry("wait_async_results", 4_000L),
                Map.entry("write_file", 400L));
        assertThat(durations.commandMs()).containsExactly(
                Map.entry("install", 3_000L),
                Map.entry("build", 2_000L),
                Map.entry("script", 600L));
        assertThat(durations.subagentMs()).containsExactly(Map.entry("verify", 3_600L));

        // 闭合守卫：closing 口径桶计（toolsMs 除名委派族）＋未归因差值 − 真并行
        // 重叠 ＝ 墙钟跨距（首末事件 0→14800）——差值＝管道空档 900＋agent_send
        // 300＋屏障尾段 3300；真并行重叠＝r-v1⊂r-3（900）＋浏览器=build（2000）。
        // 双计回归（窗口径/委派族回桶）使等式左端膨胀 → 断言失败
        long closingBucketSum = durations.llmMs()
                + durations.toolsMs().get("write_file")
                + durations.commandMs().values().stream().mapToLong(Long::longValue).sum()
                + durations.subagentMs().get("verify");
        long unattributed = 900 + 300 + 3_300;
        long trueParallelOverlap = 900 + 2_000;
        assertThat(closingBucketSum + unattributed - trueParallelOverlap)
                .isEqualTo(14_800L);
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

    @Test
    void given_closed_model_calls_when_queried_then_iterations_counted() {
        // #453 迭代数口径：闭合模型调用区间计数（未闭合/委派源不入账）
        StageDurationFacts facts = new StageDurationFacts();
        facts.onEvent(modelStart("r-1", 0));
        facts.onEvent(modelEnd("r-1", 100));
        facts.onEvent(modelStart("r-2", 200)); // 未闭合——不计

        assertThat(facts.modelCalls()).isEqualTo(1);
    }
}
