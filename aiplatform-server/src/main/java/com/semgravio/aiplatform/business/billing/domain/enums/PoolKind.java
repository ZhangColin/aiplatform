package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 余额池种类（ADR-0047 双池）：账户余额池（账户名下、跨项目）与项目余额池
 * （项目专属）。与 {@link DeductionSource} 码位对齐（源选定即指向对应种类的池）
 * ——两枚举概念不同身（池的归类 vs 项目的一次性选择），不合并。
 */
public enum PoolKind implements BaseEnum<PoolKind> {

    ACCOUNT(1, "账户池"),
    PROJECT(2, "项目池");

    private final Integer code;
    private final String name;

    PoolKind(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<PoolKind> {
        public JpaConverter() {
            super(PoolKind.class);
        }
    }
}
