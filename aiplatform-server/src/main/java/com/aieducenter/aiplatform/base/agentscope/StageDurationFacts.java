package com.aieducenter.aiplatform.base.agentscope;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;

/**
 * 引擎事件流 → 阶段耗时事实（#111 收口扩载 durationBreakdown 的观察面，
 * {@link FileChangeFacts} 同族：事实观察，非呈现）：执行体的模型调用与工具执行
 * 逐事件掐表（计时源 = 引擎事件 {@code createdAt}——同进程引擎事件的产生时刻，
 * 服务端真实口径，无映射管道迟滞；解析不出即不计，同「不可观测即不计」口径），
 * 实例随流段生命周期（converse/resume 各一枚），收口侧经
 * {@link AgentReply#durations()} 携出、跨段 {@code plus} 合并。
 *
 * <p><b>口径</b>：① 只计配对闭合区间（模型调用按 replyId、工具执行按
 * toolCallId）——流中段崩断的未闭合区间丢弃（尝试失败账由 run 尝试环的尝试墙钟
 * 承载）；② 工具执行窗 = callEnd（参数落定）→ resultEnd（结果落定，含失败态——
 * 失败的执行照样耗时），参数在途的模型生成时间归 LLM 桶；③ command 工具不进
 * toolsMs 平铺——命令文本（参数增量累积解析）按命令归组进 commandMs；④ 带
 * source 的委派事件（子智能体转发进父流）不进执行体桶，只记委派窗（首末 source
 * 事件跨距——窗内细节即子智能体账，归属哪个桶的判定归收口装配侧）。</p>
 *
 * <p><b>已知洞</b>：权限挂起的工具调用跨流段（挂起段 callEnd、续跑段
 * resultEnd）若续跑不重放调用边界则配对不上——破坏性命令确认是例外路径，洞承认
 * （时间落「未归因差值」）。</p>
 */
final class StageDurationFacts {

    /** 命令归组·依赖安装（closing durationBreakdown 的 command 桶键，SSE事件清单同载）。 */
    static final String GROUP_INSTALL = "install";

    /** 命令归组·dev server（起服/常驻类命令）。 */
    static final String GROUP_DEV = "dev";

    /** 命令归组·测试。 */
    static final String GROUP_TEST = "test";

    /** 命令归组·其他（兜底）。 */
    static final String GROUP_OTHER = "other";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 命令工具名（与 {@link ToolActionLines} 播报表的命令面同值）。 */
    private static final String COMMAND_TOOL = "command";

    /**
     * 归组形态（宁粗勿细的首版——依赖安装 / dev server / 测试三族的常见形态命中，
     * 其余兜底 other）：复合命令按段（&&/;/| 与换行切分）左到右、段内
     * install > dev > test 首个命中定组。
     */
    private static final Pattern INSTALL = Pattern.compile(
            "\\b(npm|pnpm|yarn|bun)\\s+(install|i|ci|add)\\b"
                    + "|\\b(apt|apt-get)\\s+install\\b"
                    + "|\\bpip3?\\s+install\\b");
    private static final Pattern DEV = Pattern.compile(
            "\\b(npm|pnpm|yarn|bun)\\s+(run\\s+)?(dev|start|serve)\\b"
                    + "|\\b(vite|nodemon)\\b"
                    + "|\\bnext\\s+dev\\b");
    private static final Pattern TEST = Pattern.compile(
            "\\b(npm|pnpm|yarn|bun)\\s+(run\\s+)?(test|check)\\b"
                    + "|\\b(vitest|jest|pytest)\\b"
                    + "|\\bplaywright\\s+test\\b");

    /** 复合命令的段切分（&&/;/| 与换行）。 */
    private static final Pattern SEGMENTS = Pattern.compile("[;&|\\n]+");

    private long llmMs;
    private final Map<String, Long> toolsMs = new LinkedHashMap<>();
    private final Map<String, Long> commandMs = new LinkedHashMap<>();
    /** 委派窗（source → [首事件 ms, 末事件 ms]）；快照时折算跨距。 */
    private final Map<String, long[]> subagentWindows = new HashMap<>();

    /** 模型调用挂账（replyId → 起点 ms）。 */
    private final Map<String, Long> modelStarts = new HashMap<>();
    /** 工具执行挂账（toolCallId → 执行起点）。 */
    private final Map<String, PendingTool> toolStarts = new HashMap<>();
    /** 命令工具的参数增量累积（toolCallId → 累积串；callEnd 点取走解析）。 */
    private final Map<String, StringBuilder> commandArgs = new HashMap<>();

