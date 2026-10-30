package com.semgravio.aiplatform.business.order.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 付款核验动作（#337 留痕行的动作语义）：到账核验（实到金额/实到时间/关联
 * 凭证随行落——全额即转已到账，不足回待支付留痕）与打回（依据必填留痕、回
 * 待支付）。与合同迁移动作（{@code ContractTransitionAction}）同款形制：
 * append-only 留痕行的动作分类位。
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）。</p>
 */
public enum PaymentVerificationAction implements BaseEnum<PaymentVerificationAction> {

    ARRIVAL(1, "到账核验"),

    REJECT(2, "打回");

    private final Integer code;
    private final String name;

    PaymentVerificationAction(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<PaymentVerificationAction> {
        public JpaConverter() {
            super(PaymentVerificationAction.class);
        }
    }
}
