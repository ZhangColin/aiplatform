package com.semgravio.aiplatform.business.billing.application.event;

import java.time.Instant;
import java.util.UUID;

import com.cartisan.event.ApplicationEvent;

/**
 * 余额池已入账（#537 计费③段间硬门）：入账落定后发布（订阅方 AFTER_COMMIT 送达）
 * ——段边界余额门排空面（余额提醒放行/被余额拦下的 run 派发 intent）的触发信号。
 * 载荷为入账事实（池/账户/项目/金额/行后余额）；账户池入账 projectId 为 null
 * （一个账户池供多个项目扣减——排空面按各项目门判定各自重判，不按载荷路由）。
 */
public record PoolToppedUp(
        String eventId,
        Instant occurredAt,
        Long poolId,
        Long accountId,
        Long projectId,
        long amountFen,
        long balanceAfter) implements ApplicationEvent {

    public static PoolToppedUp of(Long poolId, Long accountId, Long projectId,
            long amountFen, long balanceAfter) {
        return new PoolToppedUp(UUID.randomUUID().toString(), Instant.now(),
                poolId, accountId, projectId, amountFen, balanceAfter);
    }

    @Override
    public String eventType() {
        return "PoolToppedUp";
    }
}
