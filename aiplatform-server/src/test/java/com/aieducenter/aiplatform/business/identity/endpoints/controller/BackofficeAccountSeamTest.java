package com.aieducenter.aiplatform.business.identity.endpoints.controller;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台账号档案读口（#154，/api/backoffice/accounts/{externalId}）在 {@code #152}
 * seam 上全绿：MockMvc 穿完整过滤链（签名验签 → 会话豁免 → @RequireSignature
 * 强制闸）→ 真应用服务 → aiplatform_test 真库。externalId＝OIDC sub＝对外正身
 * （我方 accountId 只是内部代理键）；返回我方留存四字段原样，无 identity 富化。
 * 签名负例（无签名 → 401）验类级强制闸对本控制器生效；负例三连的 seam 级
 * 复验归 {@code BackofficeSeamContractTest}，不在此重复。
 */
@BackofficeSeamTest
class BackofficeAccountSeamTest {

    private static final String EXTERNAL_ID = "sub-backoffice-1";

    @Autowired
    private MockMvc mockMvc;

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
    void given_persisted_account_when_signed_profile_then_four_fields_verbatim() throws Exception {
        Account account = accountRepository.save(Account.register(EXTERNAL_ID, "运营查档·张三"));
        // 建档时点固定尾零微秒（#177 回归锁）：LocalDateTime.toString() 保留 (.114420)、
        // Jackson ISO 序列化裁尾零 (.11442)，原文比对必假红——断言按解析后的值对照
        // （日期部分无载荷，now() 只取当天）
        LocalDateTime createdAt = LocalDateTime.now().withNano(114_420_000);
        jdbcTemplate.update("UPDATE idn_accounts SET created_at = ? WHERE external_id = ?",
                Timestamp.valueOf(createdAt), EXTERNAL_ID);

        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/accounts/" + EXTERNAL_ID),
                        "/api/backoffice/accounts/" + EXTERNAL_ID, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(account.getId().toString()))
                .andExpect(jsonPath("$.data.externalId").value(EXTERNAL_ID))
                .andExpect(jsonPath("$.data.displayName").value("运营查档·张三"))
                .andExpect(jsonPath("$.data.createdAt").value(parsedEqualTo(createdAt)));
    }

    /** createdAt 断言匹配器：两侧各归一化到 LocalDateTime 再比较，不受 ISO 裁尾零影响（#177）。 */
    private static Matcher<String> parsedEqualTo(LocalDateTime expected) {
        return new TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(String actual) {
                return LocalDateTime.parse(actual).isEqual(expected);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("解析后等于 ").appendValue(expected);
            }
        };
    }

    @Test
    void given_unknown_external_id_when_signed_profile_then_404_idn004() throws Exception {
        // 未命中走错误口径（404 IDN_004 账号不存在），不返回半空档案对象
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/accounts/sub-nonexistent"),
                        "/api/backoffice/accounts/sub-nonexistent", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(6004))
                .andExpect(jsonPath("$.message").value("账号不存在"))
                .andExpect(jsonPath("$.data").value(nullValue())); // 不返回半空档案
    }

    @Test
    void given_no_signature_headers_when_get_profile_then_401_signature_required() throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(get("/api/backoffice/accounts/" + EXTERNAL_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }
}
