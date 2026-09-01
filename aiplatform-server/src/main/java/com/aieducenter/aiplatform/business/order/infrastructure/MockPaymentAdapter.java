package com.aieducenter.aiplatform.business.order.infrastructure;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.order.domain.port.PaymentPort;

/**
 * 平台内 mock 支付适配器（v1 唯一实现，#30）：不接任何渠道——生成支付流水号、
 * 同步返回成功（spec：只走成功路径，不模拟失败/超时）。真实渠道接入（#32）时
 * 本适配器整体替换，端口签名随二维码闭环 reshape（ADR 0003：git 历史即回退面）。
 */
@Adapter(PortType.CLIENT)
public class MockPaymentAdapter implements PaymentPort {

    /** 流水号前缀（与渠道单号区分；列宽 100 内）。 */
    private static final String PAYMENT_NO_PREFIX = "MOCK-";

    @Override
    public String pay(Long orderId, Long amount, String currency) {
        // TSID 时间有序：同单多次调用天然可辨（正常流仅一次，重复支付被聚合守卫拒绝）
        return PAYMENT_NO_PREFIX + TsidGenerator.newInstance().generate();
    }
}
