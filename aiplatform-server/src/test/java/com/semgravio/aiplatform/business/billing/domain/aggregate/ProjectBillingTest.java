package com.semgravio.aiplatform.business.billing.domain.aggregate;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目计费态聚合不变量（#531 计费①）：扣减源一次性选定（用户面不可改、后台
 * 可调整留痕）、付费状态翻转两门语义与不可逆、试用开局缺省。
 */
class ProjectBillingTest {

    private static final long PROJECT_ID = 900_001L;
    private static final long ACCOUNT_ID = 900_002L;

    @Test
    void given_fresh_billing_when_read_then_trial_and_unselected() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);

        assertThat(billing.paid()).isFalse();
        assertThat(billing.sourceSelected()).isFalse();
        assertThat(billing.getDeductionSource()).isNull();
        assertThat(billing.getFlippedAt()).isNull();
    }

    @Test
    void given_unselected_when_select_project_source_once_then_selected_and_still_trial() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);

        billing.selectSource(DeductionSource.PROJECT, null);

        assertThat(billing.sourceSelected()).isTrue();
        assertThat(billing.getDeductionSource()).isEqualTo(DeductionSource.PROJECT);
        assertThat(billing.getSourceAccountId()).isNull();
        assertThat(billing.getSourceSelectedAt()).isNotNull();
        // 源选定≠付费（两轨判定：源选定＋翻转才是付费）
        assertThat(billing.paid()).isFalse();
    }

    @Test
    void given_source_selected_when_user_selects_again_then_rejected() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);
        billing.selectSource(DeductionSource.PROJECT, null);

        assertThatThrownBy(() -> billing.selectSource(DeductionSource.ACCOUNT, ACCOUNT_ID))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.SOURCE_ALREADY_SELECTED.message());
    }

    @Test
    void given_account_source_without_account_then_rejected() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);

        assertThatThrownBy(() -> billing.selectSource(DeductionSource.ACCOUNT, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.SOURCE_ACCOUNT_REQUIRED.message());
    }

    @Test
    void given_account_source_selected_when_read_then_account_frozen() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);

        billing.selectSource(DeductionSource.ACCOUNT, ACCOUNT_ID);

        assertThat(billing.getSourceAccountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void given_user_selected_when_backoffice_reselects_then_applied_with_trace() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);
        billing.selectSource(DeductionSource.ACCOUNT, ACCOUNT_ID);

        billing.reselectSourceByBackoffice(DeductionSource.PROJECT, null,
                new Operator("9123456789", "运营甲"));

        // 后台调整＝救济通道：覆盖既有选定＋操作者留痕
        assertThat(billing.getDeductionSource()).isEqualTo(DeductionSource.PROJECT);
        assertThat(billing.getSourceAccountId()).isNull();
        assertThat(billing.getSourceOperatorId()).isEqualTo("9123456789");
        assertThat(billing.getSourceOperatorName()).isEqualTo("运营甲");
    }

    @Test
    void given_flip_without_source_then_rejected() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);

        assertThatThrownBy(() -> billing.flipToPaid(FlipGate.INTERCEPT))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.FLIP_SOURCE_REQUIRED.message());
    }

    @Test
    void given_source_selected_when_flip_via_either_gate_then_paid_with_gate_trace() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);
        billing.selectSource(DeductionSource.PROJECT, null);

        billing.flipToPaid(FlipGate.FORK);

        assertThat(billing.paid()).isTrue();
        assertThat(billing.getFlippedAt()).isNotNull();
        assertThat(billing.getFlipGate()).isEqualTo(FlipGate.FORK);

        // 门②同语义（另一实例走拦截卡门）
        ProjectBilling other = ProjectBilling.trialOf(900_003L);
        other.selectSource(DeductionSource.ACCOUNT, ACCOUNT_ID);
        other.flipToPaid(FlipGate.INTERCEPT);
        assertThat(other.getFlipGate()).isEqualTo(FlipGate.INTERCEPT);
        assertThat(other.paid()).isTrue();
    }

    @Test
    void given_already_paid_when_flip_again_then_rejected_irreversible() {
        ProjectBilling billing = ProjectBilling.trialOf(PROJECT_ID);
        billing.selectSource(DeductionSource.PROJECT, null);
        billing.flipToPaid(FlipGate.FORK);

        // 付费状态不可逆：同门/异门再翻一律拒绝
        assertThatThrownBy(() -> billing.flipToPaid(FlipGate.FORK))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.FLIP_ALREADY_PAID.message());
        assertThatThrownBy(() -> billing.flipToPaid(FlipGate.INTERCEPT))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.FLIP_ALREADY_PAID.message());
        // 翻转后源调整仍可达（救济通道不因付费关门）
        assertThatCode(() -> billing.reselectSourceByBackoffice(
                DeductionSource.ACCOUNT, ACCOUNT_ID, null)).doesNotThrowAnyException();
    }

    @Test
    void given_null_project_when_trial_of_then_rejected() {
        assertThatThrownBy(() -> ProjectBilling.trialOf(null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.PROJECT_BILLING_FIELDS_INCOMPLETE.message());
    }
}
