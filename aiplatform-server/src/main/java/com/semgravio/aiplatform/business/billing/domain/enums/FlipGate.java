package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 付费状态翻转门（ADR-0047 两门任一，#515/#514 载体票）：门①＝岔路口选付费项
 * （继续讨论·由我的账户/本项目付费——登录＋源池余额为正）；门②＝试用中意见
 * 拦截卡确认扣减（后台已充项目余额的项目免登录直接确认）。两门同一翻转语义
 * （锚在钱的承诺事实上、不可逆），门别只作留痕（成本观测/审计归因）。
 */
public enum FlipGate implements BaseEnum<FlipGate> {

    FORK(1, "岔路口"),
    INTERCEPT(2, "拦截卡");

    private final Integer code;
    private final String name;

    FlipGate(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<FlipGate> {
        public JpaConverter() {
            super(FlipGate.class);
        }
    }
}
