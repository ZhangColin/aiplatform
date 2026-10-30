package com.semgravio.aiplatform.business.identity.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;
import com.cartisan.core.exception.CodeMessage;

import com.semgravio.aiplatform.business.identity.domain.enums.AccountBindingProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号绑定聚合（#327）：凭据下落的唯一行——provider × identifier 挂账号，落库后
 * 不可变（迁移换供数方只动本表）。
 */
class AccountBindingTest {

    @Test
    void given_new_login_identifier_when_bind_then_row_populated_with_tsid_id() {
        AccountBinding binding = AccountBinding.bind(424242L, AccountBindingProvider.EMAIL,
                "user@example.com");

        assertThat(binding.getId()).isPositive();
        assertThat(binding.getAccountId()).isEqualTo(424242L);
        assertThat(binding.getProvider()).isEqualTo(AccountBindingProvider.EMAIL);
        assertThat(binding.getIdentifier()).isEqualTo("user@example.com");
    }

    @Test
    void given_blank_identifier_when_bind_then_rejected() {
        assertThatThrownBy(() -> AccountBinding.bind(424242L, AccountBindingProvider.OIDC, null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_009"));
        assertThatThrownBy(() -> AccountBinding.bind(424242L, AccountBindingProvider.PHONE, " "))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_009"));
    }

    @Test
    void given_missing_provider_or_account_when_bind_then_rejected() {
        assertThatThrownBy(() -> AccountBinding.bind(null, AccountBindingProvider.EMAIL, "user@example.com"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_009"));
        assertThatThrownBy(() -> AccountBinding.bind(424242L, null, "user@example.com"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_009"));
    }

    @Test
    void given_oversized_identifier_when_bind_then_rejected() {
        assertThatThrownBy(() -> AccountBinding.bind(424242L, AccountBindingProvider.EMAIL,
                "x".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_009"));
    }

    private static String code(DomainException exception) {
        return exception.getCodeMessage().code();
    }
}
