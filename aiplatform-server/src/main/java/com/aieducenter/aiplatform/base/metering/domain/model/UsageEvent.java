package com.aieducenter.aiplatform.base.metering.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * 用量事件协议（A1 §2.2，研究稿 F4.2 照收）：一次上报 = 一条 run 级 token 记录。
 *
 * <p>字段语义：{@code eventId} 调用方生成的幂等键（重复上报 first-write-wins）；
 * {@code subject} 不透明归属 id（业务层传 projectId，底座不解释）；{@code runId}/
 * {@code sessionId} 运行与会话寻址（可空——非 run 级来源可不带）；{@code provider}/
 * {@code model} 模型标识（单价表匹配键——无引擎维度，单栈后引擎不进协议）；
 * {@code dims} 业务维度透传（终态口径 projectId + agentKind(ba/coder) + sessionId，
 * 底座不解释，可空）；{@code tokens} 五档互斥分解（见 {@link TokenUsage}）。
 * run 级一条：step-finish 增量求和是 adapter 内部实现，不进协议。</p>
 */
public record UsageEvent(
        String eventId,
        Instant ts,
        String subject,
        String runId,
        String sessionId,
        String provider,
        String model,
        Map<String, String> dims,
        TokenUsage tokens) {
}
