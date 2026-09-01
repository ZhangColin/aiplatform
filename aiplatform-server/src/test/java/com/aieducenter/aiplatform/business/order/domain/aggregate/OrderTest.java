package com.aieducenter.aiplatform.business.order.domain.aggregate;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单聚合状态机（#37/#39 支付原子化）：{@link Order#pay}（待支付 → 已支付，落
 * paidAt/paymentNo）与 {@link Order#archive}（已支付 → 已归档，落 archivedAt）的
 * 转移与守卫——「已支付」为真实落库中间态，归档是支付后的独立步骤。
 */
class OrderTest {

    @Test
    void given_quoted_order_when_pay_then_paid_with_timestamp_and_payment_no() {
        Order order = quotedOrder();

        order.pay("MOCK-123");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isNotNull();
        assertThat(order.getPaymentNo()).isEqualTo("MOCK-123");
        assertThat(order.getArchivedAt()).isNull(); // 归档是后续独立步骤，非一跳
        assertThat(order.isTerminal()).isFalse(); // PAID 非终态（未终结唯一索引仍覆盖）
    }

    @Test
    void given_non_quoted_order_when_pay_then_rejected() {
        for (Order order : List.of(pendingQuoteOrder(), paidOrder(), archivedOrder(), cancelledOrder())) {
            assertThatThrownBy(() -> order.pay("MOCK-X"))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.ORDER_PAY_NOT_ALLOWED.message());
        }
    }

    @Test
    void given_blank_payment_no_when_pay_then_rejected() {
        Order order = quotedOrder();

        assertThatThrownBy(() -> order.pay(" "))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.ORDER_FIELDS_INCOMPLETE.message());
    }

    @Test
    void given_paid_order_when_archive_then_archived_with_timestamp() {
        Order order = quotedOrder();
        order.pay("MOCK-123");

        order.archive();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ARCHIVED);
        assertThat(order.getArchivedAt()).isNotNull();
        assertThat(order.getPaidAt()).isNotNull(); // 支付事实先于归档落定
        assertThat(order.isTerminal()).isTrue();
    }

    @Test
    void given_non_paid_order_when_archive_then_rejected() {
        for (Order order : List.of(pendingQuoteOrder(), quotedOrder(), archivedOrder(), cancelledOrder())) {
            assertThatThrownBy(order::archive)
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.ORDER_ARCHIVE_NOT_ALLOWED.message());
        }
    }

    // ---------- 夹具（经公共入口驱动到目标态，不绕私有状态） ----------

    private static Order pendingQuoteOrder() {
        return Order.place(1L, null, "# PRD\n\n需求");
    }

    private static Order quotedOrder() {
        Order order = pendingQuoteOrder();
        order.quote(128000L, "首版报价");
        return order;
    }

    private static Order paidOrder() {
        Order order = quotedOrder();
        order.pay("MOCK-123");
        return order;
    }

    private static Order archivedOrder() {
        Order order = paidOrder();
        order.archive();
        return order;
    }

    private static Order cancelledOrder() {
        Order order = pendingQuoteOrder();
        order.cancel();
        return order;
    }
}
