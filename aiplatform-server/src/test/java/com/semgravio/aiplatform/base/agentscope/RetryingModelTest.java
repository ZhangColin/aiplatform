package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.exception.OpenAIException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

/**
 * {@link RetryingModel}（#321 模型流断流自动重试 + #322 单条流总时长上限）：
 * 单条模型流遇可重试错误（HTTP 429/5xx，框架 {@code ExecutionConfig.
 * RETRYABLE_ERRORS} 分类——真实形态 {@link OpenAIException}，实现
 * {@code ModelHttpException}）在流层有限次自动重试接续；非可重试错误（4xx 业务错）
 * 不重试如实上抛；重试耗尽上抛末次原始错误（不裹 RetryExhaustedException——
 * 重试面不吞真错误）；已发块后失败不重试（重订阅会重放已消费块、下游累积重复
 * 文本——护栏）。总时长上限（#322）：{@code Flux.timeout} 语义是信号间隔不是
 * 总时长（#321 取证），滴漏挂流（块间隔小于 inter-signal 阈值、永不收口）只有
 * 总时长上限能掐——超限按失败处置进重试面（可重试分类，护栏照常治理）。
 */
@ExtendWith(MockitoExtension.class)
class RetryingModelTest {

    /** 快退避配置（测试不等生产 2s 起步）。 */
    private static AgentscopeProperties fastRetry() {
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setModelStreamRetryMaxAttempts(3);
        properties.setModelStreamRetryInitialBackoff(Duration.ofMillis(10));
        properties.setModelStreamRetryMaxBackoff(Duration.ofMillis(50));
        return properties;
    }

    private static ChatResponse usageResponse(ChatUsage usage) {
        return ChatResponse.builder().usage(usage).build();
    }

    @Mock
    private Model delegate;

    @Test
    void given_retryable_429_then_success_when_stream_then_retried_once_and_clean_result() {
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.error(new OpenAIException("HTTP 429 Too Many Requests", 429, null)))
                .thenReturn(Flux.just(usageResponse(new ChatUsage(100, 40, 0, 0.5))));

