package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 余额提醒留存车道（#537 计费③，ADR-0047 段间硬门）：付费项目段边界余额检查
 * 不过时被拦输入的受理面——意见回意见轮、咨询回答询轮、作用域改稿回设计会话
 * （件序＋发散度随留存承载）；兜底/下单意图零调用天然免费，永不入拦。
 */
public enum BalanceHoldLane implements BaseEnum<BalanceHoldLane> {

    OPINION(1, "意见"),
    INQUIRY(2, "咨询"),
    DESIGN_REVISION(3, "作用域改稿");

    private final Integer code;
    private final String name;

    BalanceHoldLane(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<BalanceHoldLane> {
        public JpaConverter() {
            super(BalanceHoldLane.class);
        }
    }
}
