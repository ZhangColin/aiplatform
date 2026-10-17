package com.aieducenter.aiplatform.business.identity.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录会话配置（前缀 app.session，#329 持久化）：会话 TTL——创建即定
 * {@code now + ttl}，过即不认、扫描清行。清扫节奏另配
 * {@code app.session.cleanup-interval}（@Scheduled 占位符消费，见
 * BffSessionCleanupScheduler；只此一处消费不入本类）。
 *
 * <p>浏览器侧 {@code aiplatform_session} 仍为会话 cookie（契约不动、无 MaxAge）：
 * 浏览器先关则 cookie 先失，行随后自过期收敛——TTL 只封顶服务端行的寿命。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.session")
public class SessionProperties {

    /** 会话 TTL（默认 14 天）：行存续上限——重启不丢、过期即弃。 */
    private Duration ttl = Duration.ofDays(14);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
