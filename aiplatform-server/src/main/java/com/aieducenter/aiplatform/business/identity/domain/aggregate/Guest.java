package com.aieducenter.aiplatform.business.identity.domain.aggregate;

import java.time.Duration;
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
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.identity.domain.error.IdentityMessage;

/**
 * 访客聚合根（{@code idn_guests}，#326 访客域）：免注册先用的匿名身份——惰性创建
 * （首条需求消息提交成功才建行＋发凭证，只逛零足迹）、凭证值即 cookie 值（服务端
 * 高熵随机，唯一索引直查）、无状态校验（每请求直查本行，存在且未作废且在保留期内
 * 即有效）、滑动续期（lastActiveAt 随节流窗口前移，保留期自最近活跃起算）。
 *
 * <p>访客独立表不混账户表（spec #320 段②）；数据不删——过保留期/作废只是不认，
 * 行保留（未绑定项目不承诺找回；绑定平移属 #328，其「作废」落点即 {@link #revoke}）。
 * 期限缺省走配置（{@code app.guest.retention-period}），聚合只收参数不持有配置。</p>
 */
@Entity
@Table(name = "idn_guests")
@Aggregate
@Getter
public class Guest extends Auditable implements AggregateRoot<Guest, Long> {

    /** 凭证列宽（32 字节 Base64url 无填充 = 43 字符，留余量）。 */
    public static final int CREDENTIAL_MAX_LENGTH = 64;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /**
     * 凭证（值即凭证）：cookie 值与本列同值，无状态校验＝按本列唯一索引直查行。
     * 高熵服务端随机（{@code OauthTransaction.randomToken()} 同源，256 bit）。
     */
    @Column(name = "credential", nullable = false, updatable = false, length = CREDENTIAL_MAX_LENGTH)
    private String credential;

    /**
     * 最近活跃时点（滑动续期的锚）：保留期自本值起算，节流窗口内只读不写。
     */
    @Column(name = "last_active_at", nullable = false)
    private LocalDateTime lastActiveAt;

    /**
     * 作废时点（NULL = 未作废）：绑定平移（#328）把访客项目过户给账户后落值——
     * 凭证即时不认。本票只立语义（{@link #revoke}），生产写入方属 #328。
     */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected Guest() {
    }

    private Guest(String credential, LocalDateTime now) {
        if (credential == null || credential.isBlank() || credential.length() > CREDENTIAL_MAX_LENGTH
                || now == null) {
            throw new DomainException(IdentityMessage.GUEST_FIELDS_INCOMPLETE);
        }
        this.id = TsidGenerator.newInstance().generate();
        this.credential = credential;
        this.lastActiveAt = now;
    }

    /** 建访客行（惰性创建：首条需求消息提交成功才到达这里）。 */
    public static Guest register(String credential, LocalDateTime now) {
        return new Guest(credential, now);
    }

    /**
     * 有效性判定（无状态校验的谓词半边）：未作废且 {@code now} 在保留期内
     * （lastActiveAt + retention）即有效；过保留期不认（行保留）。
     */
    public boolean activeAt(LocalDateTime now, Duration retention) {
        return revokedAt == null
                && lastActiveAt != null
                && now.isBefore(lastActiveAt.plus(retention));
    }

    /**
     * 续期是否到期（写节流判定）：距最近活跃达阈值才写——续期写频率被节流窗口
     * 封顶（读多写少），有效性窗口实际落在 [retention, retention + threshold]。
     */
    public boolean renewDue(LocalDateTime now, Duration threshold) {
        return revokedAt == null
                && lastActiveAt != null
                && !now.isBefore(lastActiveAt.plus(threshold));
    }

    /** 续期（{@link #renewDue} 为真才调用）：活跃锚前移，保留期随新锚滑动。 */
    public void renew(LocalDateTime now) {
        if (now == null || (lastActiveAt != null && now.isBefore(lastActiveAt))) {
            throw new DomainException(IdentityMessage.GUEST_FIELDS_INCOMPLETE);
        }
        this.lastActiveAt = now;
    }

    /**
     * 作废（单向、幂等——首次时点定格）：绑定平移后凭证不再有效。数据不删。
     */
    public void revoke(LocalDateTime now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }
}
