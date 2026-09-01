package com.aieducenter.aiplatform.base.agentscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * 直播映射表单点（#23 生成环②）：AgentScope 事件 → 平台直播帧（词汇正本 = eventhub
 * {@link AgentEventTypes} 直播组；面向客户的解说广播，前端直播侧栏唯一消费面——不
 * 耦合引擎透传格式）。仅编码 run（生成/修正）经 {@link AgentCommand#live()} opt-in
 * 挂载，BA 对话不流式不留痕。
 *
 * <p><b>解说生产</b>（CONTEXT.md「直播」）：智能体自述为主（文本增量逐段成型）+
 * 工具动作人话模板兜底；思考（reasoning）与读类工具不播。段切分（直播逐段流）：</p>
 * <ul>
 *   <li>句读（。！？；…换行及西文对应符号）落定即出段；</li>
 *   <li>文本块变（blockId 变化）、步骤边界、动作边界先出余段——段与段有序不串；</li>
 *   <li>长度上限切段（时延有界，不等句读）；run 收尾 {@link #flush} 出尾段（幂等）。</li>
 * </ul>
 *
 * <p><b>动作模板</b>（工具名封闭表 + 路径提取，平台不加翻译机器）：write_file /
 * edit_file 经工具参数增量（ToolCallDelta）累积解析 path，取文件名去扩展名为标签
 * →「正在编写【标签】」（解析不出 → 通用话术）；command →「正在运行命令」；
 * read_file / grep / glob / list 等读类不播（对客户是噪音）。</p>
 *
 * <table border="1">
 *   <caption>AgentScope 事件 → 直播帧</caption>
 *   <tr><th>AgentScope 事件</th><th>直播 type</th><th>段字段</th></tr>
 *   <tr><td>TextBlockDelta（累积切段）</td><td>{@code live-text}</td><td>text（完整段）</td>
 *   <tr><td>ToolCallEnd（write_file/edit_file/command）</td><td>{@code live-action}</td><td>action（人话行）</td>
 *   <tr><td>ModelCallStart（步骤计数）</td><td>{@code live-step}</td><td>step（1 起序号）</td>
 * </table>
 */
final class AgentscopeLiveMapper {

    /** 单段长度上限（字符）：超限即出段，直播时延有界。 */
    private static final int MAX_SEGMENT_LENGTH = 160;

    /** 句读符（段切分依据；中西文并列收全）。 */
    private static final String SENTENCE_ENDERS = "。！？；…!?;\n";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 写文件类工具名（harness 内置编码工具，参数含 path）。 */
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String EDIT_FILE_TOOL = "edit_file";
    private static final String COMMAND_TOOL = "command";

    private final String runId;
    private final String sessionId;
    private final String engine;

    private final StringBuilder text = new StringBuilder();
    private String blockId = "";
    private int step;
    private final Map<String, StringBuilder> toolArgs = new HashMap<>();

    AgentscopeLiveMapper(String runId, String sessionId, String engine) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.engine = engine;
    }

    /** 过程事件 → 直播帧（0..n：边界事件可先带一帧余段再出自身帧）；未映射类型产空。 */
    List<AgentEvent> map(io.agentscope.core.event.AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return mapTextDelta(delta);
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            // 工具参数增量只累积不产帧（动作行在调用落定点出）
            toolArgs.computeIfAbsent(nvl(delta.getToolCallId()), k -> new StringBuilder())
                    .append(nvl(delta.getDelta()));
            return List.of();
        }
        List<AgentEvent> frames = new ArrayList<>();
        if (event instanceof ModelCallStartEvent) {
            flushText(frames);
            step += 1;
            frames.add(frame(AgentEventTypes.LIVE_STEP,
                    AgentEventTypes.LIVE_STEP_FIELD, step));
            return frames;
        }
        if (event instanceof ToolCallEndEvent end) {
            flushText(frames);
            actionLine(nvl(end.getToolCallName()),
                    toolArgs.remove(nvl(end.getToolCallId()))).ifPresent(line ->
                    frames.add(frame(AgentEventTypes.LIVE_ACTION,
                            AgentEventTypes.LIVE_ACTION_FIELD, line)));
            return frames;
        }
        // 其余事件（思考/读类工具/块尾/挂起等）：不产帧不切段
        return List.of();
    }

    /** run 收尾（run-finish / error / 挂起前）：逐句出已落定段，余下无句读尾整段出（幂等）。 */
    List<AgentEvent> flush() {
        List<AgentEvent> frames = new ArrayList<>();
        flushSettledSentences(frames);
        flushText(frames);
        return frames;
    }

    // ---------- 内部 ----------

    private List<AgentEvent> mapTextDelta(TextBlockDeltaEvent delta) {
        String deltaBlock = nvl(delta.getBlockId());
        List<AgentEvent> frames = new ArrayList<>();
        if (!deltaBlock.equals(blockId)) {
            // 块变即段边界：先出上一块余段
            flushText(frames);
            blockId = deltaBlock;
        }
        text.append(nvl(delta.getDelta()));
        // 句读落定即逐句出段（含句读即出，不要求恰好收尾在句读上）
        flushSettledSentences(frames);
        if (text.length() >= MAX_SEGMENT_LENGTH) {
            // 超长无句读残留（长句）：硬切整段，时延有界
            flushText(frames);
        }
        return frames;
    }

    /**
     * 出余段——长句硬切整段（超长无句读时的时延兜底；空白不出段）。
     */
    private void flushText(List<AgentEvent> frames) {
        if (text.length() == 0 || text.toString().isBlank()) {
            text.setLength(0);
            return;
        }
        frames.add(frame(AgentEventTypes.LIVE_TEXT,
                AgentEventTypes.LIVE_TEXT_FIELD, text.toString()));
        text.setLength(0);
    }

    /**
     * 逐句出段：每个句读符（含）各成一段（一次到达的长增量含多句时不憋整块——
     * 「句读落定即出段」对粗粒度增量同样成立）；无句读的尾部留在缓冲等下一边界
     * 或收尾 flush。
     */
    private void flushSettledSentences(List<AgentEvent> frames) {
        int start = 0;
        int settled = 0;
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDERS.indexOf(text.charAt(i)) >= 0) {
                String sentence = text.substring(start, i + 1);
                if (!sentence.isBlank()) {
                    frames.add(frame(AgentEventTypes.LIVE_TEXT,
                            AgentEventTypes.LIVE_TEXT_FIELD, sentence));
                }
                start = i + 1;
                settled = start;
            }
        }
        if (settled > 0) {
            text.delete(0, settled);
        }
    }

    /** 工具动作 → 人话行（读类不播返回空）。 */
    private static Optional<String> actionLine(String toolName, StringBuilder args) {
        if (WRITE_FILE_TOOL.equals(toolName) || EDIT_FILE_TOOL.equals(toolName)) {
            return Optional.of("正在编写【" + pathLabel(args) + "】");
        }
        if (COMMAND_TOOL.equals(toolName)) {
            return Optional.of("正在运行命令");
        }
        return Optional.empty();
    }

    /** 累积参数 → path 标签（文件名去扩展名；解析不出回落通用话术）。 */
    private static String pathLabel(StringBuilder args) {
        String label = null;
        if (args != null && !args.isEmpty()) {
            try {
                JsonNode path = JSON.readTree(args.toString()).path("path");
                if (path.isTextual()) {
                    label = labelOf(path.asText());
                }
            }
            catch (Exception ignored) {
                // 参数流不完整/非 JSON：走通用话术
            }
        }
        return label != null && !label.isBlank() ? label : "代码文件";
    }

    /** 路径 → 文件名去扩展名（标签）：取末段、剥最后一个扩展名与点分隐藏前缀。 */
    private static String labelOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.trim();
    }

    private AgentEvent frame(String type, String field, Object value) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(AgentEventTypes.RUN_FIELD, runId);
        payload.put(AgentEventTypes.SESSION_FIELD, sessionId);
        payload.put(AgentEventTypes.ROLE_ENGINE_FIELD, engine);
        payload.put(field, value);
        return new AgentEvent(type, payload);
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
