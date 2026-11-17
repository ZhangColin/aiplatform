package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 后台账户运营动作种类（{@code bil_operator_actions}，#535 计费⑤）：赠送
 * （默认入账户池、可指定项目池——动作即选定项目扣减源）、手动充值录入（对公
 * 转账——仿凭证核验留痕）、余额调整（平台故障退款返还与运营纠错同载体）、
 * 扣减源调整（用户选错池的后台救济通道）。全部 append-only 操作者留痕（变更
 * 前后快照同事务）。
 */
public enum OperatorActionType implements BaseEnum<OperatorActionType> {

    GRANT(1, "赠送"),
    MANUAL_TOPUP(2, "手动充值"),
    BALANCE_ADJUST(3, "余额调整"),
    SOURCE_RESELECT(4, "扣减源调整");

    private final Integer code;
    private final String name;

    OperatorActionType(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<OperatorActionType> {
        public JpaConverter() {
            super(OperatorActionType.class);
        }
    }
}
