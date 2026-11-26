package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.semgravio.aiplatform.business.billing.domain.enums.BalanceHoldLane;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 余额提醒留存聚合不变量（#537 计费③，ADR-0047 段间硬门）：付费项目段边界余额
 * 检查不过时被拦输入留存——充值回正自动放行（resume）、新拦取代旧待决（supersede）、
 * 字段完整性守卫（BILL_026）。#610 设计线整条退役：作用域改稿车道与件序/发散度
 * 留存列拆除。
 */
class BalanceHoldTest {

    @Test
    void given_valid_input_when_pending_then_hold_pending_with_payload() {
        BalanceHold hold = BalanceHold.pendingOf(930_001L, BalanceHoldLane.OPINION,
                "把报表加个导出", List.of(Map.of("attachmentType", "note",
                        "anchor", "圈注锚")), "run-1");

        assertThat(hold.pending()).isTrue();
        assertThat(hold.getProjectId()).isEqualTo(930_001L);
        assertThat(hold.getLane()).isEqualTo(BalanceHoldLane.OPINION);
        assertThat(hold.getPrompt()).isEqualTo("把报表加个导出");
        assertThat(hold.getAttachments()).hasSize(1);
        assertThat(hold.getRunId()).isEqualTo("run-1");
    }

    @Test
    void given_pending_when_resume_then_resumed_and_not_pending() {
        BalanceHold hold = BalanceHold.pendingOf(930_001L, BalanceHoldLane.INQUIRY,
                "系统访问地址是什么", null, "run-3");

        hold.resume();

        assertThat(hold.pending()).isFalse();
        assertThat(hold.getStatus().name()).isEqualTo("RESUMED");
    }

    @Test
    void given_resumed_when_resume_again_then_rejected() {
        BalanceHold hold = BalanceHold.pendingOf(930_001L, BalanceHoldLane.OPINION,
                "再改一个地方", null, "run-4");
        hold.resume();

        assertThatThrownBy(hold::resume)
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.BALANCE_HOLD_NOT_PENDING.message());
    }

    @Test
    void given_pending_when_supersede_then_superseded_and_idempotent() {
        BalanceHold hold = BalanceHold.pendingOf(930_001L, BalanceHoldLane.OPINION,
                "旧输入", null, "run-5");

        hold.supersede();
        hold.supersede(); // 幂等：已非待决不再动

        assertThat(hold.pending()).isFalse();
    }

    @Test
    void given_incomplete_fields_when_pending_then_rejected() {
        assertThatThrownBy(() -> BalanceHold.pendingOf(null, BalanceHoldLane.OPINION, "x", null, "r"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.BALANCE_HOLD_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> BalanceHold.pendingOf(930_001L, null, "x", null, "r"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.BALANCE_HOLD_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> BalanceHold.pendingOf(930_001L, BalanceHoldLane.OPINION, " ", null, "r"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.BALANCE_HOLD_FIELDS_INCOMPLETE.message());
        assertThatThrownBy(() -> BalanceHold.pendingOf(930_001L, BalanceHoldLane.OPINION, "x", null, " "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.BALANCE_HOLD_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_empty_attachments_when_pending_then_null_payload() {
        BalanceHold hold = BalanceHold.pendingOf(930_001L, BalanceHoldLane.OPINION,
                "纯文字输入", List.of(), "run-6");

        assertThat(hold.getAttachments()).isNull();
    }
}
