package com.semgravio.aiplatform.business.identity.infrastructure.code;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.business.identity.application.VerificationCodeProperties;
import com.semgravio.aiplatform.business.identity.domain.enums.AccountBindingProvider;

/**
 * 在途验证码存储（#327）：内存 Map、键＝通道×归一标识。短命数据（分钟级挑战件）
 * 不落库——进程重启即失＝在途码全作废、用户重发即可，非损失面；登录会话的持久
 * 化是另一机制（#329），此处不越界。
 *
 * <p>时间边界口径（单测钉死）：冷却窗到点即放行（exclusive）、有效期到点即过期
 * （inclusive）、验码成功即消费（一次性）、错码记账超次即作废、重发覆盖旧码。
 * 全参数（{@link VerificationCodeProperties}）配置化。</p>
 */
@Component
public class VerificationCodeStore {

    private final VerificationCodeProperties properties;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    public VerificationCodeStore(VerificationCodeProperties properties) {
        this.properties = properties;
    }

    /** 同目标冷却窗内（发码频控）＝true。 */
    public boolean inCooldown(AccountBindingProvider channel, String identifier, Instant now) {
        Entry entry = entries.get(key(channel, identifier));
        return entry != null && now.isBefore(entry.cooldownUntil);
    }

    /** 落码（重发即覆盖旧码，旧码自然作废）＋开冷却窗。 */
    public void issue(AccountBindingProvider channel, String identifier, String code, Instant now) {
        Duration cooldown = Duration.ofSeconds(properties.getSendCooldownSeconds());
        entries.put(key(channel, identifier), new Entry(code,
                now.plus(Duration.ofSeconds(properties.getCodeTtlSeconds())),
                now.plus(cooldown),
                properties.getMaxVerifyAttempts()));
    }

    /**
     * 验码即消费判定（一次性）：码对且未过期 → true 且移除；码错 → 记账一次，
     * 试错耗尽即作废；过期/无在途码 → false。判定与消费在一个原子段内完成
     * （同码并发重放只有一次能过）。
     */
    public boolean verifyAndConsume(AccountBindingProvider channel, String identifier,
            String code, Instant now) {
        Key key = key(channel, identifier);
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        if (!now.isBefore(entry.expiresAt)) { // 有效期到点即过期（inclusive）
            entries.remove(key);
            return false;
        }
        if (!entry.code.equals(code)) {
            if (entry.attemptsLeft <= 1) { // 本次错码即耗尽：作废
                entries.remove(key);
            } else {
                entries.put(key, entry.withAttemptSpent());
            }
            return false;
        }
        return entries.remove(key, entry); // 消费：验过即作废
    }

    private static Key key(AccountBindingProvider channel, String identifier) {
        return new Key(channel, identifier);
    }

    private record Key(AccountBindingProvider channel, String identifier) {
    }

    private record Entry(String code, Instant expiresAt, Instant cooldownUntil, int attemptsLeft) {

        Entry withAttemptSpent() {
            return new Entry(code, expiresAt, cooldownUntil, attemptsLeft - 1);
        }
    }
}
