package com.aieducenter.aiplatform.business.order.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 付款渠道类型（#337 渠道接口位最小形态，spec #320 实施序④）：v1 仅线下转账
 * 走通（凭证核验路径），扫码为占位枚举（渠道真接后启用）。「后续接渠道不大改」
 * 由本枚举＋节点上的渠道流水号可空字段承载——既有支付端口（PaymentPort）
 * 维持现状不加宽。
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）。</p>
 */
public enum PaymentChannelType implements BaseEnum<PaymentChannelType> {

    OFFLINE_TRANSFER(1, "线下转账"),

    QR_CODE(2, "扫码支付");

    private final Integer code;
    private final String name;

    PaymentChannelType(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * JPA Converter（框架自动应用）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<PaymentChannelType> {
        public JpaConverter() {
            super(PaymentChannelType.class);
        }
    }
}
