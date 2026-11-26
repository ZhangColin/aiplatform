package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 运行期切候选留痕（#557，ADR-0053 ③，append-only，照 {@link ModelProviderTrace}
 * 机制）：FailoverModel 每次链式切换一痕——何时（{@code createdAt}）/从哪候选切到
 * 哪（通道键×实际模型名两列组）/触发错误（类签名＋文案截断）/run 上下文（可空
 * ——不在计量订阅树上的装配面切换）。写腿经 {@code ModelSwitchTraceSink} 端口
 * （base）→ {@code PersistingModelSwitchTraceSink} 适配（business）落库；运营
 * 可见面＝「备账号被烧」的运行期事实（与配置面留痕 {@link ModelProviderTrace}/
 * {@link ModelConfigTrace} 互补：那两份记「配置怎么改的」，本份记「运行期怎么切的」）。
 *
 * @param id              留痕标识（TSID，写痕用例生成）
 * @param configKey         配置语义键（无 FK——留痕跨一切存活，V79 口径）
 * @param fromProviderKey 切出候选通道键（首切可空——语义上从配置现列起）
 * @param fromModelName   切出候选实际模型名
 * @param toProviderKey   切入候选通道键
 * @param toModelName     切入候选实际模型名
 * @param triggerError    触发错误摘要（异常类签名＋文案，≤500 截断）
 * @param runId           run 上下文（可空——无计量语境的装配面）
 * @param sessionId       会话上下文（可空，同上）
 * @param createdAt       切换时刻
 */
public record ModelConfigSwitchTrace(
        long id,
        String configKey,
        String fromProviderKey,
        String fromModelName,
        String toProviderKey,
        String toModelName,
        String triggerError,
        String runId,
        String sessionId,
        LocalDateTime createdAt) {
}
