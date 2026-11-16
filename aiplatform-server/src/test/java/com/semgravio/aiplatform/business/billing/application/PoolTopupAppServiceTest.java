package com.semgravio.aiplatform.business.billing.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.PoolTopupAppService.PoolSnapshot;
import com.semgravio.aiplatform.business.billing.application.PoolTopupAppService.TopupReceipt;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;
import com.semgravio.aiplatform.business.billing.domain.enums.EntryKind;
import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双池入账用例集成测试（#531 计费①）：池惰性建、append-only 流水（行后余额＋
 * 操作者留痕）、<b>向项目池入账即选定项目扣减源</b>（动作即选择——销售演示动线
 * 的机制面）、目标互斥与金额守卫。
 */
@IntegrationTest
class PoolTopupAppServiceTest {

    private static final long PROJECT_ID = 921_001L;
    private static final long ACCOUNT_ID = 921_101L;

    @Autowired
    private PoolTopupAppService appService;

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
    void given_no_pool_when_project_topup_then_pool_created_and_source_auto_selected() {
        TopupReceipt receipt = appService.topup(null, PROJECT_ID, 10_000,
                new Operator("9133333333", "运营甲"), "后台赠送入项目池");

        // 池惰性建（首笔入账才出现）
        assertThat(appService.projectPoolOf(PROJECT_ID)).hasValueSatisfying(pool -> {
            assertThat(pool.kind()).isEqualTo(PoolKind.PROJECT);
            assertThat(pool.balance()).isEqualTo(10_000L);
        });
        // 入账即选择：扣减源已定为项目余额（前台零呈现的机制面）
        var billing = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_operator_id FROM bil_project_billing"
                        + " WHERE project_id = ?", PROJECT_ID);
        assertThat(billing.get("deduction_source")).isEqualTo(2); // PROJECT
        assertThat(billing.get("source_operator_id")).isEqualTo("9133333333");
        // 流水行：金额恒正＋行后余额＋操作者留痕
        List<BillingEntry> entries = entryRepository.findByPoolIdOrderByIdAsc(receipt.poolId());
        assertThat(entries).hasSize(1);
        BillingEntry entry = entries.get(0);
        assertThat(entry.getKind()).isEqualTo(EntryKind.TOPUP);
        assertThat(entry.getAmount()).isEqualTo(10_000L);
        assertThat(entry.getBalanceAfter()).isEqualTo(10_000L);
        assertThat(entry.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(entry.getOperatorId()).isEqualTo("9133333333");
        assertThat(entry.getNote()).isEqualTo("后台赠送入项目池");
    }

    @Test
    void given_self_topup_without_operator_then_trace_columns_null() {
        TopupReceipt receipt = appService.topup(ACCOUNT_ID, null, 500, null, null);

        // 账户池入账不触碰扣减源（无 billing 行）；无操作者两列落空（收银台自助形态）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_project_billing", Integer.class)).isZero();
        List<BillingEntry> entries = entryRepository.findByPoolIdOrderByIdAsc(receipt.poolId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getOperatorId()).isNull();
        assertThat(entries.get(0).getOperatorName()).isNull();
        assertThat(entries.get(0).getProjectId()).isNull();
    }

    @Test
    void given_existing_pool_when_second_topup_then_accumulates_with_balance_trail() {
        TopupReceipt first = appService.topup(null, PROJECT_ID, 3_000, null, null);
        TopupReceipt second = appService.topup(null, PROJECT_ID, 2_000, null, null);

        assertThat(second.poolId()).isEqualTo(first.poolId());
        assertThat(appService.projectPoolOf(PROJECT_ID))
                .hasValueSatisfying(pool -> assertThat(pool.balance()).isEqualTo(5_000L));
        // append-only 余额轨迹：行后余额串起池现值
        List<BillingEntry> entries = entryRepository.findByPoolIdOrderByIdAsc(first.poolId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getBalanceAfter()).isEqualTo(3_000L);
        assertThat(entries.get(1).getBalanceAfter()).isEqualTo(5_000L);
        // 已是项目源：重复入账不触发源改写（source_selected_at 不刷新）
        var billing = jdbcTemplate.queryForMap(
                "SELECT source_selected_at FROM bil_project_billing WHERE project_id = ?",
                PROJECT_ID);
        assertThat(billing.get("source_selected_at")).isNotNull();
    }

    @Test
    void given_user_selected_account_when_backoffice_project_topup_then_source_realigned() {
        jdbcTemplate.update("INSERT INTO bil_project_billing (id, project_id, deduction_source,"
                + " source_account_id, source_selected_at, created_at, updated_at)"
                + " VALUES (?, ?, 1, ?, now(), now(), now())", 921_900L, PROJECT_ID, ACCOUNT_ID);

        appService.topup(null, PROJECT_ID, 1_000, new Operator("9144444444", "运营乙"), null);

        // 后台向项目池入账＝动作即选择：账户源被对齐为项目源＋操作者留痕
        var billing = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_account_id, source_operator_id"
                        + " FROM bil_project_billing WHERE project_id = ?", PROJECT_ID);
        assertThat(billing.get("deduction_source")).isEqualTo(2);
        assertThat(billing.get("source_account_id")).isNull();
        assertThat(billing.get("source_operator_id")).isEqualTo("9144444444");
    }

    @Test
    void given_account_source_project_when_self_service_topup_then_source_not_changed() {
        jdbcTemplate.update("INSERT INTO bil_project_billing (id, project_id, deduction_source,"
                + " source_account_id, source_selected_at, created_at, updated_at)"
                + " VALUES (?, ?, 1, ?, now(), now(), now())", 921_901L, PROJECT_ID, ACCOUNT_ID);

        // 自助充值（无操作者＝收银台形态）不改源：整笔拒绝（选定后不可自助更改）
        assertThatThrownBy(() -> appService.topup(null, PROJECT_ID, 1_000, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.SOURCE_ALREADY_SELECTED.message());
        // 拒绝是原子的：入账与流水同回滚，无半截账
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_entries", Integer.class)).isZero();
        assertThat(appService.projectPoolOf(PROJECT_ID)).isEmpty();
    }

    @Test
    void given_both_or_neither_target_when_topup_then_rejected() {
        assertThatThrownBy(() -> appService.topup(ACCOUNT_ID, PROJECT_ID, 100, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.POOL_TARGET_INVALID.message());
        assertThatThrownBy(() -> appService.topup(null, null, 100, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.POOL_TARGET_INVALID.message());
        assertThatThrownBy(() -> appService.topup(null, PROJECT_ID, 0, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.TOPUP_AMOUNT_INVALID.message());
    }

    @Test
    void given_no_pool_when_query_then_empty() {
        assertThat(appService.accountPoolOf(ACCOUNT_ID)).isEmpty();
        assertThat(appService.projectPoolOf(PROJECT_ID)).isEmpty();

        // PoolSnapshot 形态钉一面（后续票消费）
        appService.topup(ACCOUNT_ID, null, 100, null, null);
        PoolSnapshot snapshot = appService.accountPoolOf(ACCOUNT_ID).orElseThrow();
        assertThat(snapshot.kind()).isEqualTo(PoolKind.ACCOUNT);
        assertThat(snapshot.balance()).isEqualTo(100L);
    }
}
