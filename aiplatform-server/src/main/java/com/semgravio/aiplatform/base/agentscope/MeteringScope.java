package com.semgravio.aiplatform.base.agentscope;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

import io.agentscope.core.model.ChatUsage;
import reactor.util.context.ContextView;

import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;

/**
 * 一轮对话的计量上下文（#109 模型边界计量 / #324 对账级修复）：主循环每次迭代 +
 * 压缩摘要 + 记忆抽取 + 委派子智能体的 LLM 调用统一经 {@link MeteredModel} 在每次
 * {@code stream()} 收口时报用量，而非只认 ReAct 管道的 {@code ModelCallEndEvent}
 * （压缩/记忆抽取是框架 {@code model.stream} 直调，绕开事件管道、usage 逃逸）。
 * 本类只承载「当前轮计量归哪里」的运行时事实：幂等键前缀（逐调用加序号防撞）、
 * 归属（subject/dims）、run/session 寻址、时间源与上报端口。
 *
 * <p><b>传递机制（#324）：Reactor 订阅链 Context，非 ThreadLocal。</b>由
 * {@link AgentscopeAgentClient} 在 run 起跑的订阅点写入（{@code contextWrite}），
 * 模型边界在订阅态读取（{@code deferContextual}）。此前 ThreadLocal 挂在调用线程
 * 上、模型边界在装配时刻读——但 ReAct 后续迭代/压缩链/委派链的模型直调发生在
 * 信号线程（与首流装配线程不同），装配线程不见 ThreadLocal 即<b>静默漏计</b>
 * （连成功 run 也只计首个模型流的现场即此形态）。订阅链 Context 随订阅传播——
 * 框架内核把订阅者 context 全量续进智能体生命周期链（事件 sink 同款传递机制），
 * 跨迭代/跨线程/子级链的模型流都可见。fire-and-forget 的记忆钩子 flush 不在本
 * 订阅树——其来源已在工厂侧关闭（记忆钩子全线 disable，见
 * {@link AgentscopeHarnessAgentFactory}）。</p>
 */
final class MeteringScope {

    /** 订阅链键：当前轮计量上下文（runTurn 订阅点写入、模型边界订阅态读取）。 */
    static final Class<MeteringScope> CONTEXT_KEY = MeteringScope.class;

    private final String runId;
    private final String sessionId;
    private final UsageContext usageContext;
    private final Clock clock;
    private final UsageEventSink sink;
    private final String idempotencyPrefix;
    private final AtomicLong seq = new AtomicLong();

    MeteringScope(String runId, String sessionId, UsageContext usageContext,
            Clock clock, UsageEventSink sink, String idempotencyPrefix) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.usageContext = usageContext;
        this.clock = clock;
        this.sink = sink;
        this.idempotencyPrefix = idempotencyPrefix;
    }

    /** 从订阅链读取当前轮计量上下文（订阅态调用——不在订阅树上的直调返回 null）。 */
    static MeteringScope fromContext(ContextView context) {
        return context.getOrDefault(CONTEXT_KEY, null);
    }

    /**
     * 一次模型 {@code stream()} 收口报恰一条 UsageEvent（幂等键 = 前缀 + 本轮自增
     * 序号；零用量/无 usage 跳过——不发明零事件）。provider/model 取模型边界的
     * 自身档位（压缩 flash 与主循环 pro 各自归位，不并桶）。
     */
    void report(ModelRef modelRef, ChatUsage usage) {
        TokenUsage tokens = AgentscopeUsageMapper.toTokenUsage(usage);
        if (tokens.total() <= 0) {
            return;
        }
        sink.report(new UsageEvent(
                idempotencyPrefix + "-" + seq.incrementAndGet(),
                clock.instant(),
                usageContext.subject(),
                runId,
                sessionId,
                modelRef.provider(),
                modelRef.modelId(),
                usageContext.dims(),
                tokens));
    }
}
