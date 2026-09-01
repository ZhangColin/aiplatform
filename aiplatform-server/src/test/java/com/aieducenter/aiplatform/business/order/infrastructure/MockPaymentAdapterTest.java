package com.aieducenter.aiplatform.business.order.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mock 支付适配器（编写规范 §8.1：适配器测试必须、无需 Mock）：流水号格式与
 * 唯一性——v1 唯一可观察行为（同步恒成功，支付语义由应用服务测试背书）。
 */
class MockPaymentAdapterTest {

    private final MockPaymentAdapter adapter = new MockPaymentAdapter();

    @Test
    void given_any_order_when_pay_then_returns_mock_prefixed_payment_no() {
        String paymentNo = adapter.pay(900L, 128000L, "CNY");

        assertThat(paymentNo).startsWith("MOCK-");
        // TSID 十进制纯数字后缀（落 ord_orders.payment_no 的形态）
        assertThat(paymentNo.substring("MOCK-".length())).matches("\\d{10,}");
    }

    @Test
    void given_repeated_calls_when_pay_then_each_payment_no_distinct() {
        assertThat(adapter.pay(900L, 128000L, "CNY"))
                .isNotEqualTo(adapter.pay(900L, 128000L, "CNY"));
    }
}
