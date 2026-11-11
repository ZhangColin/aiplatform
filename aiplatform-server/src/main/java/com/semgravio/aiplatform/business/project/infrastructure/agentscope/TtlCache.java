package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 短 TTL 进程内缓存（#453-2：每迭代平台开销优化腿——执行体 ReAct 循环中间件的
 * 运营配置读腿从「每迭代一次主键 SELECT」降为「TTL 窗口内零查库」）：进行中
 * run 的档位/开关秒级不变，TTL 的生效延迟语义对偶「进行中 run 不定格」
 * （运营面改档后下一个 TTL 窗口即生效，不发版——本仓装配取 5s）；
 * 缓存 miss/过期才走读腿，读腿抛错<b>不毒化缓存</b>（错误照常上抛——中间件
 * fail-open 口径不变，下次窗口重取）。线程安全（synchronized——迭代串行经
 * onReasoning，竞争面极小）。
 *
 * <p>实例随中间件发放（agent 实例缓存的装配件），无状态泄漏面；TTL 与 Clock
 * 构造注入（测试可控）。</p>
 *
 * @param <T> 缓存值类型（不可变配置值——策略对象/开关布尔）
 */
final class TtlCache<T> {

    private final Duration ttl;
    private final Clock clock;
    private T value;
    private Instant fetchedAt;

    TtlCache(Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("TTL 须为正时长");
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 取值：窗口内零查库直返缓存；过期/未取走读腿并刷新。读腿抛错上抛且不刷新
     * fetchedAt（旧值保留但本次不返回——fail-open 语义归中间件，本类不做
     * stale 续用裁决）。
     */
    synchronized T get(Supplier<T> loader) {
        Instant now = clock.instant();
        if (value != null && !now.isAfter(fetchedAt.plus(ttl))) {
            return value;
        }
        T fresh = loader.get();
        value = fresh;
        fetchedAt = now;
        return fresh;
    }
}
