package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 对话区发言响应（#19 需求环①）：runId = 本轮主智能体运行标识（智能体事件
 * {@code /api/events?runId=} 的锚；本轮回复与下一问经 SSE 到达）。
 *
 * @param runId 主智能体运行标识
 */
public record InterviewTurnResponse(String runId) {
}
