package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后台动作留痕行聚合不变量（#535 计费⑤）：append-only 行格——入账类金额恒正、
 * 调整类非零带符号＋原因必填、源调整不动池（池锚/金额/前后余额全 NULL）＋旧/新
 * 源快照明细、操作者两列落空口径。
 */
class OperatorActionTest {

    private static final Operator OPERATOR = new Operator("900535", "运营·账务");

    @Test
    void given_topup_action_then_amount_positive_and_operator_persisted() {
        OperatorAction action = OperatorAction.topup(OperatorActionType.GRANT,
                936_101L, null, 42L, 10_000, 0, 10_000, null, OPERATOR);

        assertThat(action.getActionType()).isEqualTo(OperatorActionType.GRANT);
        assertThat(action.getAmount()).isEqualTo(10_000L);
        assertThat(action.getBalanceBefore()).isZero();
        assertThat(action.getBalanceAfter()).isEqualTo(10_000L);
        assertThat(action.getOperatorId()).isEqualTo("900535");
        assertThat(action.getOperatorName()).isEqualTo("运营·账务");
        assertThat(action.getDetail()).isNull(); // 空明细归一 NULL
    }

    @Test
    void given_topup_with_wrong_type_or_nonpositive_amount_then_rejected() {
        assertThatThrownBy(() -> OperatorAction.topup(OperatorActionType.BALANCE_ADJUST,
                1L, null, 1L, 100, 0, 100, null, OPERATOR))
                .hasMessageContaining(BillingMessage.OPERATOR_ACTION_INVALID.message());
        assertThatThrownBy(() -> OperatorAction.topup(OperatorActionType.GRANT,
                1L, null, 1L, 0, 0, 0, null, OPERATOR))
                .hasMessageContaining(BillingMessage.TOPUP_AMOUNT_INVALID.message());
    }

    @Test
    void given_balance_adjust_then_signed_amount_and_reason_in_detail() {
        OperatorAction action = OperatorAction.balanceAdjust(1L, null, 7L,
                -5_000, 80_000, 75_000, "重复扣减纠错", OPERATOR);

        assertThat(action.getActionType()).isEqualTo(OperatorActionType.BALANCE_ADJUST);
        assertThat(action.getAmount()).isEqualTo(-5_000L);
        assertThat(action.getBalanceBefore()).isEqualTo(80_000L);
        assertThat(action.getBalanceAfter()).isEqualTo(75_000L);
        assertThat(action.getDetail()).containsEntry("reason", "重复扣减纠错");
    }

    @Test
    void given_balance_adjust_zero_or_blank_reason_then_rejected() {
        assertThatThrownBy(() -> OperatorAction.balanceAdjust(1L, null, 7L,
                0, 0, 0, "原因", OPERATOR))
                .hasMessageContaining(BillingMessage.ADJUSTMENT_AMOUNT_INVALID.message());
        assertThatThrownBy(() -> OperatorAction.balanceAdjust(1L, null, 7L,
                -100, 0, -100, " ", OPERATOR))
                .hasMessageContaining(BillingMessage.ENTRY_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_source_reselect_then_no_pool_anchors_and_snapshot_detail() {
        OperatorAction action = OperatorAction.sourceReselect(936_001L,
                DeductionSource.ACCOUNT, 936_101L, DeductionSource.PROJECT, null, OPERATOR);

        assertThat(action.getActionType()).isEqualTo(OperatorActionType.SOURCE_RESELECT);
        assertThat(action.getPoolId()).isNull();
        assertThat(action.getAmount()).isNull();
        assertThat(action.getBalanceBefore()).isNull();
        assertThat(action.getBalanceAfter()).isNull();
        Map<String, Object> detail = action.getDetail();
        assertThat(detail).isNotNull();
        assertThat(detail.get("oldSource")).isEqualTo(1);
        assertThat(detail.get("newSource")).isEqualTo(2);
        assertThat(detail.get("newAccountId")).isNull();
    }

    @Test
    void given_source_reselect_from_trial_then_old_source_null_and_rejected_cases() {
        OperatorAction action = OperatorAction.sourceReselect(936_001L,
                null, null, DeductionSource.ACCOUNT, 936_101L, OPERATOR);
        assertThat(action.getDetail())
                .containsEntry("oldSource", null)
                .containsEntry("newSource", 1);

        assertThatThrownBy(() -> OperatorAction.sourceReselect(null,
                null, null, DeductionSource.PROJECT, null, OPERATOR))
                .hasMessageContaining(BillingMessage.PROJECT_BILLING_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> OperatorAction.sourceReselect(936_001L,
                null, null, null, null, OPERATOR))
                .hasMessageContaining(BillingMessage.PROJECT_BILLING_FIELDS_INCOMPLETE.message());
    }
}
