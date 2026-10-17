package com.aieducenter.aiplatform.business.identity.domain.aggregate;

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
 * 账号聚合根（{@code idn_accounts}，#327 绑定表拆分后瘦身形）：业务档案极简——
 * id＋显示名＋时间戳，凭据一律住绑定表（{@link AccountBinding}），「迁 SSO 只动
 * 绑定表」由此成立。无 issuer/provider 列（供数方差异在绑定行）、无角色列（角色
 * 票再加）。不软删除（Auditable 只取审计字段）。首次登录自动建档，唯一性防线在
 * 绑定表 unique(provider, identifier)，常态路径靠登录 upsert 编排。
 */
@Entity
@Table(name = "idn_accounts")
@Aggregate
@Getter
public class Account extends Auditable implements AggregateRoot<Account, Long> {

    public static final int DISPLAY_NAME_MAX_LENGTH = 200;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "display_name", nullable = false, length = DISPLAY_NAME_MAX_LENGTH)
    private String displayName;

    protected Account() {
    }

    private Account(String displayName) {
        if (displayName == null || displayName.isBlank()
                || displayName.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new DomainException(IdentityMessage.ACCOUNT_FIELDS_INCOMPLETE);
        }
        this.id = TsidGenerator.newInstance().generate();
        this.displayName = displayName;
    }

    /**
     * 首次登录建档（OIDC 回调换 token 验签成功后 / 验证码登录验码通过后，经
     * AccountAppService 的两档 upsert 调用，绑定行同事务落）。
     */
    public static Account register(String displayName) {
        return new Account(displayName);
    }

    /**
     * 二次登录同步显示名（有变则更新）；返回是否发生变化，供调用方决定是否落库。
     */
    public boolean syncDisplayName(String candidate) {
        if (candidate == null || candidate.isBlank()
                || candidate.equals(this.displayName)
                || candidate.length() > DISPLAY_NAME_MAX_LENGTH) {
            return false;
        }
        this.displayName = candidate;
        return true;
    }
}
