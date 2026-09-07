package com.aieducenter.aiplatform.base.agentscope;

import java.util.List;

/**
 * 一轮对话的汇聚结果：最终文本（流式增量的汇聚，与回调拼接一致）；挂起轮
 * （软终点，run 未终态）另携带 {@link AgentSuspension}——null = 正常收口；
 * {@link #changes()} 为本流段成功的文件变更事实（#88 收口扩载的观察面——
 * write_file / edit_file 工具调用事实，收口侧拼装变更清单与统计）；
 * {@link #durations()} 为本流段的阶段耗时事实（#111 收口扩载 durationBreakdown
 * 的观察面——模型调用/工具执行/命令归组/委派窗，收口侧跨段合并汇总）。
 */
public record AgentReply(String runId, String text, AgentSuspension suspension,
        List<FileChange> changes, StageDurations durations) {

    public AgentReply(String runId, String text, AgentSuspension suspension,
            List<FileChange> changes) {
        this(runId, text, suspension, changes, StageDurations.zero());
    }

    public AgentReply(String runId, String text, AgentSuspension suspension) {
        this(runId, text, suspension, List.of());
    }

    /** 正常收口形（无挂起、无文件变更）。 */
    public AgentReply(String runId, String text) {
        this(runId, text, null, List.of());
    }
}
