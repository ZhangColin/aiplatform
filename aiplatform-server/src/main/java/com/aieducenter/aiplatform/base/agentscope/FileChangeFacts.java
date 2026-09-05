package com.aieducenter.aiplatform.base.agentscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.ToolCallDeltaEvent;

/**
 * 工具写动作 → 文件变更事实（#88 收口扩载）：write_file / edit_file 的参数增量
 * 自行累积（command / 读类不含文件面不收），调用落定解析 path 与行数挂账，工具
 * <b>结果成功才提交</b>——失败/被拒的写不是变更。观察源 = 平台可观测的工具调用
 * 事实（判定与清单不由模型自报）；实例随流段生命周期（converse/resume 各一枚），
 * 收口侧经 {@link AgentReply#changes()} 携出。
 *
 * <p>已知口径（契约文档同载）：edit 的 replace_all 多次命中按一次计（事件面无
 * 命中数）；命令行改造的文件（脚手架 CLI 等）不进清单——真 diff 归版本层
 * （#91 容器 git）收口对比。</p>
 */
final class FileChangeFacts {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 写文件类工具名（与 {@link ToolActionLines} 播报表的文件面同集）。 */
    private static final String WRITE_FILE_TOOL = "write_file";
    private static final String EDIT_FILE_TOOL = "edit_file";

    private final Map<String, StringBuilder> args = new HashMap<>();
    /** 调用落定的挂账（toolCallId → 解析出的变更，待结果落定裁决）。 */
    private final Map<String, FileChange> pending = new HashMap<>();
    private final List<FileChange> committed = new ArrayList<>();

    /** 参数增量只累积写文件类（其余工具的参数与文件面无关）。 */
    void onDelta(ToolCallDeltaEvent delta) {
        if (!fileTool(delta.getToolCallName())) {
            return;
        }
        args.computeIfAbsent(nvl(delta.getToolCallId()), k -> new StringBuilder())
                .append(nvl(delta.getDelta()));
    }

    /** 调用落定（参数完整）：解析 path 与行数挂账；解析不出即无此变更。 */
    void onCallEnd(String toolName, String toolCallId) {
        if (!fileTool(toolName)) {
            return;
        }
        StringBuilder buffered = args.remove(nvl(toolCallId));
        FileChange change = buffered != null ? parse(toolName, buffered.toString()) : null;
        if (change != null) {
            pending.put(nvl(toolCallId), change);
        }
    }

    /** 结果落定：成功提交挂账变更；其余态（失败/被拒/中断/仍在跑）丢弃或保持挂账。 */
    void onResultEnd(String toolCallId, boolean succeeded) {
        FileChange change = pending.remove(nvl(toolCallId));
        if (change != null && succeeded) {
            committed.add(change);
        }
    }

    /** 本流段已提交的文件变更（逐次保序；同路径跨调用合并在收口拼装层）。 */
    List<FileChange> changes() {
        return List.copyOf(committed);
    }

    // ---------- 内部 ----------

    private static boolean fileTool(String toolName) {
        return WRITE_FILE_TOOL.equals(toolName) || EDIT_FILE_TOOL.equals(toolName);
    }

    /** 完整参数 → 变更事实：write 按新文件行数、edit 按 old/new 行数；非法即 null。 */
    private static FileChange parse(String toolName, String argsJson) {
        try {
            JsonNode root = JSON.readTree(argsJson);
            String path = root.path("path").asText(null);
            if (path == null || path.isBlank()) {
                return null;
            }
            if (WRITE_FILE_TOOL.equals(toolName)) {
                return new FileChange(path, lineCount(root.path("content").asText("")), 0);
            }
            return new FileChange(path,
                    lineCount(root.path("new_string").asText("")),
                    lineCount(root.path("old_string").asText("")));
        }
        catch (Exception ignored) {
            // 参数流不完整/非 JSON：不可观测即不计
            return null;
        }
    }

    /** 行数：非空文本按换行分段计（尾行无换行也计一行；空串 = 0）。 */
    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int idx = text.indexOf('\n'); idx >= 0; idx = text.indexOf('\n', idx + 1)) {
            lines += 1;
        }
        return lines;
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
