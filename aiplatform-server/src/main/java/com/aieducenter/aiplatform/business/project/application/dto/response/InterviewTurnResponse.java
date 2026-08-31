package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 指令区发言响应（#19 需求环①）：runId = 本轮 BA 运行标识（智能体流
 * {@code /api/agent-events?runId=} 的锚；本轮回复与下一问经 SSE 到达）。
 *
 * @param runId BA 运行标识
 */
public record InterviewTurnResponse(String runId) {
}
