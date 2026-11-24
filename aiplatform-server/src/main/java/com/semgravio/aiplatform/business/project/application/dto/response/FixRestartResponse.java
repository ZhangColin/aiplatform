package com.semgravio.aiplatform.business.project.application.dto.response;

/**
 * 「继续更新」响应（更新 run 终态恢复出口，#48）：重派 run 的运行标识（挂
 * /api/events?runId= 的锚——恢复动作与新 run 的链路关系）。更新 run 是
 * 异步轨道——#604 起单发无重试（失败即终态，不自动重跑），收口以
 * SSE + REST 重查为准。
 *
 * @param runId 重派 run 的运行标识
 */
public record FixRestartResponse(String runId) {
}