    /** 工具执行挂账（callEnd 点）：工具名 + 起点 + 命令归组（非命令工具为 null）。 */
    private record PendingTool(String toolName, long startedMs, String commandGroup) {
    }

    /**
     * 全事件单入口（掐表点 = 事件产生时刻）：委派事件记委派窗后不并账（窗内细节
     * 即子智能体账），执行体事件进模型/工具配对。
     */
    void onEvent(AgentEvent event) {
        Long at = epochMilli(event);
        if (at == null) {
            return;
        }
        String source = AgentscopeEventMapper.sourceOf(event);
        if (source != null) {
            long[] window = subagentWindows.computeIfAbsent(source, key -> new long[]{at, at});
            window[1] = at;
            return;
        }
        if (event instanceof ModelCallStartEvent start) {
            modelStarts.put(nvl(start.getReplyId()), at);
        }
        else if (event instanceof ModelCallEndEvent end) {
            Long started = modelStarts.remove(nvl(end.getReplyId()));
            if (started != null) {
                llmMs += at - started;
            }
        }
        else if (event instanceof ToolCallDeltaEvent delta) {
            // 命令工具的参数增量只累积不出账（命令文本在 callEnd 点取——归组依据）
            if (COMMAND_TOOL.equals(delta.getToolCallName())) {
                commandArgs.computeIfAbsent(nvl(delta.getToolCallId()), key -> new StringBuilder())
                        .append(nvl(delta.getDelta()));
            }
        }
        else if (event instanceof ToolCallEndEvent end) {
            StringBuilder args = commandArgs.remove(nvl(end.getToolCallId()));
            toolStarts.put(nvl(end.getToolCallId()), new PendingTool(nvl(end.getToolCallName()),
                    at, COMMAND_TOOL.equals(end.getToolCallName()) ? commandGroup(args) : null));
        }
        else if (event instanceof ToolResultEndEvent end) {
            PendingTool pending = toolStarts.remove(nvl(end.getToolCallId()));
            if (pending == null) {
                return;
            }
            long span = at - pending.startedMs();
            if (pending.commandGroup() != null) {
                commandMs.merge(pending.commandGroup(), span, Long::sum);
            }
            else {
                toolsMs.merge(pending.toolName(), span, Long::sum);
            }
        }
    }

    /** 本流段的阶段耗时不定形快照（未闭合区间丢弃——只计完整观察）。 */
    StageDurations snapshot() {
        Map<String, Long> windows = new LinkedHashMap<>();
        subagentWindows.forEach((source, window) -> windows.put(source, window[1] - window[0]));
        return new StageDurations(llmMs, toolsMs, commandMs, windows);
    }

    /** 命令工具累积参数 → 归组（command 字段解析不出 → 其他——不可观测即兜底）。 */
    private static String commandGroup(StringBuilder argsJson) {
        if (argsJson == null || argsJson.isEmpty()) {
            return GROUP_OTHER;
        }
        try {
            JsonNode command = JSON.readTree(argsJson.toString()).path("command");
            return command.isTextual() ? classify(command.asText()) : GROUP_OTHER;
        }
        catch (Exception ignored) {
            return GROUP_OTHER;
        }
    }

    /**
     * 命令文本归组（粗分组首版）：复合命令按段左到右、段内 install > dev > test
     * 首个命中定组（「cd x && npm install」归 install）；全不命中兜底 other。
     */
    static String classify(String command) {
        if (command == null || command.isBlank()) {
            return GROUP_OTHER;
        }
        for (String segment : SEGMENTS.split(command)) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (INSTALL.matcher(trimmed).find()) {
                return GROUP_INSTALL;
            }
            if (DEV.matcher(trimmed).find()) {
                return GROUP_DEV;
            }
            if (TEST.matcher(trimmed).find()) {
                return GROUP_TEST;
            }
        }
        return GROUP_OTHER;
    }

    /** 事件产生时刻（createdAt ISO-8601）；解析不出返回 null（不可观测即不计）。 */
    private static Long epochMilli(AgentEvent event) {
        try {
            return Instant.parse(event.getCreatedAt()).toEpochMilli();
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
