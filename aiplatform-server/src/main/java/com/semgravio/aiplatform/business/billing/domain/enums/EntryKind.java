package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 流水行种类（bil_entries，append-only）：入账（充值/赠送——模拟收银台与后台
 * 入账面）、扣减（段收口结算与固定单价项直记——付费项目智能体调用的用户账）、
 * 调整（后台余额调整——运营纠错与平台故障返还同载体，ADR-0047 退款两线）。
 */
public enum EntryKind implements BaseEnum<EntryKind> {

    TOPUP(1, "入账"),
    DEDUCTION(2, "扣减"),
    ADJUSTMENT(3, "调整");

    private final Integer code;
    private final String name;

    EntryKind(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<EntryKind> {
        public JpaConverter() {
            super(EntryKind.class);
        }
    }
}
