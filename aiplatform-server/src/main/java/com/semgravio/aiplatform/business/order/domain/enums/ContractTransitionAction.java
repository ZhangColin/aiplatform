package com.semgravio.aiplatform.business.order.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 合同后台动作（#336 迁移留痕的动作维度）：状态迁移类（推进/撤回/作废/失败或
 * 过期）与非迁移类（签署标记/草稿重录）统称——每动作一行 append-only 留痕
 * （操作人/时间/依据必填）。非迁移类在留痕行上 from_status = to_status（在场
 * 快照），迁移类如实记录两侧。
 */
public enum ContractTransitionAction implements BaseEnum<ContractTransitionAction> {

    ADVANCE(1, "推进"),

    WITHDRAW(2, "撤回"),

    VOID(3, "作废"),

    SIGN_MARK(4, "签署标记"),

    RE_RECORD(5, "草稿重录"),

    FAIL(6, "失败或过期");

    private final Integer code;
    private final String name;

    ContractTransitionAction(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<ContractTransitionAction> {
        public JpaConverter() {
            super(ContractTransitionAction.class);
        }
    }
}
