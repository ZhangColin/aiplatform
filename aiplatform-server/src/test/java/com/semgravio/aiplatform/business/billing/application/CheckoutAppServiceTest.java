package com.semgravio.aiplatform.business.billing.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.CheckoutAppService.CheckoutReceipt;
import com.semgravio.aiplatform.business.billing.application.dto.command.CheckoutTopupCommand;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;
import com.semgravio.aiplatform.business.billing.domain.enums.EntryKind;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingEntryRepository;
import com.semgravio.aiplatform.web.AuthException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模拟收银台用例集成测试（#534 计费④）：准入码错误/缺失被拦（BILL_019）＋
 * 须先登录（401）＋账户上下文充值落账户池（自助形态流水：无操作者＋备注）＋
 * 项目上下文充值（试用项目入账即选定项目源）＋账户源项目拒绝（BILL_020 死钱
 * 防线，原子无半截账）。
 */
@IntegrationTest
class CheckoutAppServiceTest {

    private static final long PROJECT_ID = 922_001L;
    private static final long ACCOUNT_ID = 922_101L;
    private static final String VALID_CODE = "DEMO-2026";

    @Autowired
    private CheckoutAppService appService;

    @Autowired
    private BillingEntryRepository entryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    @Test
    void given_wrong_or_missing_code_when_validate_then_rejected_with_guidance() {
        assertThatThrownBy(() -> appService.validateAccessCode("WRONG-CODE"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.ACCESS_CODE_INVALID.message())
                .hasMessageContaining("联系平台"); // 如实引导（无码用户指路）
        assertThatThrownBy(() -> appService.validateAccessCode(null))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> appService.validateAccessCode("   "))
                .isInstanceOf(ApplicationException.class);
        // 码两侧空白归一后比对
        appService.validateAccessCode("  " + VALID_CODE + "  ");
    }

    @Test
    void given_guest_when_topup_then_unauthorized() {
        assertThatThrownBy(() -> appService.topupAccount(null,
                new CheckoutTopupCommand(VALID_CODE, 5_000L)))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> appService.topupProject(null, PROJECT_ID,
                new CheckoutTopupCommand(VALID_CODE, 5_000L)))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void given_logged_in_when_account_topup_then_pool_credited_with_self_service_entry() {
        CheckoutReceipt receipt = appService.topupAccount(ACCOUNT_ID,
                new CheckoutTopupCommand(VALID_CODE, 20_000L));

        assertThat(receipt.pool().balance()).isEqualTo(20_000L);
        List<BillingEntry> entries = entryRepository.findByPoolIdOrderByIdAsc(
                Long.parseLong(receipt.pool().poolId()));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getKind()).isEqualTo(EntryKind.TOPUP);
        assertThat(entries.get(0).getAmount()).isEqualTo(20_000L);
        assertThat(entries.get(0).getBalanceAfter()).isEqualTo(20_000L);
        assertThat(entries.get(0).getNote()).isEqualTo(CheckoutAppService.CHECKOUT_NOTE);
        // 自助形态：无操作者两列落空、无段归属
        assertThat(entries.get(0).getOperatorId()).isNull();
        assertThat(entries.get(0).getOperatorName()).isNull();
        assertThat(entries.get(0).getProjectId()).isNull();
        // 账户充值不触碰扣减源（无 billing 行）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_project_billing", Integer.class)).isZero();
    }

    @Test
    void given_trial_project_when_project_topup_then_pool_credited_and_source_selected() {
        appService.topupProject(ACCOUNT_ID, PROJECT_ID,
                new CheckoutTopupCommand(VALID_CODE, 10_000L));

        var billing = jdbcTemplate.queryForMap(
                "SELECT deduction_source FROM bil_project_billing WHERE project_id = ?",
                PROJECT_ID);
        assertThat(billing.get("deduction_source")).isEqualTo(2); // PROJECT——入账即选定
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_entries WHERE project_id = ? AND kind = 1",
                Integer.class, PROJECT_ID)).isEqualTo(1);
    }

    @Test
    void given_account_source_project_when_project_topup_then_rejected_atomically() {
        jdbcTemplate.update("INSERT INTO bil_project_billing (id, project_id, deduction_source,"
                + " source_account_id, source_selected_at, created_at, updated_at)"
                + " VALUES (?, ?, 1, ?, now(), now(), now())", 922_900L, PROJECT_ID, ACCOUNT_ID);

        assertThatThrownBy(() -> appService.topupProject(ACCOUNT_ID, PROJECT_ID,
                new CheckoutTopupCommand(VALID_CODE, 5_000L)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.TOPUP_TARGET_NOT_SOURCE_POOL.message());

        // 死钱防线是原子的：无池无流水（半截账零容忍）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_entries", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_pools WHERE project_id = ?", Integer.class,
                PROJECT_ID)).isZero();
    }

    @Test
    void given_project_source_project_when_project_topup_then_credited() {
        jdbcTemplate.update("INSERT INTO bil_project_billing (id, project_id, deduction_source,"
                + " source_selected_at, created_at, updated_at)"
                + " VALUES (?, ?, 2, now(), now(), now())", 922_901L, PROJECT_ID);

        CheckoutReceipt receipt = appService.topupProject(ACCOUNT_ID, PROJECT_ID,
                new CheckoutTopupCommand(VALID_CODE, 3_000L));

        assertThat(receipt.pool().balance()).isEqualTo(3_000L);
        // 源已选项目：入账幂等空过（不刷新源选定，selectProjectSource 早退）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_entries WHERE project_id = ?", Integer.class,
                PROJECT_ID)).isEqualTo(1);
    }

    @Test
    void given_missing_body_when_topup_then_rejected_as_code_invalid_not_npe() {
        // @RequestBody(required=false) 空体：按码缺失拦（BILL_019），不 NPE/500
        assertThatThrownBy(() -> appService.topupAccount(ACCOUNT_ID, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.ACCESS_CODE_INVALID.message());
        assertThatThrownBy(() -> appService.topupProject(ACCOUNT_ID, PROJECT_ID, null))
                .isInstanceOf(ApplicationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_pools", Integer.class)).isZero();
    }

    @Test
    void given_non_positive_amount_when_topup_then_rejected_by_guard() {
        assertThatThrownBy(() -> appService.topupAccount(ACCOUNT_ID,
                new CheckoutTopupCommand(VALID_CODE, 0L)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.TOPUP_AMOUNT_INVALID.message());
        assertThatThrownBy(() -> appService.topupAccount(ACCOUNT_ID,
                new CheckoutTopupCommand(VALID_CODE, null)))
                .isInstanceOf(ApplicationException.class);
        // 金额守卫先于入账：无池无流水
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_pools", Integer.class)).isZero();
    }
}
