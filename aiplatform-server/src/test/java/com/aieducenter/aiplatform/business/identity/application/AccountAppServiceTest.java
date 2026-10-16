package com.aieducenter.aiplatform.business.identity.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountBriefResponse;
import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountResponse;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.enums.AccountBindingProvider;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountBindingRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号应用服务：全量清单（建档顺序、字符串 id）＋绑定表拆分后的取数链（#327）——
 * 外部标识换算（accountIdOf）、摘要嵌入（briefOf/briefsOf）改从绑定表取；两档
 * 登录 upsert（oidc 回调/验证码登录）。
 */
@IntegrationTest
class AccountAppServiceTest {

    @Autowired
    private AccountAppService appService;

    @Autowired
    private AccountBindingRepository bindingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanAccounts() {
        // 测试库跨运行留存：前后各清一次，隔离历史残留行（本地持久 PG）；
        // 绑定行先清——账号删了绑定残留会让同标识再建档撞 unique
        jdbcTemplate.update("DELETE FROM idn_account_bindings");
        jdbcTemplate.update("DELETE FROM idn_accounts");
    }

    @Test
    void given_accounts_when_list_then_creation_order_with_string_ids() {
        Long first = appService.upsertOidc("sub-1", "开发甲").getId();
        Long second = appService.upsertOidc("sub-2", "测试乙").getId();

        List<AccountResponse> accounts = appService.list();

        assertThat(accounts).hasSize(2);
        assertThat(accounts).extracting(AccountResponse::accountId)
                .containsExactly(first.toString(), second.toString()); // 建档顺序稳定
        assertThat(accounts).extracting(AccountResponse::displayName)
                .containsExactly("开发甲", "测试乙");
    }

    @Test
    void given_oidc_binding_when_account_id_of_then_resolved_via_binding_table() {
        // 存量形态：OIDC sub 搬迁后即 oidc 绑定行——后台 externalId 过滤面零变化
        Long accountId = appService.upsertOidc("sub-legacy", "存量用户").getId();

        assertThat(appService.accountIdOf("sub-legacy")).contains(accountId);
        assertThat(appService.accountIdOf("sub-never-registered")).isEmpty();
    }

    @Test
    void given_email_binding_when_account_id_of_then_resolved_same_face() {
        // 验证码登录建档的新人群：邮箱标识在同一后台寻址面可命中
        Long accountId = appService.loginByBinding(AccountBindingProvider.EMAIL,
                "user@example.com").getId();

        assertThat(appService.accountIdOf("user@example.com")).contains(accountId);
    }

    @Test
    void given_same_identifier_under_two_providers_when_resolve_then_oidc_wins_deterministically() {
        // identifier 无单独唯一约束（oidc sub 与邮箱/手机号命名空间互不重叠是常态，
        // 不以约束赌它）——取数链给确定性优先序：oidc → email → phone
        Long oidcAccount = appService.upsertOidc("shared-id", "OIDC 侧").getId();
        appService.loginByBinding(AccountBindingProvider.EMAIL, "shared-id");

        assertThat(appService.accountIdOf("shared-id")).contains(oidcAccount);
    }

    @Test
    void given_accounts_with_bindings_when_briefs_then_primary_identifier_and_display_name() {
        Long oidcAccount = appService.upsertOidc("sub-243", "运营查档·钱七").getId();
        Long emailAccount = appService.loginByBinding(AccountBindingProvider.EMAIL,
                "zhang@example.com").getId();

        Map<Long, AccountBriefResponse> briefs =
                appService.briefsOf(java.util.Arrays.asList(oidcAccount, emailAccount, null));

        assertThat(briefs).containsEntry(oidcAccount,
                new AccountBriefResponse("sub-243", "运营查档·钱七"));
        assertThat(briefs).containsEntry(emailAccount,
                new AccountBriefResponse("zhang@example.com", "zhang@example.com"));
        assertThat(briefs).doesNotContainKey(null); // 可空账号不入 Map（容缺口径不变）
    }

    @Test
    void given_absent_account_when_brief_then_null_not_error() {
        assertThat(appService.briefOf(null)).isNull();
        assertThat(appService.briefOf(999999L)).isNull();
    }

    @Test
    void given_new_oidc_sub_when_upsert_then_account_created_with_binding() {
        Account account = appService.upsertOidc("sub-new", "新用户");

        assertThat(account.getDisplayName()).isEqualTo("新用户");
        assertThat(bindingRepository.findByProviderAndIdentifier(
                AccountBindingProvider.OIDC, "sub-new"))
                .hasValueSatisfying(binding -> assertThat(binding.getAccountId())
                        .isEqualTo(account.getId()));
    }

    @Test
    void given_existing_oidc_sub_with_changed_name_when_upsert_then_display_name_synced() {
        Long first = appService.upsertOidc("sub-sync", "旧名").getId();

        Account second = appService.upsertOidc("sub-sync", "新名");

        assertThat(second.getId()).isEqualTo(first); // 同 sub 不重复建档
        assertThat(second.getDisplayName()).isEqualTo("新名"); // IdP 是显示名权威
    }

    @Test
    void given_new_email_when_login_by_binding_then_account_silently_created() {
        // 登录即注册：无密码、验码即 upsert，新标识静默建账户＋绑定
        Account account = appService.loginByBinding(AccountBindingProvider.EMAIL,
                "new-user@example.com");

        assertThat(account.getDisplayName()).isEqualTo("new-user@example.com"); // 显示名缺省=标识原文
        assertThat(bindingRepository.findByProviderAndIdentifier(
                AccountBindingProvider.EMAIL, "new-user@example.com"))
                .hasValueSatisfying(binding -> assertThat(binding.getAccountId())
                        .isEqualTo(account.getId()));
    }

    @Test
    void given_existing_email_binding_when_login_by_binding_then_same_account_name_not_overwritten() {
        Long first = appService.loginByBinding(AccountBindingProvider.EMAIL,
                "known@example.com").getId();
        jdbcTemplate.update("UPDATE idn_accounts SET display_name = '自定名' WHERE id = ?",
                first);

        Account second = appService.loginByBinding(AccountBindingProvider.EMAIL,
                "known@example.com");

        assertThat(second.getId()).isEqualTo(first);
        assertThat(second.getDisplayName()).isEqualTo("自定名"); // 标识不是显示名权威，不覆写
    }
}
