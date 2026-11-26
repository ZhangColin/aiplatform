package com.semgravio.aiplatform.base.agentscope;

import java.util.Map;
import java.util.function.Supplier;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 内核四件工具（{@code write_file}／{@code read_file}／{@code edit_file}／{@code bash}）
 * 的共同调用日志缝（#634）：每次工具调用恰好一行服务端日志，供运营发现新坑时查证；
 * 不改用户面、不加 SSE、不落库。
 *
 * <p>字段＝工具名、目标摘要（文件路径；bash 命令前 120 字；read 的 offset／limit）、
 * 结果（ok／error）、耗时，失败时附错误或 stderr 首行（截断）。安全口径：不记
 * {@code write_file} 正文、{@code read_file} 文件内容、完整工具输出、{@code .env} 值；
 * 命令原文按固定长度截断；所有摘要压成单行，保证「恰好一行日志」。</p>
 *
 * <p>日志写入失败只吞掉并继续（{@link #record} 的 {@code catch (Throwable)}），不反噬
 * 生成——日志组件出错时工具结果照常返回。</p>
 */
final class KernelToolCallLog {

    private static final Logger logger = LoggerFactory.getLogger(KernelToolCallLog.class);

    /** bash 命令摘要上限（#634 规格：命令前 120 字）。 */
    private static final int COMMAND_MAX = 120;
    /** 失败摘要上限（#634 规格：错误或 stderr 首行截断）。 */
    private static final int ERROR_MAX = 200;
    /** 路径摘要上限（防御性；正常工作区路径远短于此）。 */
    private static final int PATH_MAX = 300;
    /** bash 错误块里 stdout 与 stderr 的分隔标记（{@link KernelTools} 的 bash 输出形态）。 */
    private static final String STDERR_MARKER = "--stderr--";

    private KernelToolCallLog() {
    }

    /**
     * 工具调用执行点：跑工具体、计耗时、落一行日志，异常照原样上抛（先落失败行）。
     * 四件工具的 {@code callAsync} 都经由此处。
     */
    static Mono<ToolResultBlock> call(String toolName, ToolCallParam param,
            Supplier<ToolResultBlock> body) {
        long startedNanos = System.nanoTime();
        ToolResultBlock block;
        try {
            block = body.get();
        }
        catch (RuntimeException e) {
            record(toolName, targetOf(toolName, param), "error", startedNanos,
                    firstLine(e.getMessage(), ERROR_MAX));
            throw e;
        }
        boolean error = block != null && block.getState() == ToolResultState.ERROR;
        record(toolName, targetOf(toolName, param), error ? "error" : "ok", startedNanos,
                error ? errorSummary(toolName, block) : null);
        return Mono.just(block);
    }

    /** 落一行日志；任何日志面故障都吞掉（AC：日志写入异常不反噬生成）。 */
    private static void record(String toolName, String target, String result, long startedNanos,
            String error) {
        try {
            long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            if (error == null) {
                logger.info("kernel tool call tool={} target={} result={} durationMs={}",
                        toolName, target, result, durationMs);
            }
            else {
                logger.info("kernel tool call tool={} target={} result={} durationMs={} error={}",
                        toolName, target, result, durationMs, error);
            }
        }
        catch (Throwable ignored) {
            // 日志面故障不反噬工具调用（#634）
        }
    }

    /** 目标摘要：按工具取路径／路径＋offset／limit／截断命令；压成单行。 */
    private static String targetOf(String toolName, ToolCallParam param) {
        Map<String, Object> input = param != null && param.getInput() != null
                ? param.getInput()
                : Map.of();
        return switch (toolName) {
            case "write_file", "edit_file" -> truncated(oneLine(value(input.get("path"))),
                    PATH_MAX, "(no path)");
            case "read_file" -> readTarget(input);
            case "bash" -> truncated(oneLine(value(input.get("command"))),
                    COMMAND_MAX, "(no command)");
            default -> "";
        };
    }

    /** read 摘要＝路径＋可选 offset／limit（#634 规格）。 */
    private static String readTarget(Map<String, Object> input) {
        StringBuilder target = new StringBuilder(
                truncated(oneLine(value(input.get("path"))), PATH_MAX, "(no path)"));
        appendIfPresent(target, "offset", input.get("offset"));
        appendIfPresent(target, "limit", input.get("limit"));
        return target.toString();
    }

    private static void appendIfPresent(StringBuilder target, String key, Object value) {
        if (value != null) {
            target.append(' ').append(key).append('=').append(oneLine(String.valueOf(value)));
        }
    }

    /**
     * 失败摘要：bash 优先 stderr 首行（错误块为 stdout＋{@code --stderr--}＋stderr），
     * 无 stderr 或其它工具取错误文本首行；均截断。
     */
    private static String errorSummary(String toolName, ToolResultBlock block) {
        String text = textOf(block);
        return "bash".equals(toolName) ? stderrFirstLine(text) : firstLine(text, ERROR_MAX);
    }

    /** 错误块里 stderr 段的首行；没有 stderr 段或为空时回落整块首行。 */
    private static String stderrFirstLine(String text) {
        if (text == null) {
            return null;
        }
        String marker = "\n" + STDERR_MARKER + "\n";
        int at = text.lastIndexOf(marker);
        if (at >= 0) {
            String stderr = text.substring(at + marker.length());
            if (!stderr.isBlank()) {
                return firstLine(stderr.strip(), ERROR_MAX);
            }
        }
        return firstLine(text, ERROR_MAX);
    }

    private static String firstLine(String text, int max) {
        if (text == null) {
            return null;
        }
        int newline = text.indexOf('\n');
        String first = newline >= 0 ? text.substring(0, newline) : text;
        if (first.endsWith("\r")) {
            first = first.substring(0, first.length() - 1);
        }
        return first.length() <= max ? first : first.substring(0, max) + "…";
    }

    private static String textOf(ToolResultBlock block) {
        if (block == null || block.getOutput().isEmpty()
                || !(block.getOutput().get(0) instanceof TextBlock text)) {
            return null;
        }
        return text.getText();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    private static String truncated(String value, int max, String emptyFallback) {
        if (value == null || value.isBlank()) {
            return emptyFallback;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
