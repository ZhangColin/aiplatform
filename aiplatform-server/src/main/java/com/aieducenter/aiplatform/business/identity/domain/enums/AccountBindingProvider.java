package com.aieducenter.aiplatform.business.identity.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 账号绑定提供方（#327 绑定表拆分）：凭据的来源通道——凭据一律住绑定表
 * （{@code idn_account_bindings}），迁 SSO、加验证码通道、换身份供数方只动绑定行。
 *
 * <p>枚举值即对外承诺面：oidc＝现有 OIDC 302 流的 sub；email/phone＝平台内自建
 * 验证码登录的收码目标（机制双开、上线先邮箱——通道开关走配置，见
 * VerificationCodeProperties）。</p>
 */
public enum AccountBindingProvider implements BaseEnum<AccountBindingProvider> {

    /** OIDC 外部身份（identity 侧 sub；存量 external_id 一次性搬迁而来） */
    OIDC(1, "OIDC"),

    /** 邮箱验证码（上线先开的通道） */
    EMAIL(2, "邮箱"),

    /** 手机号验证码（机制位在，配置开关启用） */
    PHONE(3, "手机号");

    private final Integer code;
    private final String name;

    AccountBindingProvider(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /** 验证码通道（发码/验码机制的适用面）——oidc 不是收码通道。 */
    public boolean isCodeChannel() {
        return this == EMAIL || this == PHONE;
    }

    /**
     * JPA Converter - 必须声明为 public static class。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<AccountBindingProvider> {
        public JpaConverter() {
            super(AccountBindingProvider.class);
        }
    }
}
