package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 「重新修改」响应（#48 超限终态恢复出口）：重派 run 的运行标识（挂
 * /api/events?runId= 的锚——恢复动作与新 run 的链路关系）。修正 run 是
 * 异步轨道——失败自动静默重试（#84：用户面 run 身份全程不变），收口以
 * SSE + REST 重查为准。
 *
 * @param runId 重派 run 的运行标识（首试即用户面身份）
 */
public record FixRestartResponse(String runId) {
}
