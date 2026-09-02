package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 「重新修改」响应（#48 超限终态恢复出口）：重派 run 首试的运行标识（挂
 * /api/agent-events?runId= 的锚——恢复动作与新 run 的链路关系）。修正 run 是
 * 异步轨道——失败自动重试换新 runId 经帧到达，收口以 SSE + REST 重查为准。
 *
 * @param runId 重派 run 的首试运行标识
 */
public record FixRestartResponse(String runId) {
}
