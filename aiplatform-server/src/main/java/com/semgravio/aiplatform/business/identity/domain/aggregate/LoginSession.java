package com.semgravio.aiplatform.business.identity.domain.aggregate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.semgravio.aiplatform.business.identity.domain.error.IdentityMessage;

/**
 * 登录会话聚合根（{@code idn_sessions}，#329 会话持久化）：BFF 服务端会话行——
 * 会话 id 即 cookie 值（不透明高熵随机，自然主键）、token 三件套只存服务端
 * （验证码登录无 token 语义全 NULL）、过期时点创建即定（{@code now + ttl}）。
 *
 * <p>生命周期＝行的存续：登出删行、过 {@code expiresAt} 不认（查询面谓词内联）、
 * 定时扫描清过期行。过期判定不在聚合上（无行内谓词场景）——唯一查询面
 * {@code findBySessionIdAndExpiresAtAfter} 承载。</p>
 */
@Entity
@Table(name = "idn_sessions")
@Aggregate
@Getter
public class LoginSession extends Auditable implements AggregateRoot<LoginSession, String> {

    /** 会话 id 列宽（32 字节 Base64url 无填充 = 43 字符，留余量；同 idn_guests.credential）。 */
    public static final int SESSION_ID_MAX_LENGTH = 64;

    /** 会话 id（= cookie 值，不透明高熵随机——自然主键，无代理键）。 */
    @Id
    @Column(name = "session_id", nullable = false, updatable = false,
            length = SESSION_ID_MAX_LENGTH)
    private String id;

    /** 归属账号（idn_accounts 软引用，无 FK；同 id 重 put 随覆盖——upsert 全列跟随输入）。 */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 显示名缓存：callback 建档时映射，每请求还原 RequestContext 免查库。 */
    @Column(name = "display_name", nullable = false, length = Account.DISPLAY_NAME_MAX_LENGTH)
    private String displayName;

    /** OIDC token 三件套（登出 id_token_hint 消费 id_token；code 登录全 NULL）。 */
    @Column(name = "id_token")
    private String idToken;

    @Column(name = "access_token")
    private String accessToken;

    @Column(name = "refresh_token")
    private String refreshToken;

    /** 会话过期时点（创建即定 now+ttl）：过即不认，行由扫描清。 */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected LoginSession() {
    }

    private LoginSession(String sessionId, Long accountId, String displayName,
            String idToken, String accessToken, String refreshToken, LocalDateTime expiresAt) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > SESSION_ID_MAX_LENGTH
                || accountId == null
                || displayName == null || displayName.isBlank() || displayName.length() > Account.DISPLAY_NAME_MAX_LENGTH
                || expiresAt == null) {
            throw new DomainException(IdentityMessage.SESSION_FIELDS_INCOMPLETE);
        }
        this.id = sessionId;
        this.accountId = accountId;
        this.displayName = displayName;
        this.idToken = idToken;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
    }

    /** 种会话行（M01/OIDC 两路登录同口；过期时点由 store 按 TTL 钉入）。 */
    public static LoginSession register(String sessionId, Long accountId, String displayName,
            String idToken, String accessToken, String refreshToken, LocalDateTime expiresAt) {
        return new LoginSession(sessionId, accountId, displayName,
                idToken, accessToken, refreshToken, expiresAt);
    }
}
