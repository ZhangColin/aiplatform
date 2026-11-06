package com.semgravio.aiplatform.base.agentscope;

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
 * 失败的执行照样耗时），参数在途的模型生成时间归 LLM 桶；③ 命令工具（内核
 * shell）不进 toolsMs 平铺——命令文本（参数增量累积解析）按命令归组进
 * commandMs；④ 带 source 的委派事件（子智能体转发进父流）不进执行体桶，按源
 * 归集该源自身的模型调用与工具执行配对累计（#417 弃「首末单窗跨距」口径——
 * 单窗把窗内并行父流的 LLM 时间圈进验证桶，重叠双计；源内区间之间的空档归
 * 未归因差值）。</p>
 */
final class StageDurationFacts {

    /** 命令归组·依赖安装（closing durationBreakdown 的 command 桶键，SSE事件清单同载）。 */
    static final String GROUP_INSTALL = "install";

    /** 命令归组·构建（#417——build/tsc 等构建族，先于 dev 判定防「vite build」误入 dev）。 */
    static final String GROUP_BUILD = "build";

    /** 命令归组·dev server（起服/常驻类命令）。 */
    static final String GROUP_DEV = "dev";

    /** 命令归组·测试。 */
    static final String GROUP_TEST = "test";

    /** 命令归组·数据库客户端（psql 等直连操作）。 */
    static final String GROUP_DB = "db";

    /** 命令归组·HTTP 探测（curl/wget）。 */
    static final String GROUP_HTTP = "http";

    /** 命令归组·一次性脚本运行（node/npx 兜族——未被前族命中的 runner 形态）。 */
    static final String GROUP_SCRIPT = "script";

    /** 命令归组·其他（兜底）。 */
    static final String GROUP_OTHER = "other";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 命令工具名（与 {@link ToolActionLines} 播报表的命令面同值：内核 shell 注册名）。 */
    private static final String COMMAND_TOOL = "execute";

    /**
     * 归组形态（#417 扩族——生产实测 command.other 吞掉全部命令耗时后的修真：
     * install / build / dev / test / db / http / script 七族常见形态命中，其余
     * 兜底 other）：复合命令按段（&&/;/| 与换行切分）左到右、段内
     * install > build > dev > test > db > http > script 首个命中定组。
     */
    private static final Pattern INSTALL = Pattern.compile(
            "\\b(npm|pnpm|yarn|bun)\\s+(install|i|ci|add)\\b"
                    + "|\\b(apt|apt-get)\\s+install\\b"
                    + "|\\bpip3?\\s+install\\b");
    private static final Pattern BUILD = Pattern.compile(
            "\\b(npm|pnpm|yarn|bun)\\s+(run\\s+)?build\\b"
                    + "|\\b(next|vite|nest|nuxt)\\s+build\\b"
                    + "|\\btsc\\b");
    private static final Pattern DEV = Pattern.compile(
            "\\b(npm|pnpm|yarn|bun)\\s+(run\\s+)?(dev|start|serve)\\b"
                    + "|\\b(vite|nodemon)\\b"
                    + "|\\bnext\\s+dev\\b");
    private static final Pattern TEST = Pattern.compile(
            "\\b(npm|pnpm|yarn|bun)\\s+(run\\s+)?(test|check)\\b"
                    + "|\\b(vitest|jest|pytest)\\b"
                    + "|\\bplaywright\\s+test\\b");
    private static final Pattern DB = Pattern.compile(
            "\\bpsql\\b|\\bpg_dump\\b|\\bpg_restore\\b|\\bredis-cli\\b");
    private static final Pattern HTTP = Pattern.compile(
            "\\bcurl\\b|\\bwget\\b");
    private static final Pattern SCRIPT = Pattern.compile(
            "\\bnode\\b|\\bnpx\\b");

    /** 复合命令的段切分（&&/;/| 与换行）。 */
    private static final Pattern SEGMENTS = Pattern.compile("[;&|\\n]+");

    private long llmMs;
    private final Map<String, Long> toolsMs = new LinkedHashMap<>();
    private final Map<String, Long> commandMs = new LinkedHashMap<>();
    /** 委派源账（source → 该源模型调用＋工具执行配对累计；首见序插入、无闭合区间为 0）。 */
    private final Map<String, Long> subagentAccounts = new LinkedHashMap<>();
    /** 源内模型调用挂账（source → replyId → 起点 ms）。 */
    private final Map<String, Map<String, Long>> subagentModelStarts = new HashMap<>();
    /** 源内工具执行挂账（source → toolCallId → 执行起点）。 */
    private final Map<String, Map<String, Long>> subagentToolStarts = new HashMap<>();

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
     * 全事件单入口（掐表点 = 事件产生时刻）：委派事件按源归集该源自身的配对区间
     * （不进执行体桶——窗内细节即子智能体账），执行体事件进模型/工具配对。
     */
    void onEvent(AgentEvent event) {
        Long at = epochMilli(event);
        if (at == null) {
            return;
        }
        String source = AgentscopeEventMapper.sourceOf(event);
        if (source != null) {
            onSourcedEvent(source, event, at);
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
        return new StageDurations(llmMs, toolsMs, commandMs, subagentAccounts);
    }

    /**
     * 委派源事件归集（#417 源账口径）：该源的模型调用与工具执行各自配对、闭合时
     * 累计进源账——与执行体配对同律（只计闭合区间），并行父流的事件不在此账。
     * 其余源事件（文本增量、参数增量等）只登记源首见、不产生账。
     */
    private void onSourcedEvent(String source, AgentEvent event, long at) {
        subagentAccounts.putIfAbsent(source, 0L);
        if (event instanceof ModelCallStartEvent start) {
            subagentModelStarts.computeIfAbsent(source, key -> new HashMap<>())
                    .put(nvl(start.getReplyId()), at);
        }
        else if (event instanceof ModelCallEndEvent end) {
            Long started = removeStart(subagentModelStarts, source, nvl(end.getReplyId()));
            if (started != null) {
                subagentAccounts.merge(source, at - started, Long::sum);
            }
        }
        else if (event instanceof ToolCallEndEvent end) {
            subagentToolStarts.computeIfAbsent(source, key -> new HashMap<>())
                    .put(nvl(end.getToolCallId()), at);
        }
        else if (event instanceof ToolResultEndEvent end) {
            Long started = removeStart(subagentToolStarts, source, nvl(end.getToolCallId()));
            if (started != null) {
                subagentAccounts.merge(source, at - started, Long::sum);
            }
        }
    }

    /** 源内挂账取走（无该源或该键 → null——未配对即不计）。 */
    private static Long removeStart(Map<String, Map<String, Long>> starts, String source,
            String key) {
        Map<String, Long> pending = starts.get(source);
        return pending != null ? pending.remove(key) : null;
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
     * 命令文本归组（#417 七族）：复合命令按段左到右、段内 install > build > dev
     * > test > db > http > script 首个命中定组（「cd x && npm install」归 install、
     * 「vite build」归 build）；全不命中兜底 other。
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
            if (BUILD.matcher(trimmed).find()) {
                return GROUP_BUILD;
            }
            if (DEV.matcher(trimmed).find()) {
                return GROUP_DEV;
            }
            if (TEST.matcher(trimmed).find()) {
                return GROUP_TEST;
            }
            if (DB.matcher(trimmed).find()) {
                return GROUP_DB;
            }
            if (HTTP.matcher(trimmed).find()) {
                return GROUP_HTTP;
            }
            if (SCRIPT.matcher(trimmed).find()) {
                return GROUP_SCRIPT;
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
