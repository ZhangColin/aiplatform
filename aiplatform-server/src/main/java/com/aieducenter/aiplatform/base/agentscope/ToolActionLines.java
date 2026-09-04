package com.aieducenter.aiplatform.base.agentscope;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.ToolCallDeltaEvent;

/**
 * 工具动作 → 人话行单点（直播 live-action 与部件 part-action 的共用生产内核，
 * #77 提取）：工具名封闭表 + 参数增量累积 + 路径提取——平台不加翻译机器。
 * write_file / edit_file 经参数增量（ToolCallDelta）累积解析 path，取文件名去
 * 扩展名为标签 →「编写【标签】」；command →「运行命令」；read_file / grep /
 * glob / list 等读类不播（对客户是噪音）。行文为动作对象短语（无时态——
 * 「编写【订单管理】」），直播侧自带「正在」前缀、部件侧时态由 state 表达。
 */
final class ToolActionLines {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 写文件类工具名（harness 内置编码工具，参数含 path）。 */
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String EDIT_FILE_TOOL = "edit_file";
    private static final String COMMAND_TOOL = "command";

    /** 参数通用标签（解析不出 path / 参数未到达时的兜底）。 */
    private static final String GENERIC_FILE_LABEL = "代码文件";

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
     * 通用标签——动作开始点参数未到达即此形态）；命令「运行命令」；不播工具
     * 返回空。
     *
     * @param consume true = 取走累积参数（调用落定点）；false = 保留（动作开始点，
     *                参数仍在途，后续增量继续累积）
     */
    Optional<String> objectPhrase(String toolName, String toolCallId, boolean consume) {
        if (!broadcastable(toolName)) {
            return Optional.empty();
        }
        if (COMMAND_TOOL.equals(toolName)) {
            if (consume) {
                toolArgs.remove(nvl(toolCallId));
            }
            return Optional.of("运行命令");
        }
        StringBuilder args = consume ? toolArgs.remove(nvl(toolCallId)) : toolArgs.get(nvl(toolCallId));
        return Optional.of("编写【" + pathLabel(args) + "】");
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