        List<ChatResponse> received = RetryingModel.wrap(delegate, fastRetry())
                .stream(List.of(), null, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        verify(delegate, times(2)).stream(any(), any(), any());
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getUsage().getTotalTokens()).isEqualTo(140);
    }

    @Test
    void given_non_retryable_400_when_stream_then_no_retry_and_original_error_surfaced() {
        OpenAIException badRequest = new OpenAIException("HTTP 400 Bad Request", 400, null);
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.error(badRequest));

        assertThatThrownBy(() -> RetryingModel.wrap(delegate, fastRetry())
                .stream(List.of(), null, null)
                .blockLast(Duration.ofSeconds(5)))
                .isSameAs(badRequest);

        verify(delegate, times(1)).stream(any(), any(), any());
    }

    @Test
    void given_retryable_5xx_always_when_stream_then_bounded_attempts_and_last_error_surfaced() {
        OpenAIException serverError = new OpenAIException("HTTP 503 Service Unavailable", 503, null);
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.error(serverError));

        assertThatThrownBy(() -> RetryingModel.wrap(delegate, fastRetry())
                .stream(List.of(), null, null)
                .blockLast(Duration.ofSeconds(10)))
                // 末次原始错误如实上抛（带状态码），不是 RetryExhaustedException——
                // 外层尝试环与错误现场（#221）消费真错误
                .isSameAs(serverError);

        verify(delegate, times(3)).stream(any(), any(), any());
    }

    @Test
    void given_retryable_error_after_emitted_chunk_when_stream_then_no_retry_no_duplicate() {
        // 中途断流（已发块后失败）：重订阅会重放已消费块、下游累积重复文本——
        // 护栏拦下重试，如实上抛进外层尝试环
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.concat(
                        Flux.just(usageResponse(new ChatUsage(100, 40, 0, 0.5))),
                        Flux.error(new OpenAIException("HTTP 500", 500, null))));

        List<ChatResponse> received = new java.util.ArrayList<>();
        assertThatThrownBy(() -> RetryingModel.wrap(delegate, fastRetry())
                .stream(List.of(), null, null)
                .doOnNext(received::add)
                .blockLast(Duration.ofSeconds(5)))
                .isInstanceOf(OpenAIException.class);

        verify(delegate, times(1)).stream(any(), any(), any());
        assertThat(received).hasSize(1);
    }

    @Test
    void given_retry_disabled_when_stream_then_passthrough_single_attempt() {
        AgentscopeProperties disabled = new AgentscopeProperties();
        disabled.setModelStreamRetryMaxAttempts(1);
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.error(new OpenAIException("HTTP 429", 429, null)));

        assertThatThrownBy(() -> RetryingModel.wrap(delegate, disabled)
                .stream(List.of(), null, null)
                .blockLast(Duration.ofSeconds(5)))
                .isInstanceOf(OpenAIException.class);

        verify(delegate, times(1)).stream(any(), any(), any());
    }

    @Test
    void given_transport_error_before_any_chunk_when_stream_then_retried() {
        // 建连期传输失败（无状态码）：框架分类按可重试（连接类错误）——零发射失败
        // 在护栏内，照常重试接续
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.error(new io.agentscope.core.model.transport.HttpTransportException(
                        "Connection refused")))
                .thenReturn(Flux.just(usageResponse(new ChatUsage(10, 5, 0, 0.1))));

        List<ChatResponse> received = RetryingModel.wrap(delegate, fastRetry())
                .stream(List.of(), null, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        verify(delegate, times(2)).stream(any(), any(), any());
        assertThat(received).hasSize(1);
    }

    // ---------- #322 单条流总时长上限 ----------

    /** 快上限配置（测试不等生产 15 分钟）。 */
    private static AgentscopeProperties capped(AgentscopeProperties properties, Duration cap) {
        properties.setModelStreamMaxDuration(cap);
        return properties;
    }

    @Test
    void given_hung_stream_zero_emission_over_cap_when_stream_then_capped_into_retry_ring() {
        // 灵魂例（AC「超出上限按失败处置进重试/尝试环」）：零发射挂流超上限＝可重试
        // 失败（TimeoutException 分类）且在已发块护栏内 → 流层重试接续，第二发正常
        // 收流成功——不再无限等
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.never())
                .thenReturn(Flux.just(usageResponse(new ChatUsage(100, 40, 0, 0.5))));

        List<ChatResponse> received = RetryingModel.wrap(delegate,
                        capped(fastRetry(), Duration.ofMillis(200)))
                .stream(List.of(), null, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        verify(delegate, times(2)).stream(any(), any(), any());
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getUsage().getTotalTokens()).isEqualTo(140);
    }

    @Test
    void given_dripping_stream_over_cap_when_stream_then_capped_error_surfaced_no_stream_retry() {
        // 滴漏挂流（已发块后仍不收口——块间隔小于框架 inter-signal 阈值、总时长无界
        // 的形态，15–109 分钟级现场同源）：超上限按失败处置，已发块护栏拦下流层重试
        // （重订阅重放＝下游累积重复文本），末次错误如实上抛外层尝试环
        when(delegate.stream(any(), any(), any())).thenReturn(
                Flux.interval(Duration.ofMillis(50))
                        .map(tick -> usageResponse(new ChatUsage(1, 1, 0, 0.0))));

        assertThatThrownBy(() -> RetryingModel.wrap(delegate,
                        capped(fastRetry(), Duration.ofMillis(300)))
                .stream(List.of(), null, null)
                .blockLast(Duration.ofSeconds(5)))
                // 阻塞边界对受检异常包 ReactiveException（Exceptions.propagate）——
                // 真错误以根因保真上抛，非重试机制的皮
                .hasRootCauseInstanceOf(ModelStreamDurationException.class);

        verify(delegate, times(1)).stream(any(), any(), any());
    }

    @Test
    void given_fast_stream_within_cap_when_stream_then_no_interference() {
        // 上限内正常流零干扰（上限不是信号间隔——连续发射照常过、恰单订阅）
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.just(
                usageResponse(new ChatUsage(1, 1, 0, 0.0)),
                usageResponse(new ChatUsage(2, 2, 0, 0.0))));

        List<ChatResponse> received = RetryingModel.wrap(delegate,
                        capped(fastRetry(), Duration.ofSeconds(2)))
                .stream(List.of(), null, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        verify(delegate, times(1)).stream(any(), any(), any());
        assertThat(received).hasSize(2);
    }

    @Test
    void given_retry_disabled_but_cap_enabled_when_stream_then_cap_still_enforced() {
        // 上限独立于重试开关（1=关闭流层重试直通，上限照常生效——堵无界挂流不依赖
        // 重试是否开启）
        AgentscopeProperties disabled = new AgentscopeProperties();
        disabled.setModelStreamRetryMaxAttempts(1);
        disabled.setModelStreamMaxDuration(Duration.ofMillis(150));
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.never());

        assertThatThrownBy(() -> RetryingModel.wrap(delegate, disabled)
                .stream(List.of(), null, null)
                .blockLast(Duration.ofSeconds(5)))
                .hasRootCauseInstanceOf(ModelStreamDurationException.class);

        verify(delegate, times(1)).stream(any(), any(), any());
    }
}
