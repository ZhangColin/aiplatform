package com.semgravio.aiplatform.business.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.BackofficeBillingAppService.ActionReceipt;
import com.semgravio.aiplatform.business.billing.domain.aggregate.OperatorAction;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;
import com.semgravio.aiplatform.business.billing.domain.repository.OperatorActionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后台账户运营用例集成测试（#535 计费⑤）：四动作＋参数调整的服务面行为——
 * append-only 留痕行（操作者＋变更前后快照）与账务变动<b>同事务</b>成对落、
 * 凭证核验必填守卫、池存在守卫、动作即选源、扣减源覆盖留痕（旧/新快照）。
 */
@IntegrationTest
class BackofficeBillingAppServiceTest {

    private static final long PROJECT_ID = 935_001L;
    private static final long ACCOUNT_ID = 935_101L;
    private static final Operator OPERATOR = new Operator("900535", "运营·账务");

    @Autowired
    private BackofficeBillingAppService appService;

    @Autowired
    private OperatorActionRepository actionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_operator_actions");
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
        jdbcTemplate.update("UPDATE bil_params SET param_value = 1, operator_id = NULL,"
                + " operator_name = NULL WHERE param_key = 'conversion_rate'");
    }

    // ---------- 赠送 ----------

    @Test
    void given_no_pool_when_grant_to_account_then_entry_and_action_persisted_with_snapshots() {
        ActionReceipt receipt = appService.grant(ACCOUNT_ID, null, 50_000,
                "活动赠送", OPERATOR);

        // 三锚回执（留痕行/流水行/池现值一次带回）
        assertThat(receipt.balanceAfter()).isEqualTo(50_000L);
        List<OperatorAction> actions = actionRepository.findAll();
        assertThat(actions).hasSize(1);
        OperatorAction action = actions.get(0);
        assertThat(action.getActionType()).isEqualTo(OperatorActionType.GRANT);
        assertThat(action.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(action.getProjectId()).isNull();
        assertThat(action.getAmount()).isEqualTo(50_000L);
        assertThat(action.getBalanceBefore()).isZero(); // 惰性建池：变更前＝0
        assertThat(action.getBalanceAfter()).isEqualTo(50_000L);
        assertThat(action.getOperatorId()).isEqualTo("900535");
        assertThat(action.getOperatorName()).isEqualTo("运营·账务");
        // 流水行同落（入账口复用——append-only 两表同事实）
        Map<String, Object> entry = jdbcTemplate.queryForMap(
                "SELECT kind, amount, balance_after, operator_id FROM bil_entries WHERE id = ?",
                receipt.entryId());
        assertThat(entry.get("kind")).isEqualTo(1);
        assertThat(entry.get("amount")).isEqualTo(50_000L);
        assertThat(entry.get("balance_after")).isEqualTo(50_000L);
        assertThat(entry.get("operator_id")).isEqualTo("900535");
    }

    @Test
    void given_grant_to_project_then_source_selected_and_action_anchored() {
        appService.grant(null, PROJECT_ID, 10_000, "演示赠送", OPERATOR);

        // 动作即选择：扣减源已选定项目池（两轨判定读点立即可查）
        Map<String, Object> billing = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_operator_id FROM bil_project_billing"
                        + " WHERE project_id = ?", PROJECT_ID);
        assertThat(billing.get("deduction_source")).isEqualTo(2);
        assertThat(billing.get("source_operator_id")).isEqualTo("900535");
        // 动作行项目锚＋前后快照
        OperatorAction action = actionRepository.findAll().get(0);
        assertThat(action.getActionType()).isEqualTo(OperatorActionType.GRANT);
        assertThat(action.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(action.getBalanceBefore()).isZero();
        assertThat(action.getBalanceAfter()).isEqualTo(10_000L);
    }

    @Test
    void given_grant_target_ambiguous_or_amount_nonpositive_then_rejected() {
        assertThatThrownBy(() -> appService.grant(ACCOUNT_ID, PROJECT_ID, 100, null, OPERATOR))
                .hasMessageContaining(BillingMessage.POOL_TARGET_INVALID.message());
        assertThatThrownBy(() -> appService.grant(null, null, 100, null, OPERATOR))
                .hasMessageContaining(BillingMessage.POOL_TARGET_INVALID.message());
        assertThatThrownBy(() -> appService.grant(ACCOUNT_ID, null, 0, null, OPERATOR))
                .hasMessageContaining(BillingMessage.TOPUP_AMOUNT_INVALID.message());
        // 零副作用：守卫先行的负例不落任何行
        assertThat(actionRepository.findAll()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_pools", Integer.class))
                .isZero();
    }

    // ---------- 手动充值录入（仿凭证核验留痕） ----------

    @Test
    void given_voucher_complete_when_manual_topup_then_action_detail_carries_voucher() {
        Instant receivedAt = Instant.parse("2026-11-15T09:30:00Z");
        ActionReceipt receipt = appService.recordManualTopup(null, PROJECT_ID,
                200_000L, receivedAt, "银行回单 HX20261115001", null, OPERATOR);

        assertThat(receipt.balanceAfter()).isEqualTo(200_000L);
        OperatorAction action = actionRepository.findAll().get(0);
        assertThat(action.getActionType()).isEqualTo(OperatorActionType.MANUAL_TOPUP);
        assertThat(action.getAmount()).isEqualTo(200_000L);
        // 凭证核验三字段随明细落痕（JSONB 数值往返按 toString 口径钉死）
        assertThat(action.getDetail().get("receivedAmount").toString()).isEqualTo("200000");
        assertThat(action.getDetail())
                .containsEntry("receivedAt", "2026-11-15T09:30:00Z")
                .containsEntry("evidence", "银行回单 HX20261115001");
        // 项目池入账即选源＋备注缺省措辞
        Map<String, Object> entry = jdbcTemplate.queryForMap(
                "SELECT note FROM bil_entries WHERE id = ?", receipt.entryId());
        assertThat(entry.get("note")).isEqualTo("对公转账手动充值");
    }

    @Test
    void given_voucher_incomplete_when_manual_topup_then_rejected_and_nothing_written() {
        Instant at = Instant.parse("2026-11-15T09:30:00Z");
        // 缺实到时间／缺依据／缺实到金额：BILL_021 必填即拒（三字段单一口径）
        assertThatThrownBy(() -> appService.recordManualTopup(
                ACCOUNT_ID, null, 100_000L, null, "回单X", null, OPERATOR))
                .hasMessageContaining(BillingMessage.VOUCHER_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> appService.recordManualTopup(
                ACCOUNT_ID, null, 100_000L, at, " ", null, OPERATOR))
                .hasMessageContaining(BillingMessage.VOUCHER_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> appService.recordManualTopup(
                ACCOUNT_ID, null, 100_000L, at, null, null, OPERATOR))
                .hasMessageContaining(BillingMessage.VOUCHER_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> appService.recordManualTopup(
                ACCOUNT_ID, null, null, at, "回单X", null, OPERATOR))
                .hasMessageContaining(BillingMessage.VOUCHER_FIELDS_INCOMPLETE.message());
        // 实到金额非正：BILL_002
        assertThatThrownBy(() -> appService.recordManualTopup(
                ACCOUNT_ID, null, 0L, at, "回单X", null, OPERATOR))
                .hasMessageContaining(BillingMessage.TOPUP_AMOUNT_INVALID.message());
        // 零副作用
        assertThat(actionRepository.findAll()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_entries", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_pools", Integer.class))
                .isZero();
    }

    // ---------- 余额调整 ----------

    @Test
    void given_existing_pool_when_adjust_then_entry_and_action_persisted_with_sign() {
        appService.grant(ACCOUNT_ID, null, 50_000, null, OPERATOR);
        // 平台故障退款返还（负向调整＝从池里追回补记的人工线表述反例：返还＝正向补入）
        ActionReceipt refund = appService.adjustBalance(ACCOUNT_ID, null, 30_000,
                "平台故障返还（run #475 中断段）", OPERATOR);
        assertThat(refund.balanceAfter()).isEqualTo(80_000L);
        // 运营纠错追回（负向）
        ActionReceipt clawback = appService.adjustBalance(ACCOUNT_ID, null, -5_000,
                "重复扣减纠错", OPERATOR);
        assertThat(clawback.balanceAfter()).isEqualTo(75_000L);

        // 动作行：带符号金额＋前后快照（负向调整行 balanceBefore > balanceAfter）
        List<OperatorAction> actions = actionRepository.findAll();
        assertThat(actions).hasSize(3); // 入账 1＋调整 2
        OperatorAction adjust = actions.get(2);
        assertThat(adjust.getActionType()).isEqualTo(OperatorActionType.BALANCE_ADJUST);
        assertThat(adjust.getAmount()).isEqualTo(-5_000L);
        assertThat(adjust.getBalanceBefore()).isEqualTo(80_000L);
        assertThat(adjust.getBalanceAfter()).isEqualTo(75_000L);
        assertThat(adjust.getDetail()).containsEntry("reason", "重复扣减纠错");
        // 流水调整行同落（append-only——金额带符号、行后余额一致）
        Map<String, Object> entry = jdbcTemplate.queryForMap(
                "SELECT kind, amount, balance_after, operator_id, note FROM bil_entries WHERE id = ?",
                clawback.entryId());
        assertThat(entry.get("kind")).isEqualTo(3);
        assertThat(entry.get("amount")).isEqualTo(-5_000L);
        assertThat(entry.get("balance_after")).isEqualTo(75_000L);
        assertThat(entry.get("operator_id")).isEqualTo("900535");
        assertThat(entry.get("note")).isEqualTo("重复扣减纠错");
    }

    @Test
    void given_no_pool_or_bad_delta_when_adjust_then_rejected_and_nothing_written() {
        // 池不存在：BILL_022（调整不是入账）
        assertThatThrownBy(() -> appService.adjustBalance(null, PROJECT_ID, -1_000,
                "平台故障返还", OPERATOR))
                .hasMessageContaining(BillingMessage.POOL_NOT_FOUND.message());
        // 调整额为零：BILL_003；原因缺失：BILL_005
        appService.grant(ACCOUNT_ID, null, 10_000, null, OPERATOR);
        assertThatThrownBy(() -> appService.adjustBalance(ACCOUNT_ID, null, 0, "原因", OPERATOR))
                .hasMessageContaining(BillingMessage.ADJUSTMENT_AMOUNT_INVALID.message());
        assertThatThrownBy(() -> appService.adjustBalance(ACCOUNT_ID, null, -100, " ", OPERATOR))
                .hasMessageContaining(BillingMessage.ENTRY_FIELDS_INCOMPLETE.message());
        // 零副作用：三行都不多（仅入账一行）
        assertThat(actionRepository.findAll()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_entries", Integer.class))
                .isEqualTo(1);
    }

    // ---------- 扣减源调整 ----------

    @Test
    void given_trial_project_when_reselect_to_account_then_billing_updated_and_action_snapshots() {
        Long actionId = appService.reselectSource(PROJECT_ID, DeductionSource.ACCOUNT,
                ACCOUNT_ID, OPERATOR);

        // 计费态行：覆盖选定（后台语义——无既有选定也直接落）
        Map<String, Object> billing = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_account_id, source_operator_id"
                        + " FROM bil_project_billing WHERE project_id = ?", PROJECT_ID);
        assertThat(billing.get("deduction_source")).isEqualTo(1);
        assertThat(billing.get("source_account_id")).isEqualTo(ACCOUNT_ID);
        assertThat(billing.get("source_operator_id")).isEqualTo("900535");
        // 动作行：旧源 NULL（试用开局）→ 新源账户；不动池（三列空）
        OperatorAction action = actionRepository.findById(actionId).orElseThrow();
        assertThat(action.getActionType()).isEqualTo(OperatorActionType.SOURCE_RESELECT);
        assertThat(action.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(action.getPoolId()).isNull();
        assertThat(action.getAmount()).isNull();
        assertThat(action.getBalanceBefore()).isNull();
        assertThat(action.getBalanceAfter()).isNull();
        assertThat(action.getDetail())
                .containsEntry("oldSource", null)
                .containsEntry("newSource", 1);
        assertThat(action.getDetail().get("newAccountId").toString())
                .isEqualTo(Long.toString(ACCOUNT_ID));
    }

    @Test
    void given_account_source_when_reselect_to_project_then_old_source_in_snapshot() {
        appService.reselectSource(PROJECT_ID, DeductionSource.ACCOUNT, ACCOUNT_ID, OPERATOR);
        Long actionId = appService.reselectSource(PROJECT_ID, DeductionSource.PROJECT,
                null, OPERATOR);

        // 覆盖选定：账户定格清空（项目源恒 NULL）
        Map<String, Object> billing = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_account_id FROM bil_project_billing"
                        + " WHERE project_id = ?", PROJECT_ID);
        assertThat(billing.get("deduction_source")).isEqualTo(2);
        assertThat(billing.get("source_account_id")).isNull();
        // 动作行带旧源快照（可审计的变更前后）
        OperatorAction action = actionRepository.findById(actionId).orElseThrow();
        assertThat(action.getDetail())
                .containsEntry("oldSource", 1)
                .containsEntry("newSource", 2)
                .containsEntry("newAccountId", null);
        assertThat(action.getDetail().get("oldAccountId").toString())
                .isEqualTo(Long.toString(ACCOUNT_ID));
    }

    @Test
    void given_account_source_without_account_id_when_reselect_then_rejected() {
        assertThatThrownBy(() -> appService.reselectSource(PROJECT_ID,
                DeductionSource.ACCOUNT, null, OPERATOR))
                .hasMessageContaining(BillingMessage.SOURCE_ACCOUNT_REQUIRED.message());
        // 零副作用：计费态行与动作行都不落
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bil_project_billing", Integer.class)).isZero();
        assertThat(actionRepository.findAll()).isEmpty();
    }

    // ---------- 计费运营参数 ----------

    @Test
    void given_param_adjust_then_value_updated_with_operator() {
        appService.adjustParam("conversion_rate", new BigDecimal("1.5"), OPERATOR);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT param_value, operator_id, operator_name FROM bil_params"
                        + " WHERE param_key = 'conversion_rate'");
        assertThat(((BigDecimal) row.get("param_value"))).isEqualByComparingTo("1.5");
        assertThat(row.get("operator_id")).isEqualTo("900535");
        assertThat(row.get("operator_name")).isEqualTo("运营·账务");
    }

    @Test
    void given_unknown_key_or_nonpositive_value_when_adjust_param_then_rejected() {
        assertThatThrownBy(() -> appService.adjustParam("no_such_key", BigDecimal.ONE, OPERATOR))
                .hasMessageContaining(BillingMessage.PARAM_KEY_UNKNOWN.message());
        assertThatThrownBy(() -> appService.adjustParam("conversion_rate",
                BigDecimal.ZERO, OPERATOR))
                .hasMessageContaining(BillingMessage.PARAM_VALUE_INVALID.message());
    }
}
