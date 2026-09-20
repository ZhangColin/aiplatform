package com.aieducenter.aiplatform.base.agentscope;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.ToolCallDeltaEvent;

/**
 * 工具动作 → 人话行单点（部件 part-action 的生产内核）：工具名封闭表 + 参数增量
 * 累积 + 参数提取——平台不加翻译机器。write_file / edit_file 经参数增量
 * （ToolCallDelta）累积解析 path，取文件名去扩展名为标签 →「编写【标签】」；
 * execute（内核 shell，命令直通——#219 透明面化）→ 命令原文首行（剥壳·截断，
 * #228：滚动行成为 claude code / replit 式 live tail）；read_file / grep / glob /
 * list 等读类不播（对客户是噪音）。行文为动作对象短语（无时态——「编写【订单
 * 管理】」，时态由动作部件的 state 表达）。
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

    /** 命令壳：`bash -c '…'` / `sh -c "…"`（引号内为壳载荷，引号后余参丢弃）。 */
    private static final Pattern SHELL_WRAP_QUOTED =
            Pattern.compile("^(?:bash|sh)\\s+-c\\s+(['\"])(.*?)\\1.*$");
    /** 命令壳：无引号形态 `bash -c npm test`（无嵌套，剥一层）。 */
    private static final Pattern SHELL_WRAP_BARE =
            Pattern.compile("^(?:bash|sh)\\s+-c\\s+(.+)$");
    /** 工作目录前缀：`cd <dir> && <命令>`（至首个 `&&` 全剥——工作目录是包装噪音）。 */
    private static final Pattern CD_PREFIX = Pattern.compile("cd\\s+.+?&&\\s*");

    private final Map<String, StringBuilder> toolArgs = new HashMap<>();

    /** 工具参数增量只累积不出行（行在动作边界点取）。 */
    void onDelta(ToolCallDeltaEvent delta) {
        toolArgs.computeIfAbsent(nvl(delta.getToolCallId()), k -> new StringBuilder())
                .append(nvl(delta.getDelta()));
    }

    /** 该工具是否播（封闭表成员：写文件类 + 命令；读类不播）。 */
    static boolean broadcastable(String toolName) {
        return WRITE_FILE_TOOL.equals(toolName) || EDIT_FILE_TOOL.equals(toolName)
                || COMMAND_TOOL.equals(toolName);
    }

    /**
     * 动作对象短语（无时态）：写文件类「编写【标签】」（args 为空/解析不出 →
     * 通用标签——动作开始点参数未到达即此形态）；命令 = 原文首行（剥壳·截断；
     * 参数在途/解析不出 → 通用标签）；不播工具返回空。
     *
     * @param consume true = 取走累积参数（终态——动作关闭，参数生命周期结束）；
     *                false = 保留（非终态——参数仍在途或已落定，重算取最新）
     */
    Optional<String> objectPhrase(String toolName, String toolCallId, boolean consume) {
        if (!broadcastable(toolName)) {
            return Optional.empty();
        }
        StringBuilder args = takeArgs(toolCallId, consume);
        if (COMMAND_TOOL.equals(toolName)) {
            String label = commandLabel(args);
            return Optional.of(label != null ? label : GENERIC_COMMAND_LABEL);
        }
        return Optional.of("编写【" + pathLabel(args) + "】");
    }

    /** 累积参数取值（consume 语义见 {@link #objectPhrase}）。 */
    private StringBuilder takeArgs(String toolCallId, boolean consume) {
        return consume ? toolArgs.remove(nvl(toolCallId)) : toolArgs.get(nvl(toolCallId));
    }

    /** 累积参数 → 命令标签（原文首行·剥壳·定宽截断；解析不出/空白回落 null → 通用标签）。 */
    private static String commandLabel(StringBuilder args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            JsonNode command = JSON.readTree(args.toString()).path("command");
            if (!command.isTextual()) {
                return null;
            }
            String label = truncate(unwrap(command.asText()));
            return label.isBlank() ? null : label;
        }
        catch (Exception ignored) {
            // 参数流不完整/非 JSON：走通用标签
            return null;
        }
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

    /** 定宽截断（{@link #COMMAND_LABEL_MAX} 含省略号「…」）。 */
    private static String truncate(String label) {
        return label.length() <= COMMAND_LABEL_MAX ? label : label.substring(0, COMMAND_LABEL_MAX - 1) + "…";
    }

    /** 累积参数 → path 标签（文件名去扩展名；解析不出回落通用标签）。 */
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
                // 参数流不完整/非 JSON：走通用标签
            }
        }
        return label != null && !label.isBlank() ? label : GENERIC_FILE_LABEL;
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
