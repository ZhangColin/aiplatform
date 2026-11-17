package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 余额提醒留存状态（#537 计费③）：待决（卡可回、可读）→ 已放行（充值回正
 * 自动恢复受理落定）或被取代（更新的拦提醒顶替／恢复受理失败退场）。行保留
 * 不删（留存即审计面：什么输入因欠费被拦、何时放行/取代）。
 */
public enum BalanceHoldStatus implements BaseEnum<BalanceHoldStatus> {

    PENDING(1, "待决"),
    RESUMED(2, "已放行"),
    SUPERSEDED(3, "被取代");

    private final Integer code;
    private final String name;

    BalanceHoldStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<BalanceHoldStatus> {
        public JpaConverter() {
            super(BalanceHoldStatus.class);
        }
    }
}
