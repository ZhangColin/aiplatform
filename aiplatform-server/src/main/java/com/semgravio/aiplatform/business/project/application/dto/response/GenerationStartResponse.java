package com.semgravio.aiplatform.business.project.application.dto.response;

/**
 * 生成发起响应：该场 run 的运行标识（挂 /api/events?runId= 的锚）。生成是异步
 * 轨道——#604 起单发无重试（不自动重跑：失败即终态，run-failed 收口事件明确
 * 展示，用户重跑即恢复）；收口（成功落 generated_at / 失败·停止发 run-failed
 * 收口事件）以 SSE + REST 重查为准。
 *
 * @param runId 运行标识（该场 run 的用户面身份，全程不变）
 */
public record GenerationStartResponse(String runId) {
}
