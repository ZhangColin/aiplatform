package com.semgravio.aiplatform.base.agentscope;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;

/**
 * 工具动作 → 人话行单点（部件 part-action 的生产内核）：工具名封闭表 + 参数增量
 * 累积 + 参数提取——平台不加翻译机器。write_file / edit_file 经参数增量
 * （ToolCallDelta）累积解析 path，取文件名去扩展名为标签 →「编写【标签】」；
 * execute（内核 shell，命令直通——#219 透明面化）→ 命令原文首行（剥壳·截断，
 * #228：滚动行成为 claude code / replit 式 live tail）；read_file /
 * grep / glob / list 等读类不播（对客户是噪音）。行文为动作对象短语（无时态
 * ——「编写【订单管理】」，时态由动作部件的 state 表达）；失败留痕原料（工具
 * 结果文本增量，#229）同表累积——终 failed 提取错误/stderr 首行截断进 error 字段。
 */
final class ToolActionLines {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 写文件类工具名（harness 内置编码工具，参数含 path）。 */
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String EDIT_FILE_TOOL = "edit_file";
    /** 命令工具名（harness 内核 shell 注册名）。 */
    private static final String COMMAND_TOOL = "execute";
    /** 参数通用标签（解析不出 path / 参数未到达时的兜底）。 */
    private static final String GENERIC_FILE_LABEL = "代码文件";
    /** 命令通用标签（参数在途 / 解析不出 command 时的兜底）。 */
    private static final String GENERIC_COMMAND_LABEL = "运行命令";
    /**
     * 命令标签定宽（#228 单行定宽截断，含省略号）：事件载荷的长度界——视觉截断
     * 由前端行内样式兜底，走查票校准观感（#232）。
     */
    static final int COMMAND_LABEL_MAX = 80;

    /**
     * 失败留痕定宽（#229 错误/stderr 首行截断，含省略号）：与 {@link #COMMAND_LABEL_MAX}
     * 各管各的额度——错误摘要承载排障信息，略宽于命令原文；视觉截断由前端行内样式
     * 兜底，走查票校准观感（#232）。
     */
    static final int ERROR_LINE_MAX = 120;

    /** 命令壳：`bash -c '…'` / `sh -c "…"`（引号内为壳载荷，引号后余参丢弃）。 */
    private static final Pattern SHELL_WRAP_QUOTED =
            Pattern.compile("^(?:bash|sh)\\s+-c\\s+(['\"])(.*?)\\1.*$");
    /** 命令壳：无引号形态 `bash -c npm test`（无嵌套，剥一层）。 */
    private static final Pattern SHELL_WRAP_BARE =
            Pattern.compile("^(?:bash|sh)\\s+-c\\s+(.+)$");
    /** 工作目录前缀：`cd <dir> && <命令>`（至首个 `&&` 全剥——工作目录是包装噪音）。 */
    private static final Pattern CD_PREFIX = Pattern.compile("cd\\s+.+?&&\\s*");

    private final Map<String, StringBuilder> toolArgs = new HashMap<>();
    /** 工具结果文本增量（失败留痕的原料，#229）：生命周期同参数——终态取走。 */
    private final Map<String, StringBuilder> toolResults = new HashMap<>();

    /** 工具参数增量只累积不出行（行在动作边界点取）。 */
    void onDelta(ToolCallDeltaEvent delta) {
        toolArgs.computeIfAbsent(nvl(delta.getToolCallId()), k -> new StringBuilder())
                .append(nvl(delta.getDelta()));
    }

    /** 工具结果文本增量只累积不出行（失败留痕原料；封闭表外工具不积——读类结果是噪音）。 */
    void onResultText(ToolResultTextDeltaEvent delta) {
        if (!broadcastable(delta.getToolCallName())) {
            return;
        }
        toolResults.computeIfAbsent(nvl(delta.getToolCallId()), k -> new StringBuilder())
                .append(nvl(delta.getDelta()));
    }

    /** 该工具是否播（封闭表成员：写文件类 + 命令；读类不播）。 */
    static boolean broadcastable(String toolName) {
        return WRITE_FILE_TOOL.equals(toolName) || EDIT_FILE_TOOL.equals(toolName)
                || COMMAND_TOOL.equals(toolName);
    }

    /**
     * 动作行（短语 + 写文件类可选目标路径，#618 预览跟随）：label = 人话对象短语
     * （无时态——时态由 state 表达）；path = write_file／edit_file 的 path 参数
     * 原形（未落定/解析不出为 null——可缺省不携带），命令类恒 null。
     */
    record ActionLine(String label, String path) {}

