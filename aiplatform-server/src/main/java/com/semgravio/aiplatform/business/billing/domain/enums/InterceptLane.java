package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 被拦车道（#533 计费②试用拦截闸）：入口三分类后、受理前被拦截的输入车道——
 * 意见与兜底两道拦（宁严勿漏，ADR-0047）；咨询零产物短路天然放行、下单意图
 * 维持订单域旧轨，永不入拦。确认扣减后按留存车道恢复受理（意见→意见轮、
 * 兜底→定型轻引导）。
 */
public enum InterceptLane implements BaseEnum<InterceptLane> {

    OPINION(1, "意见"),
    FALLBACK(2, "兜底");

    private final Integer code;
    private final String name;

    InterceptLane(Integer code, String name) {
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
     * JPA Converter（框架自动应用，实体字段无需 @Convert）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<InterceptLane> {
        public JpaConverter() {
            super(InterceptLane.class);
        }
    }
}
