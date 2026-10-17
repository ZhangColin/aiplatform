package com.aieducenter.aiplatform.business.order.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 付款节点状态（#337 五态照交接包原样立，spec #320 实施序④）：v1 只走凭证
 * 核验路径——待支付 →（用户上传凭证）线下转账待核验 →（后台核验）已到账，
 * 或打回（回待支付＋依据留痕）；「支付处理中/支付失败或关闭」枚举立着、
 * 路径 v1 不通（渠道真接后启用——异步中间态在渠道适配器内吸收后迁入）。
 *
 * <p>已到账是节点终态（全额判定：累计核到账 ≥ 节点金额才转，不足回待支付
 * 留痕）；待支付/待核验是用户可再动作态（补传凭证/补差额）。</p>
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）。</p>
 */
public enum PaymentNodeStatus implements BaseEnum<PaymentNodeStatus> {

    PENDING(1, "待支付"),

    PROCESSING(2, "支付处理中"),

    FAILED_OR_CLOSED(3, "支付失败或关闭"),

    AWAITING_VERIFICATION(4, "线下转账待核验"),

    RECEIVED(5, "已到账");

    private final Integer code;
    private final String name;

    PaymentNodeStatus(Integer code, String name) {
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

    /** 是否已到账（节点终态——到账后用户面不再出凭证入口）。 */
    public boolean isReceived() {
        return this == RECEIVED;
    }

    /**
     * 凭证上传可达态（待支付/线下转账待核验）：已到账不再收凭证；「处理中/
     * 失败」v1 不可达（枚举立着、无迁移边）。
     */
    public boolean canSubmitVoucher() {
        return this == PENDING || this == AWAITING_VERIFICATION;
    }

    /** 后台核验可达态（仅线下转账待核验——先有凭证主张才有核验对象）。 */
    public boolean canVerify() {
        return this == AWAITING_VERIFICATION;
    }

    /**
     * JPA Converter（框架自动应用）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<PaymentNodeStatus> {
        public JpaConverter() {
            super(PaymentNodeStatus.class);
        }
    }
}