    /**
     * 动作行（无时态）：写文件类「编写【标签】」（args 为空/解析不出 →
     * 通用标签——动作开始点参数未到达即此形态）；命令 = 原文首行（剥壳·截断；
     * 参数在途/解析不出 → 通用标签）；不播工具返回空。写文件类同时带目标路径
     * （#618 预览跟随的路径来源——参数原形，标签仍由文件名派生）。
     *
     * @param consume true = 取走累积参数（终态——动作关闭，参数生命周期结束）；
     *                false = 保留（非终态——参数仍在途或已落定，重算取最新）
     */
    Optional<ActionLine> actionLine(String toolName, String toolCallId, boolean consume) {
        if (!broadcastable(toolName)) {
            return Optional.empty();
        }
        StringBuilder args = takeArgs(toolCallId, consume);
        if (COMMAND_TOOL.equals(toolName)) {
            String label = commandLabel(args);
            return Optional.of(new ActionLine(label != null ? label : GENERIC_COMMAND_LABEL, null));
        }
        JsonNode parsed = parseArgs(args);
        String path = textField(parsed, "path");
        return Optional.of(new ActionLine("编写【" + labelOfPath(path) + "】", path));
    }

    /** 累积参数取值（consume 语义见 {@link #actionLine}）。 */
    private StringBuilder takeArgs(String toolCallId, boolean consume) {
        return consume ? toolArgs.remove(nvl(toolCallId)) : toolArgs.get(nvl(toolCallId));
    }

    /**
     * 失败留痕（#229）：终 failed 取累积结果文本首行（strip·截断至
     * {@link #ERROR_LINE_MAX}）——错误/stderr 的初判线索；终 completed 直接丢弃
     * （结果文本生命周期同参数：终态即取走，非终态挂起重放保留）。无文本 / 首行
     * 空白 → 空（error 字段可缺省）。
     */
    Optional<String> errorPhrase(String toolCallId, boolean failed, boolean consume) {
        StringBuilder result = consume ? toolResults.remove(nvl(toolCallId)) : toolResults.get(nvl(toolCallId));
        if (!failed || result == null || result.isEmpty()) {
            return Optional.empty();
        }
        String line = result.toString().lines().findFirst().map(String::strip).orElse("");
        return line.isBlank() ? Optional.empty() : Optional.of(truncate(line, ERROR_LINE_MAX));
    }

    /** 累积参数 → 命令标签（原文首行·剥壳·定宽截断；解析不出/空白回落 null → 通用标签）。 */
    private static String commandLabel(StringBuilder args) {
        String command = textField(parseArgs(args), "command");
        if (command == null) {
            return null;
        }
        String label = truncate(unwrap(command), COMMAND_LABEL_MAX);
        return label.isBlank() ? null : label;
    }

    /** 累积参数 → JSON（空/不完整/非 JSON → null，走通用形态）。 */
    private static JsonNode parseArgs(StringBuilder args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(args.toString());
        }
        catch (Exception ignored) {
            // 参数流不完整/非 JSON：走通用形态
            return null;
        }
    }

    /** JSON 顶层文本字段（非文本/空白 → null）。 */
    private static String textField(JsonNode parsed, String field) {
        if (parsed == null) {
            return null;
        }
        JsonNode node = parsed.path(field);
        if (!node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }

    /**
     * 命令原文 → 核心命令：首行（多行取一）→ 剥 `bash -c` / `sh -c` 壳（可嵌套）
     * → 剥 `cd … &&` 前缀（可链式）；其余连接形态裸显（不越权改写命令语义）。
     */
    private static String unwrap(String command) {
        String line = command.lines().findFirst().map(String::strip).orElse("");
        Matcher quoted = SHELL_WRAP_QUOTED.matcher(line);
        while (quoted.reset(line).matches()) {
            line = quoted.group(2).strip();
        }
        Matcher bare = SHELL_WRAP_BARE.matcher(line);
        if (bare.matches()) {
            line = bare.group(1).strip();
        }
        Matcher cd = CD_PREFIX.matcher(line);
        while (cd.reset(line).lookingAt()) {
            line = line.substring(cd.end()).strip();
        }
        return line;
    }

    /** 定宽截断（max 含省略号「…」——label / error 各管各的额度）。 */
    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** path → 标签（文件名去扩展名；无路径回落通用标签）。 */
    private static String labelOfPath(String path) {
        if (path == null) {
            return GENERIC_FILE_LABEL;
        }
        String label = labelOf(path);
        return label.isBlank() ? GENERIC_FILE_LABEL : label;
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

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
