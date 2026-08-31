package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 「开始做系统」响应：首次尝试的运行标识（挂 /api/agent-events?runId= 的锚）。
 * 生成是异步轨道——失败自动重试换新 runId 经 task-retrying / task-start 帧到达，
 * 收口（成功落 generated_at / 超限失败）以 SSE + REST 重查为准。
 *
 * @param runId 首试运行标识
 */
public record GenerationStartResponse(String runId) {
}
