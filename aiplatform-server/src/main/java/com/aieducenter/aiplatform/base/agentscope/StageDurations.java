package com.aieducenter.aiplatform.base.agentscope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一段流（converse/resume）的阶段耗时事实快照（#111 收口扩载 durationBreakdown
 * 的观察面）：LLM 等待（执行体模型调用起止累计）/ 工具执行（按工具名分桶——
 * command 工具不入此桶）/ 命令归组（command 工具的 install/dev/test/other）/
 * 委派窗（source → 首末事件跨距）。不可变——run 尝试环跨段（converse + 续跑）
 * 以 {@link #plus} 合并、跨尝试在收口装配侧汇总进 closing。观察口径与已知洞见
 * {@link StageDurationFacts}。
 */
public record StageDurations(long llmMs, Map<String, Long> toolsMs,
        Map<String, Long> commandMs, Map<String, Long> subagentMs) {

    /** 快照不可变（来源 map 的防御拷贝——观察面继续累积不回流已携出的快照；
     *  保插入序：分桶次序即观察序，进 closing JSONB 呈现稳定）。 */
    public StageDurations {
        toolsMs = immutableOrdered(toolsMs);
        commandMs = immutableOrdered(commandMs);
        subagentMs = immutableOrdered(subagentMs);
    }

    /** 零值（无观察——一次性调用与测试桩的缺省）。 */
    public static StageDurations zero() {
        return new StageDurations(0, Map.of(), Map.of(), Map.of());
    }

    /** 跨段合并：计数相加、分桶按键求和（同 source 委派窗跨段续接同理求和）。 */
    public StageDurations plus(StageDurations other) {
        return new StageDurations(llmMs + other.llmMs,
                merged(toolsMs, other.toolsMs),
                merged(commandMs, other.commandMs),
                merged(subagentMs, other.subagentMs));
    }

    private static Map<String, Long> merged(Map<String, Long> left, Map<String, Long> right) {
        Map<String, Long> merged = new LinkedHashMap<>(left);
        right.forEach((key, value) -> merged.merge(key, value, Long::sum));
        return merged;
    }

    private static Map<String, Long> immutableOrdered(Map<String, Long> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
