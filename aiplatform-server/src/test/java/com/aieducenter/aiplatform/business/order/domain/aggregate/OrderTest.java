package com.aieducenter.aiplatform.business.order.domain.aggregate;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.DomainException;

import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单聚合状态机（#37/#39 支付原子化）：{@link Order#pay}（待支付 → 已支付，落
 * paidAt/paymentNo）与 {@link Order#archive}（已支付 → 已归档，落 archivedAt）的
 * 转移与守卫——「已支付」为真实落库中间态，归档是支付后的独立步骤。运营取消
 * （{@link Order#cancelByBackoffice}，#157）与运营重试归档
 * （{@link Order#archiveByBackoffice}，#158）在此钉守卫与留痕：状态守卫各复用
 * {@link Order#cancel}（ORD_005）/ {@link Order#archive}（ORD_012），差异仅在
 * 必填原因（取消，ORD_013/014）＋操作者两列（支付链自动归档/用户取消恒空）。
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

    // ---------- #157：运营取消——必填原因＋操作者落痕（守卫与用户取消同构） ----------

    @Test
    void given_unpaid_order_when_cancel_by_backoffice_then_cancelled_with_trace() {
        // 待报价/已报价两未支付态都可达，落原因＋操作者＋取消时点
        for (Order order : List.of(pendingQuoteOrder(), quotedOrder())) {
            order.cancelByBackoffice("用户改需求，终止报价流程",
                    new Operator("700100", "运营·小刘"));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancelledAt()).isNotNull();
            assertThat(order.getCancelReason()).isEqualTo("用户改需求，终止报价流程");
            assertThat(order.getCancelOperatorId()).isEqualTo("700100");
            assertThat(order.getCancelOperatorName()).isEqualTo("运营·小刘");
        }
    }

    @Test
    void given_non_unpaid_order_when_cancel_by_backoffice_then_rejected() {
        // 守卫与用户取消同一处（cancel()）：已支付/已归档/已取消一律 ORD_005
        for (Order order : List.of(paidOrder(), archivedOrder(), cancelledOrder())) {
            assertThatThrownBy(() -> order.cancelByBackoffice("迟到", null))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.ORDER_CANCEL_NOT_ALLOWED.message());
            assertThat(order.getCancelReason()).isNull(); // 留痕不落半截
        }
    }

    @Test
    void given_blank_reason_when_cancel_by_backoffice_then_rejected() {
        for (String reason : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> pendingQuoteOrder().cancelByBackoffice(reason, null))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.ORDER_CANCEL_REASON_REQUIRED.message());
        }
    }

    @Test
    void given_overlong_reason_when_cancel_by_backoffice_then_rejected() {
        String overlong = "长".repeat(Order.CANCEL_REASON_MAX_LENGTH + 1);

        assertThatThrownBy(() -> pendingQuoteOrder().cancelByBackoffice(overlong, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(OrderMessage.ORDER_CANCEL_REASON_TOO_LONG.message());
    }

    // ---------- #158：运营重试归档——操作者落痕（守卫与支付链自动归档同构） ----------

    @Test
    void given_paid_order_when_archive_by_backoffice_then_archived_with_trace() {
        Order order = paidOrder();

        order.archiveByBackoffice(new Operator("700100", "运营·小刘"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ARCHIVED);
        assertThat(order.getArchivedAt()).isNotNull();
        assertThat(order.getArchiveOperatorId()).isEqualTo("700100");
        assertThat(order.getArchiveOperatorName()).isEqualTo("运营·小刘");
    }

    @Test
    void given_paid_order_when_payment_chain_archive_then_operator_columns_stay_null() {
        // 支付链自动归档走 archive()（无人工触发）：留痕两列恒空——落空口径结构性保证
        Order order = paidOrder();

        order.archive();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ARCHIVED);
        assertThat(order.getArchiveOperatorId()).isNull();
        assertThat(order.getArchiveOperatorName()).isNull();
    }

    @Test
    void given_non_paid_order_when_archive_by_backoffice_then_rejected() {
        // 守卫与支付链自动归档同一处（archive()）：非已支付一律 ORD_012，留痕不落半截
        for (Order order : List.of(pendingQuoteOrder(), quotedOrder(), archivedOrder(), cancelledOrder())) {
            assertThatThrownBy(() -> order.archiveByBackoffice(new Operator("700100", "运营·小刘")))
                    .isInstanceOf(DomainException.class)
                    .hasMessageContaining(OrderMessage.ORDER_ARCHIVE_NOT_ALLOWED.message());
            assertThat(order.getArchiveOperatorId()).isNull();
        }
    }

    @Test
    void given_missing_operator_headers_when_archive_by_backoffice_then_null_trace() {
        Order order = paidOrder();

        order.archiveByBackoffice(null); // 缺透传头 → 落空口径

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ARCHIVED);
        assertThat(order.getArchiveOperatorId()).isNull();
        assertThat(order.getArchiveOperatorName()).isNull();
    }

    // ---------- #155：报价操作者随价目行落痕（append-only 追加序不变） ----------

    @Test
    void given_operator_when_quote_then_entry_carries_operator_and_null_stays_empty() {
        Order order = pendingQuoteOrder();

        order.quote(128000L, "首版报价", null); // 无操作者（落空口径）
        order.quote(99000L, "改价", new Operator("700100", " 运营·小刘 "));

        // 纯域无持久化（id 未生），按追加序断言（新→旧排序归持久化层测试）：
        // 改价行带操作者（空白已归一），首报行操作者为空
        assertThat(order.getPriceEntries()).hasSize(2);
        assertThat(order.getPriceEntries().get(0).getOperatorId()).isNull();
        assertThat(order.getPriceEntries().get(0).getOperatorName()).isNull();
        assertThat(order.getPriceEntries().get(1).getOperatorId()).isEqualTo("700100");
        assertThat(order.getPriceEntries().get(1).getOperatorName()).isEqualTo("运营·小刘");
    }

    // ---------- 夹具（经公共入口驱动到目标态，不绕私有状态） ----------

    private static Order pendingQuoteOrder() {
        return Order.place(1L, null, "# PRD\n\n需求");
    }

    private static Order quotedOrder() {
        Order order = pendingQuoteOrder();
        order.quote(128000L, "首版报价", null);
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
