package com.semgravio.aiplatform.base.agentscope;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;

/**
 * 模型边界计量装饰（#109 / #324 对账级修复）：包一层 {@link Model}，每次
 * {@code stream()} 收口把该次调用的 usage 报进当前轮 {@link MeteringScope}（经
 * {@link UsageEventSink} 落 {@code met_usage_events}）。主循环（ReAct 每次迭代）、
 * 压缩摘要（{@code ConversationCompactor.summarizePrefix}）、记忆抽取（
 * {@code MemoryFlushManager.flushMemories}）、委派子智能体（共享同一模型实例）各自
 * {@code stream()} 直调——在此单点收口，取代「只认 {@code ModelCallEndEvent}」的
 * 旧单一来源。
 *
 * <p>usage 取「末个非空 usage 胜出」（同框架 {@code ReasoningContext.processChunk}
 * 的覆盖式累积口径——最终 chunk 携全量 usage，中间 chunk 或空或子集，逐 chunk 累加
 * 会重复计费）；{@code doFinally} 收口确保失败轮已耗 token 如实计量（对齐 run 收口
 * 口径）。计量上下文在<b>订阅态</b>从 Reactor 订阅链读取（{@link MeteringScope} 的
 * 传递机制——装配线程≠订阅/信号线程，装配态读取对后续迭代/压缩/委派链静默漏计，
 * #324）。无活跃轮（不在订阅树上的直调）不报——不发明归属。</p>
 */
final class MeteredModel implements Model {

    private final Model delegate;
    private final ModelRef modelRef;

    private MeteredModel(Model delegate, ModelRef modelRef) {
        this.delegate = delegate;
        this.modelRef = modelRef;
    }

    /** 解析模型串为 provider:modelId 归属（白名单同主模型口径）并包装。 */
    static Model wrap(Model delegate, String modelString) {
        return new MeteredModel(delegate, ModelRef.parse(modelString));
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
            GenerateOptions options) {
        return Flux.deferContextual(contextView -> {
            MeteringScope scope = MeteringScope.fromContext(contextView);
            if (scope == null) {
                return delegate.stream(messages, tools, options);
            }
            AtomicReference<ChatUsage> last = new AtomicReference<>();
            return delegate.stream(messages, tools, options)
                    .doOnNext(resp -> {
                        if (resp.getUsage() != null) {
                            last.set(resp.getUsage());
                        }
                    })
                    .doFinally(signal -> scope.report(modelRef, last.get()));
        });
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
