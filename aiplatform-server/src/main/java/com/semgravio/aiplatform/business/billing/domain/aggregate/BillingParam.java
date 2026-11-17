package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

/**
 * 计费运营参数聚合根（{@code bil_params}）：param_key 自然主键（非流水——参数
 * 现值面，调整走行 UPDATE；不随调参漂移的快照责任在扣减行 snapshot 上）。
 * #531 落 {@link #KEY_CONVERSION_RATE}（全局折算率，初始 1——token 项用户扣减＝
 * 成本单价×折算率，固定单价项不乘）；后续票挂后台调整端点（运营面可调不发版）。
 */
@Entity
@Table(name = "bil_params")
@Aggregate
@Getter
public class BillingParam extends Auditable implements AggregateRoot<BillingParam, String> {

    /** 全局折算率参数键（ADR-0047：运营面可调、初始 1；只乘 token 项）。 */
    public static final String KEY_CONVERSION_RATE = "conversion_rate";

    @Id
    @Column(name = "param_key", nullable = false, updatable = false, length = 100)
    private String paramKey;

    /** 参数值（无量纲正数；精度随单价表 20,10）。 */
    @Column(name = "param_value", nullable = false, precision = 20, scale = 10)
    private BigDecimal paramValue;

    /** 最近调整操作者（种子行/无头落 NULL）。 */
    @Column(name = "operator_id", length = 64)
    private String operatorId;

    /** 最近调整操作者名（直读展示）。 */
    @Column(name = "operator_name", length = 200)
    private String operatorName;

    protected BillingParam() {
    }

    private BillingParam(String paramKey, BigDecimal paramValue) {
        if (paramKey == null || paramKey.isBlank() || paramValue == null
                || paramValue.signum() <= 0) {
            throw new DomainException(BillingMessage.PARAM_VALUE_INVALID);
        }
        this.paramKey = paramKey.trim();
        this.paramValue = paramValue;
    }

    /** 开参数行（迁移种子外的应用面建行——值须为正数）。 */
    public static BillingParam of(String paramKey, BigDecimal paramValue) {
        return new BillingParam(paramKey, paramValue);
    }

    /** 调整参数值（正数；操作者留痕，{@code null} = 无头落空）。 */
    public void updateTo(BigDecimal paramValue, Operator operator) {
        if (paramValue == null || paramValue.signum() <= 0) {
            throw new DomainException(BillingMessage.PARAM_VALUE_INVALID);
        }
        this.paramValue = paramValue;
        this.operatorId = operator == null ? null : operator.id();
        this.operatorName = operator == null ? null : operator.name();
    }

    /**
     * 聚合 ID = 参数键（自然键）。
     */
    @Override
    public String getId() {
        return paramKey;
    }
}
