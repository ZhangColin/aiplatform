package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 拦截留存状态（#533 计费②）：待决（卡可回、可读）→ 已确认（确认扣减同事务
 * 落定）或被取代（更新的拦截顶替）。行保留不删（留存是审计面：被拦过什么、
 * 何时确认/取代）。
 */
public enum InterceptStatus implements BaseEnum<InterceptStatus> {

    PENDING(1, "待决"),
    CONFIRMED(2, "已确认"),
    SUPERSEDED(3, "被取代");

    private final Integer code;
    private final String name;

    InterceptStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<InterceptStatus> {
        public JpaConverter() {
            super(InterceptStatus.class);
        }
    }
}
