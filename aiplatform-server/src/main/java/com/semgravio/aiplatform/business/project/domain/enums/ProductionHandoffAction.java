package com.semgravio.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 生产承接动作（#338 留痕的动作维度）：状态迁移类（推进）与非迁移类（创建/
 * 信息更新）统称——每动作一行 append-only 留痕（操作人/时间/依据必填）。创建
 * 行由开工判定联动落（系统操作者）；非迁移类在留痕行上 from_status = to_status
 * （在场快照），迁移类如实记录两侧。
 */
public enum ProductionHandoffAction implements BaseEnum<ProductionHandoffAction> {

    CREATE(1, "创建"),

    ADVANCE(2, "推进"),

    UPDATE(3, "信息更新");

    private final Integer code;
    private final String name;

    ProductionHandoffAction(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<ProductionHandoffAction> {
        public JpaConverter() {
            super(ProductionHandoffAction.class);
        }
    }
}
