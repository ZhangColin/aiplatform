package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.semgravio.aiplatform.business.billing.domain.enums.InterceptLane;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 试用拦截留存聚合不变量（#533 计费②门②）：待决→已确认/被取代状态机（非待决
 * 确认＝BILL_017）、字段完整性守卫（BILL_018）、附件载荷原样留存。
 */
class InterceptHoldTest {

    @Test
    void given_valid_input_when_pending_then_hold_pending_with_payload() {
        InterceptHold hold = InterceptHold.pendingOf(920_001L, InterceptLane.OPINION,
                "把主色调改成绿色", List.of(Map.of("attachmentType", "image",
                        "name", "ref.png", "path", "materials/ref.png")), "run-1");

        assertThat(hold.pending()).isTrue();
        assertThat(hold.getProjectId()).isEqualTo(920_001L);
        assertThat(hold.getLane()).isEqualTo(InterceptLane.OPINION);
        assertThat(hold.getPrompt()).isEqualTo("把主色调改成绿色");
        assertThat(hold.getAttachments()).hasSize(1);
        assertThat(hold.getRunId()).isEqualTo("run-1");
    }

    @Test
    void given_pending_when_confirm_then_confirmed_and_not_pending() {
        InterceptHold hold = InterceptHold.pendingOf(920_001L, InterceptLane.FALLBACK,
                "你好呀", null, "run-2");

        hold.confirm();

        assertThat(hold.pending()).isFalse();
    }

    @Test
    void given_confirmed_when_confirm_again_then_rejected() {
        InterceptHold hold = InterceptHold.pendingOf(920_001L, InterceptLane.OPINION,
                "再改一个地方", null, "run-3");
        hold.confirm();

        assertThatThrownBy(hold::confirm)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.INTERCEPT_NOT_PENDING.message());
    }

    @Test
    void given_pending_when_supersede_then_superseded_and_idempotent() {
        InterceptHold hold = InterceptHold.pendingOf(920_001L, InterceptLane.OPINION,
                "旧输入", null, "run-4");

        hold.supersede();
        hold.supersede(); // 幂等：已非待决不再动

        assertThat(hold.pending()).isFalse();
    }

    @Test
    void given_incomplete_fields_when_pending_then_rejected() {
        assertThatThrownBy(() -> InterceptHold.pendingOf(null, InterceptLane.OPINION, "x", null, "r"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.INTERCEPT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> InterceptHold.pendingOf(920_001L, null, "x", null, "r"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.INTERCEPT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> InterceptHold.pendingOf(920_001L, InterceptLane.OPINION, " ", null, "r"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.INTERCEPT_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> InterceptHold.pendingOf(920_001L, InterceptLane.OPINION, "x", null, " "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.INTERCEPT_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_empty_attachments_when_pending_then_null_payload() {
        InterceptHold hold = InterceptHold.pendingOf(920_001L, InterceptLane.OPINION,
                "纯文字输入", List.of(), "run-5");

        assertThat(hold.getAttachments()).isNull();
    }
}
