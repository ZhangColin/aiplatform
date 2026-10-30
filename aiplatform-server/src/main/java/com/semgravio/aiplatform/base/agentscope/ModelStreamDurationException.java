package com.semgravio.aiplatform.base.agentscope;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 单条模型流总时长超上限（#322 堵无界挂流）：滴漏挂流（块间隔小于框架 inter-signal
 * 阈值、永不收口——15–109 分钟级现场同源）只有<b>总时长</b>上限能掐（#321 取证：
 * {@code Flux.timeout} 语义是信号间隔不是总时长）。超上限按失败处置进重试面。
 *
 * <p><b>继承 {@link TimeoutException}＝分类接线</b>：框架 {@code
 * ExecutionConfig.RETRYABLE_ERRORS} 按超时可重试分类——上限失败正确归入流层重试面
 * （受 {@link RetryingModel} 已发块护栏治理：已发块后不重试、如实上抛外层尝试环）；
 * 无此继承会被归为未知错误不重试、直接整 attempt 作废（#321 修复前的缺口同形）。
 */
final class ModelStreamDurationException extends TimeoutException {

    ModelStreamDurationException(Duration limit) {
        super("单条模型流总时长超上限（limit=" + limit + "）——按失败处置，不再无限等");
    }
}
