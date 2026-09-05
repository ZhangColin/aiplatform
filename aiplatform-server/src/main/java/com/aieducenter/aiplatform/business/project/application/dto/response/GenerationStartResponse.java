package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 「开始做系统」响应：首次尝试的运行标识（挂 /api/events?runId= 的锚）。
 * 生成是异步轨道——重试换新 runId 经 run-start 事件到达（中间失败静默），
 * 收口（成功落 generated_at / 超限终态发 run-failed 收口事件）以 SSE + REST 重查为准。
 *
 * @param runId 首试运行标识
 */
public record GenerationStartResponse(String runId) {
}
