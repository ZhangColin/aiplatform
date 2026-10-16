package com.aieducenter.aiplatform.business.identity.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;
import com.cartisan.core.exception.CodeMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号聚合（#327 绑定表拆分后瘦身形）：档案只余 id＋显示名＋时间戳——凭据一律
 * 住绑定表（AccountBinding），自动建档与显示名同步语义不变。
 */
class AccountTest {

    @Test
    void given_first_login_when_register_then_display_name_populated_with_tsid_id() {
        Account account = Account.register("张三");

        assertThat(account.getId()).isPositive();
        assertThat(account.getDisplayName()).isEqualTo("张三");
    }

    @Test
    void given_incomplete_display_name_when_register_then_rejected() {
        assertThatThrownBy(() -> Account.register(null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_001"));
        assertThatThrownBy(() -> Account.register(" "))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_001"));
    }

    @Test
    void given_oversized_display_name_when_register_then_rejected() {
        assertThatThrownBy(() -> Account.register("名".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(code(e)).isEqualTo("IDN_001"));
    }

    private static String code(DomainException exception) {
        return exception.getCodeMessage().code();
    }

    @Test
    void given_changed_display_name_when_sync_then_updated_and_reported() {
        Account account = Account.register("张三");

        boolean changed = account.syncDisplayName("李四");

        assertThat(changed).isTrue();
        assertThat(account.getDisplayName()).isEqualTo("李四");
    }

    @Test
    void given_unchanged_display_name_when_sync_then_no_op_and_not_reported() {
        Account account = Account.register("张三");

        assertThat(account.syncDisplayName("张三")).isFalse();
        assertThat(account.getDisplayName()).isEqualTo("张三");
    }
}
