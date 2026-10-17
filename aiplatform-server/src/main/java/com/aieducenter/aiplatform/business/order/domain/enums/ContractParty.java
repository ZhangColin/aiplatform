package com.aieducenter.aiplatform.business.order.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 合同签署位（#336 双方签署位两枚）：甲方＝客户方、乙方＝服务方——「签署完成」
 * ＝双方均已落签。仅作签署标记动作的寻址参数与响应呈现，不落独立列（合同两
 * 枚布尔位即存储正身）。
 */
public enum ContractParty implements BaseEnum<ContractParty> {

    PARTY_A(1, "甲方"),

    PARTY_B(2, "乙方");

    private final Integer code;
    private final String name;

    ContractParty(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<ContractParty> {
        public JpaConverter() {
            super(ContractParty.class);
        }
    }
}
