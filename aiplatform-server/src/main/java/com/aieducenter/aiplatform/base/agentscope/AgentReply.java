package com.aieducenter.aiplatform.base.agentscope;

/**
 * 一轮对话的汇聚结果：最终文本（流式增量的汇聚，与回调拼接一致）。
 */
public record AgentReply(String runId, String text) {
}
