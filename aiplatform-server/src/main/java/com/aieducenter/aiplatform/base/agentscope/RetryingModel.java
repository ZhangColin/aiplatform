package com.aieducenter.aiplatform.base.agentscope;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 模型流层断流自动重试（#321）＋单条流总时长上限（#322）：包一层 {@link Model}，
 * 单条模型流遇可重试错误（HTTP 429/5xx）时有限次自动重试接续——重发同一请求，
 * 失败才上抛进外层尝试环（{@code CoderRunAttempts} 维持最后防线）；非可重试错误
 * （4xx 业务错）不重试、原样上抛（重试面不吞真错误）。
 *
 * <p><b>错误分类＝接线框架既有接口</b>：{@link ExecutionConfig#RETRYABLE_ERRORS}
 * （{@code HttpTransportException.isRetryable()} / {@code ModelHttpException.
 * isRetryableHttpStatus()}——429/5xx/超时/IO 可重试，其余 4xx 不可）。</p>
 *
 * <p><b>为什么平台独占流层重试（内层已关）</b>：agentscope 2.0.1 的
 * {@code OpenAIChatModel.Builder.build()} 会把 {@code MODEL_DEFAULTS}（内建
 * retryWhen）烧进模型 configuredOptions——内层重试缺省开启，但次数/backoff 平台
 * 无控制面、无<b>已发块护栏</b>（中途断流重订阅重放已消费块，下游
 * {@code ReasoningContext} 按 chunk 追加累积＝回复文本重复拼接）、耗尽后上抛
 * reactor {@code RetryExhaustedException}（真错误被重试机制的皮裹走——外层
 * 尝试环 #221 错误现场拿到的是重试耗尽文案而非模型错误）。故工厂在 resolve 时
 * 显式关内层（见 {@code AgentscopeHarnessAgentFactory.innerRetryDisabledOptions}
 * ——保留缺省 inter-signal 超时，除重试维度外不改既有行为），本类独占流层重试：
 * 次数/退避可配、分类接线、耗尽如实上抛末次原始错误。</p>
 *
 * <p><b>已发块护栏</b>：失败时已向下游发过任何块即不再重试（如实上抛进外层
 * 尝试环）——重试只发生在零发射失败上（429/5xx 响应在任何数据块之前到达，天然
 * 满足）。护栏下「任何发射只来自末次尝试」成立，外圈 {@link MeteredModel}
 * 的整流收口计量与单次直调完全同形——重试不破坏既有计量路径。</p>
 *
 * <p><b>总时长上限（#322）</b>：{@code Flux.timeout} 语义是信号间隔不是总时长
 * （#321 取证），框架内建 5 分钟 inter-signal 超时只掐静默——滴漏挂流（块间隔
 * 小于阈值、永不收口）不受治，实测 15–109 分钟级。本层对每条流（含重试后的每次
 * 重订阅，各自起算）施加总时长上限：超限即 {@link ModelStreamDurationException}
 * （继承 {@code TimeoutException}＝可重试分类，进重试面；护栏照常治理——已发块
 * 后不重试、如实上抛外层尝试环）。上限独立于重试开关（关闭重试仍生效——堵无界
 * 挂流不依赖重试）。</p>
 *
 * <p>重试静默（#84 口径）：不出用户面事件流，仅日志留痕（重试是过程事实，用户面
 * run 身份不变）。次数上界与退避经 {@link AgentscopeProperties} 配置（缺省 3 次
 * 含首试、2s 起 30s 封顶指数退避）。</p>
 */
@Slf4j
final class RetryingModel implements Model {

    private final Model delegate;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    /** 单条流总时长上限（null = 不设限）。 */
    private final Duration maxDuration;

    private RetryingModel(Model delegate, int maxAttempts, Duration initialBackoff,
            Duration maxBackoff, Duration maxDuration) {
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxDuration = maxDuration;
    }

    /**
     * 按配置包装：次数缺省 3、退避缺省 2s/30s（缺省档对齐框架 MODEL_DEFAULTS）；
     * 次数 ≤1 不包重试（关闭流层重试），总时长上限（缺省 15 分钟，null/零/负＝
     * 不设限）独立生效——两者全关才真直通（零装饰开销）。
     */
    static Model wrap(Model delegate, AgentscopeProperties properties) {
        int attempts = properties.getModelStreamRetryMaxAttempts() != null
                ? Math.max(1, properties.getModelStreamRetryMaxAttempts())
                : 3;
        Duration duration = positiveOrNull(properties.getModelStreamMaxDuration());
        if (attempts <= 1 && duration == null) {
            return delegate;
        }
        Duration backoff = positiveOr(properties.getModelStreamRetryInitialBackoff(),
                Duration.ofSeconds(2));
        Duration cap = positiveOr(properties.getModelStreamRetryMaxBackoff(), Duration.ofSeconds(30));
        return new RetryingModel(delegate, attempts, backoff,
                cap.compareTo(backoff) < 0 ? backoff : cap, duration);
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value != null && !value.isNegative() && !value.isZero() ? value : fallback;
    }

    private static Duration positiveOrNull(Duration value) {
        return value != null && !value.isNegative() && !value.isZero() ? value : null;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
            GenerateOptions options) {
        // 已发块护栏：本流（含历次尝试）向下游发过任何块即不再重试——重订阅会重放
        // 已消费块、下游累积重复文本；错误信号晚于最后一次发射串行到达，读取无竞态
        AtomicBoolean emitted = new AtomicBoolean(false);
        Flux<ChatResponse> body = Flux.defer(() -> durationCapped(
                delegate.stream(messages, tools, options)
                        .doOnNext(response -> emitted.set(true))));
        if (maxAttempts <= 1) {
            return body;
        }
        return body.retryWhen(Retry.backoff(maxAttempts - 1L, initialBackoff)
                        .maxBackoff(maxBackoff)
                        .jitter(0.5)
                        .filter(error -> !emitted.get()
                                && ExecutionConfig.RETRYABLE_ERRORS.test(error))
                        // 重试耗尽如实上抛末次原始错误（不裹 RetryExhaustedException——
                        // 外层尝试环与错误现场（#221）要真错误，不要重试机制的皮）
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure())
                        .doBeforeRetry(signal -> log.warn(
                                "[model-stream] 断流自动重试 {}/{} 次：{}",
                                signal.totalRetriesInARow() + 1, maxAttempts - 1,
                                signal.failure().toString())));
    }

    /**
     * 单条流总时长上限（每次订阅各自起算——重试的重订阅是新流新上限）：到点掐流
     * 转 {@link ModelStreamDurationException}。<b>为什么不用 {@code Flux.timeout}
     * ＋为什么不是 {@code take(Duration)} 直用</b>：前者是信号间隔语义（滴漏挂流
     * 每块重置、永不触发——堵不住）；后者到点发 onComplete 而非错误（下游当正常
     * 收口，假完成）。取 {@code takeUntilOther} 到点收口＋{@code concatWith} 把
     * 「因上限收口」显式转错误：{@code capped} 旗标在 {@code doOnNext} 先于
     * {@code takeUntilOther} 的收口信号链路设置（信号串行无竞态），自然完成时
     * 旗标未置、{@code concatWith} 补空收口——正常流零干扰。
     */
    private Flux<ChatResponse> durationCapped(Flux<ChatResponse> source) {
        if (maxDuration == null) {
            return source;
        }
        AtomicBoolean capped = new AtomicBoolean(false);
        return source
                .takeUntilOther(Mono.delay(maxDuration).doOnNext(tick -> capped.set(true)))
                .concatWith(Mono.defer(() -> capped.get()
                        ? Mono.error(new ModelStreamDurationException(maxDuration))
                        : Mono.empty()));
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
