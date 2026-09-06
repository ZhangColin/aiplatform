package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 生成发起响应（#101 生成无门后本端点退为失败「重新发起」兜底）：该场 run 的
 * 运行标识（挂 /api/events?runId= 的锚）。生成是异步轨道——失败自动静默重试
 * （#84：用户面 run 身份 = 首试 runId 全程不变，中间失败与重试信号不出用户面），
 * 收口（成功落 generated_at / 超限终态发 run-failed 收口事件）以 SSE + REST 重查为准。
 *
 * @param runId 运行标识（首试即用户面身份）
 */
public record GenerationStartResponse(String runId) {
}
