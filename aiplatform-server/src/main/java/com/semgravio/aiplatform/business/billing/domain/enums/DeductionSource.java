package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 扣减源（CONTEXT「扣减源」）：项目的余额取用池标识，<b>一次性选定</b>——后台向
 * 项目余额入账即已选项目池（动作即选择，前台零呈现），从未入账的项目由用户在
 * 岔路口卡或确认扣减卡上选一次；选定后不可自助更改（后台运营可调整，留痕）。
 * 码位与 {@link PoolKind} 对齐：取款池 = 对应种类的池。
 */
public enum DeductionSource implements BaseEnum<DeductionSource> {

    ACCOUNT(1, "账户余额"),
    PROJECT(2, "项目余额");

    private final Integer code;
    private final String name;

    DeductionSource(Integer code, String name) {
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

    /** 码 → 枚举（后台扣减源调整端点的映射面；未知码如实拒绝——照订单域 ofCode 先例）。 */
    public static DeductionSource ofCode(Integer code) {
        for (DeductionSource source : values()) {
            if (source.code.equals(code)) {
                return source;
            }
        }
        throw new IllegalArgumentException("未知的扣减源码: " + code);
    }

    /**
     * JPA Converter（框架自动应用，实体字段无需 @Convert）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<DeductionSource> {
        public JpaConverter() {
            super(DeductionSource.class);
        }
    }
}
