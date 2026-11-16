package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.semgravio.aiplatform.business.billing.domain.enums.EntryKind;
import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 余额池与流水行聚合不变量（#531 计费①）：池归属互斥建池校验、金额符号守卫
 * （入账恒正/扣减恒正入参/调整非零）、负余额软模型（扣成负合法）、流水行行格
 * （金额符号约定＋扣减行快照必带＋操作者留痕两列）。
 */
class BillingPoolAndEntryTest {

    private static final long POOL_ID = 910_001L;
    private static final long PROJECT_ID = 910_002L;
    private static final long ACCOUNT_ID = 910_003L;

    @Test
    void given_new_pools_when_read_then_zero_balance_cNY_and_exclusive_owner() {
        BillingPool account = BillingPool.forAccount(ACCOUNT_ID);
        BillingPool project = BillingPool.forProject(PROJECT_ID);

        assertThat(account.getKind()).isEqualTo(PoolKind.ACCOUNT);
        assertThat(account.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(account.getProjectId()).isNull();
        assertThat(project.getKind()).isEqualTo(PoolKind.PROJECT);
        assertThat(project.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(project.getBalance()).isZero();
        assertThat(account.getCurrency()).isEqualTo(BillingPool.CURRENCY_CNY);
    }

    @Test
    void given_pool_without_owner_when_create_then_rejected() {
        assertThatThrownBy(() -> BillingPool.forAccount(null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.POOL_TARGET_INVALID.message());
        assertThatThrownBy(() -> BillingPool.forProject(null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.POOL_TARGET_INVALID.message());
    }

    @Test
    void given_pool_when_deposit_then_balance_grows_and_invalid_amount_rejected() {
        BillingPool pool = BillingPool.forProject(PROJECT_ID);

        pool.deposit(1_000);

        assertThat(pool.getBalance()).isEqualTo(1_000);
        assertThatThrownBy(() -> pool.deposit(0))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.TOPUP_AMOUNT_INVALID.message());
        assertThatThrownBy(() -> pool.deposit(-5))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.TOPUP_AMOUNT_INVALID.message());
    }

    @Test
    void given_pool_with_funds_when_deduct_beyond_balance_then_negative_allowed() {
        BillingPool pool = BillingPool.forProject(PROJECT_ID);
        pool.deposit(500);

        pool.deduct(800); // 段中自由：扣成负照落（负余额软模型）

        assertThat(pool.getBalance()).isEqualTo(-300);
        assertThat(pool.positive()).isFalse();
        assertThatThrownBy(() -> pool.deduct(0))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.DEDUCTION_AMOUNT_INVALID.message());
    }

    @Test
    void given_pool_when_adjust_then_signed_and_zero_rejected() {
        BillingPool pool = BillingPool.forProject(PROJECT_ID);
        pool.deposit(1_000);

        pool.adjust(-400);
        pool.adjust(100);

        assertThat(pool.getBalance()).isEqualTo(700);
        assertThatThrownBy(() -> pool.adjust(0))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.ADJUSTMENT_AMOUNT_INVALID.message());
    }

    @Test
    void given_deduction_when_build_entry_then_negative_amount_and_full_row_shape() {
        BillingEntry entry = BillingEntry.deduction(POOL_ID, PROJECT_ID,
                SegmentType.MAIN_TURN, "turn-run-1", 250, -300,
                Map.of("rate", java.math.BigDecimal.ONE));

        assertThat(entry.getKind()).isEqualTo(EntryKind.DEDUCTION);
        // 扣减行金额恒负（正入参、行上落负）；行后余额快照如实（可负）
        assertThat(entry.getAmount()).isEqualTo(-250);
        assertThat(entry.getBalanceAfter()).isEqualTo(-300);
        assertThat(entry.getSegmentType()).isEqualTo(SegmentType.MAIN_TURN);
        assertThat(entry.getSegmentRef()).isEqualTo("turn-run-1");
        assertThat(entry.getSnapshot()).isNotEmpty();
        assertThat(entry.getCurrency()).isEqualTo(BillingPool.CURRENCY_CNY);
    }

    @Test
    void given_deduction_without_snapshot_or_ref_when_build_then_rejected() {
        assertThatThrownBy(() -> BillingEntry.deduction(POOL_ID, PROJECT_ID,
                SegmentType.GENERATION_RUN, " ", 250, 0, Map.of("rate", "1")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.ENTRY_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> BillingEntry.deduction(POOL_ID, PROJECT_ID,
                SegmentType.MAIN_TURN, "run-1", 250, 0, Map.of()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.ENTRY_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_topup_and_adjustment_when_build_then_sign_and_trace_conventions() {
        Operator operator = new Operator("9123456789", "运营乙");
        BillingEntry topup = BillingEntry.topup(POOL_ID, PROJECT_ID, 1_000, 1_000,
                operator, "赠送入账");
        BillingEntry selfTopup = BillingEntry.topup(POOL_ID, null, 500, 1_500,
                null, null);
        BillingEntry adjustment = BillingEntry.adjustment(POOL_ID, PROJECT_ID, -200, 800,
                operator, "平台故障返还冲正");

        assertThat(topup.getAmount()).isEqualTo(1_000);
        assertThat(topup.getOperatorId()).isEqualTo("9123456789");
        assertThat(topup.getNote()).isEqualTo("赠送入账");
        // 收银台自助充值无操作者：两列落空
        assertThat(selfTopup.getOperatorId()).isNull();
        assertThat(selfTopup.getOperatorName()).isNull();
        assertThat(selfTopup.getProjectId()).isNull();
        // 调整带符号＋原因必填
        assertThat(adjustment.getAmount()).isEqualTo(-200);
        assertThat(adjustment.getKind()).isEqualTo(EntryKind.ADJUSTMENT);
        assertThatThrownBy(() -> BillingEntry.adjustment(POOL_ID, PROJECT_ID, -200, 800,
                operator, " "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.ENTRY_FIELDS_INCOMPLETE.message());
    }
}
