package com.aieducenter.aiplatform.business.identity.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountResponse;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号查询：全量清单（建档顺序、字符串 id）。
 */
@SpringBootTest
class AccountAppServiceTest {

    @Autowired
    private AccountAppService appService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanAccounts() {
        // 测试库跨运行留存：前后各清一次，隔离历史残留行（本地持久 PG）
        jdbcTemplate.update("DELETE FROM idn_accounts");
    }

    @Test
    void given_accounts_when_list_then_creation_order_with_string_ids() {
        Long first = persistedAccount("sub-1", "开发甲");
        Long second = persistedAccount("sub-2", "测试乙");

        List<AccountResponse> accounts = appService.list();

        assertThat(accounts).hasSize(2);
        assertThat(accounts).extracting(AccountResponse::accountId)
                .containsExactly(first.toString(), second.toString()); // 建档顺序稳定
        assertThat(accounts).extracting(AccountResponse::displayName)
                .containsExactly("开发甲", "测试乙");
    }

    private Long persistedAccount(String externalId, String displayName) {
        return accountRepository.save(Account.register(externalId, displayName)).getId();
    }
}
