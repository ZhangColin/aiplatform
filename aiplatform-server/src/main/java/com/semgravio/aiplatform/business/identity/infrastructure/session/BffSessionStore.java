package com.semgravio.aiplatform.business.identity.infrastructure.session;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.semgravio.aiplatform.business.identity.application.SessionProperties;
import com.semgravio.aiplatform.business.identity.domain.aggregate.LoginSession;
import com.semgravio.aiplatform.business.identity.domain.repository.LoginSessionRepository;

/**
 * BFF 会话存储（A2 §2 表 2；#329 起落 {@code idn_sessions}——接口不动、实现由
 * 内存 Map 换 PG）：服务重启登录态不丢、M01 与 OIDC 两路会话同享一表、多实例
 * 部署的前置条件齐备。key = 不透明 sessionId（业务 cookie 值，自然主键）。
 *
 * <p>生命周期＝行的存续：{@link #put} 创建即钉过期时点（now + {@code app.session.ttl}）；
 * {@link #get} 过期谓词内联查询（过期行等同不存在，正确性不依赖清扫节奏）；
 * {@link #remove} 删行（登出）；{@link #purgeExpired} 定时扫描清过期行
 * （节奏 {@code app.session.cleanup-interval}，DELETE 幂等——多实例并发安全）。</p>
 */
@Component
public class BffSessionStore {

    private final LoginSessionRepository sessionRepository;
    private final SessionProperties properties;
    private final Clock clock;

    public BffSessionStore(LoginSessionRepository sessionRepository,
            SessionProperties properties, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 种会话（M01/OIDC 两路登录同口）：落行，过期时点创建即定（now + ttl）。 */
    @Transactional
    public void put(String sessionId, BffSession session) {
        sessionRepository.save(LoginSession.register(sessionId, session.accountId(),
                session.displayName(), session.idToken(), session.accessToken(),
                session.refreshToken(), LocalDateTime.now(clock).plus(properties.getTtl())));
    }

    /** 取会话：行存在且未过期即有效（null/未知/已过期皆 empty——过期行不认，行由清扫删）。 */
    public Optional<BffSession> get(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return sessionRepository
                .findByIdAndExpiresAtAfter(sessionId, LocalDateTime.now(clock))
                .map(row -> new BffSession(row.getAccountId(), row.getDisplayName(),
                        row.getIdToken(), row.getAccessToken(), row.getRefreshToken()));
    }

    /** 删会话（登出）：缺行 no-op（幂等）。 */
    @Transactional
    public void remove(String sessionId) {
        if (sessionId != null) {
            sessionRepository.deleteById(sessionId);
        }
    }

    /** 清过期行（定时扫描入口，#329）：返回清除行数。 */
    @Transactional
    public int purgeExpired() {
        return sessionRepository.deleteExpiredBefore(LocalDateTime.now(clock));
    }
}
