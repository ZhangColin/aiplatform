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

import com.aieducenter.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.aieducenter.aiplatform.business.identity.domain.error.IdentityMessage;

/**
 * 账号绑定（{@code idn_account_bindings}，#327 绑定表拆分）：账户凭据的唯一下落
 * ——account_id × provider × identifier。迁 SSO 只动本表、账号档不再随供数方变形；
 * unique(provider, identifier) 兜底「同标识不重复挂绑」，常态路径靠登录 upsert 编排。
 *
 * <p>行落库后不可变（无 updatable 面）：换绑＝新行旧行下线，不在行内改写。同账号
 * 同 provider 多行（一账号多邮箱等）schema 允许、v1 无产生面；取数侧「主绑定标识」
 * 的确定性口径见 AccountAppService。</p>
 */
@Entity
@Table(name = "idn_account_bindings")
@Aggregate
@Getter
public class AccountBinding extends Auditable implements AggregateRoot<AccountBinding, Long> {

    /** 与库列宽对齐（V31 迁移） */
    public static final int IDENTIFIER_MAX_LENGTH = 200;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "provider", nullable = false, updatable = false)
    private AccountBindingProvider provider;

    @Column(name = "identifier", nullable = false, updatable = false, length = IDENTIFIER_MAX_LENGTH)
    private String identifier;

    protected AccountBinding() {
    }

    private AccountBinding(Long accountId, AccountBindingProvider provider, String identifier) {
        if (accountId == null || provider == null
                || identifier == null || identifier.isBlank()
                || identifier.length() > IDENTIFIER_MAX_LENGTH) {
            throw new DomainException(IdentityMessage.BINDING_FIELDS_INCOMPLETE);
        }
        this.id = TsidGenerator.newInstance().generate();
        this.accountId = accountId;
        this.provider = provider;
        this.identifier = identifier;
    }

    /** 登录建档/挂绑（upsert 未命中侧的新行）。 */
    public static AccountBinding bind(Long accountId, AccountBindingProvider provider,
            String identifier) {
        return new AccountBinding(accountId, provider, identifier);
    }
}
