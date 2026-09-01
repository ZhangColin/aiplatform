package com.aieducenter.aiplatform.business.order.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 支付端口（#30 交易环③）：真实支付渠道接入的切换边界（ADR 0003——一个接口一个
 * 实现的最便宜缝，不建运行时注册表）。v1 唯一实现 = 平台内 mock（同步只走成功
 * 路径）；真实接入（#32 收尾②）换适配器：出站签名创建支付（二维码模式、
 * businessOrderNo = 订单号）、notifyUrl 幂等回调 + 主动查单兜底——真实渠道的
 * 异步中间态在适配器内吸收，订单状态机不见「支付中」。
 */
@Port(PortType.CLIENT)
public interface PaymentPort {

    /**
     * 执行支付并返回支付流水号（落 {@code ord_orders.payment_no}；真实接入为
     * 渠道单号）。v1 mock 恒同步成功；真实适配器的失败/超时路径归 #32。
     *
     * @param orderId  订单号（真实接入作 businessOrderNo）
     * @param amount   总价（分，与订单现值同源）
     * @param currency ISO 4217（v1 恒 CNY）
     */
    String pay(Long orderId, Long amount, String currency);
}
