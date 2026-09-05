package com.aieducenter.aiplatform.base.agentscope;

/**
 * 一轮对话的汇聚结果：最终文本（流式增量的汇聚，与回调拼接一致）；挂起轮
 * （软终点，run 未终态）另携带 {@link AgentSuspension}——null = 正常收口。
 */
public record AgentReply(String runId, String text, AgentSuspension suspension) {

    /** 正常收口形（无挂起）。 */
    public AgentReply(String runId, String text) {
        this(runId, text, null);
    }
}
