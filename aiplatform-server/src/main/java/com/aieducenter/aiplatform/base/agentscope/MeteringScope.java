package com.aieducenter.aiplatform.base.agentscope;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

import io.agentscope.core.model.ChatUsage;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;

/**
 * 一轮对话的计量上下文（#109 模型边界计量）：主循环 + 压缩摘要 + 记忆抽取的 LLM
 * 调用统一经 {@link MeteredModel} 在每次 {@code stream()} 收口时报用量，而非只认
 * ReAct 管道的 {@code ModelCallEndEvent}（压缩/记忆抽取是框架 {@code model.stream}
 * 直调，绕开事件管道、usage 逃逸）。本类只承载「当前轮计量归哪里」的运行时事实：
 * 幂等键前缀（逐调用加序号防撞）、归属（subject/dims）、run/session 寻址、时间源
 * 与上报端口——由 {@link AgentscopeAgentClient} 在 run 起跑时挂 ThreadLocal，模型
 * 边界（同一模型实例被主循环/压缩/记忆抽取共用）据此归入本轮；fire-and-forget 的
 * 记忆钩子 flush（boundedElastic 线程）看不到本 ThreadLocal——其来源已在工厂侧
 * 关闭（记忆钩子全线 disable，见 {@link AgentscopeHarnessAgentFactory}）。
 */
final class MeteringScope {

    /** 当前线程活跃轮（run 起跑挂、收口摘；框架直调同一模型实例时据此归属）。 */
    private static final ThreadLocal<MeteringScope> CURRENT = new ThreadLocal<>();

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

    static MeteringScope current() {
        return CURRENT.get();
    }

    static void enter(MeteringScope scope) {
        CURRENT.set(scope);
    }

    static void exit() {
        CURRENT.remove();
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
