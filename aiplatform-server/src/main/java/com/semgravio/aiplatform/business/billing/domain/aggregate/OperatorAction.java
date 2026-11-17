package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

/**
 * 后台账户运营动作留痕行（{@code bil_operator_actions}，#535 计费⑤，ADR-0047
 * 「后台账户运营：机机签名＋操作者留痕」）：赠送/手动充值/余额调整/扣减源调整
 * 四类动作各落一行——<b>append-only 只插不改</b>（全业务列
 * {@code updatable = false}，与流水行同律），操作者两列＋变更前后快照
 * （{@code balanceBefore}/{@code balanceAfter}＋{@code detail} JSONB——凭证
 * 核验字段/扣减源旧值新值）与池变动/计费态变动<b>同事务</b>成对落。三锚列
 * （account/project/pool）软引用不外键（账本可分离）；源调整不动池，池锚与
 * 前后余额列落 NULL。
 */
@Entity
@Table(name = "bil_operator_actions")
@Aggregate
@Getter
public class OperatorAction extends Auditable implements AggregateRoot<OperatorAction, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 动作种类（赠送/手动充值/余额调整/扣减源调整）。 */
    @Column(name = "action_type", nullable = false, updatable = false)
    private OperatorActionType actionType;

    /** 账户锚（idn_accounts 软引用；账户池动作非空）。 */
    @Column(name = "account_id", updatable = false)
    private Long accountId;

    /** 项目锚（prj_projects 软引用；项目池动作/源调整非空）。 */
    @Column(name = "project_id", updatable = false)
    private Long projectId;

    /** 池锚（bil_pools 软引用；源调整为 NULL——不动池）。 */
    @Column(name = "pool_id", updatable = false)
    private Long poolId;

    /** 金额（分；赠送/充值恒正、调整带符号、源调整 NULL）。 */
    @Column(name = "amount", updatable = false)
    private Long amount;

    /** 变更前池余额快照（分；源调整为 NULL）。 */
    @Column(name = "balance_before", updatable = false)
    private Long balanceBefore;

    /** 变更后池余额快照（分；源调整为 NULL）。 */
    @Column(name = "balance_after", updatable = false)
    private Long balanceAfter;

    /** 动作明细（手动充值＝凭证核验三字段；源调整＝旧/新扣减源快照；null/空归一 NULL）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> detail;

    /** 后台操作者（admin 侧标识；缺头落空 NULL）。 */
    @Column(name = "operator_id", updatable = false, length = 64)
    private String operatorId;

    /** 后台操作者名（直读展示；缺头落空 NULL）。 */
    @Column(name = "operator_name", updatable = false, length = 200)
    private String operatorName;

    protected OperatorAction() {
    }

    /**
     * 赠送/手动充值入账留痕：金额恒正（BILL_002）、前后余额必填（入账必先有池）。
     * {@code detail} 由调用方装凭证核验字段（手动充值）或备注线索。
     */
    public static OperatorAction topup(OperatorActionType type, Long accountId, Long projectId,
            Long poolId, long amount, long balanceBefore, long balanceAfter,
            Map<String, Object> detail, Operator operator) {
        if (type != OperatorActionType.GRANT && type != OperatorActionType.MANUAL_TOPUP) {
            throw new DomainException(BillingMessage.OPERATOR_ACTION_INVALID);
        }
        if (amount <= 0) {
            throw new DomainException(BillingMessage.TOPUP_AMOUNT_INVALID);
        }
        return build(type, accountId, projectId, poolId, amount, balanceBefore, balanceAfter,
                detail, operator);
    }

    /**
     * 余额调整留痕：非零带符号（BILL_003）、原因必填留痕（BILL_005——调整行格与
     * 流水行同）；前后余额必填。
     */
    public static OperatorAction balanceAdjust(Long accountId, Long projectId, Long poolId,
            long delta, long balanceBefore, long balanceAfter, String reason, Operator operator) {
        if (delta == 0) {
            throw new DomainException(BillingMessage.ADJUSTMENT_AMOUNT_INVALID);
        }
        if (isBlank(reason)) {
            throw new DomainException(BillingMessage.ENTRY_FIELDS_INCOMPLETE);
        }
        return build(OperatorActionType.BALANCE_ADJUST, accountId, projectId, poolId, delta,
                balanceBefore, balanceAfter, Map.of("reason", reason.trim()), operator);
    }

    /**
     * 扣减源调整留痕：不动池（池锚/金额/前后余额全 NULL），明细落旧/新源快照——
     * 后台救济通道（用户选错池）的可审计面。
     */
    public static OperatorAction sourceReselect(Long projectId,
            DeductionSource oldSource, Long oldAccountId,
            DeductionSource newSource, Long newAccountId, Operator operator) {
        if (projectId == null || newSource == null) {
            throw new DomainException(BillingMessage.PROJECT_BILLING_FIELDS_INCOMPLETE);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("oldSource", oldSource == null ? null : oldSource.getCode());
        detail.put("oldSourceName", oldSource == null ? null : oldSource.getName());
        detail.put("oldAccountId", oldAccountId);
        detail.put("newSource", newSource.getCode());
        detail.put("newSourceName", newSource.getName());
        detail.put("newAccountId", newAccountId);
        return build(OperatorActionType.SOURCE_RESELECT, null, projectId, null, null,
                null, null, detail, operator);
    }

    private static OperatorAction build(OperatorActionType type, Long accountId, Long projectId,
            Long poolId, Long amount, Long balanceBefore, Long balanceAfter,
            Map<String, Object> detail, Operator operator) {
        OperatorAction action = new OperatorAction();
        action.actionType = type;
        action.accountId = accountId;
        action.projectId = projectId;
        action.poolId = poolId;
        action.amount = amount;
        action.balanceBefore = balanceBefore;
        action.balanceAfter = balanceAfter;
        action.detail = detail == null || detail.isEmpty() ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        action.operatorId = operator == null ? null : operator.id();
        action.operatorName = operator == null ? null : operator.name();
        return action;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 聚合 ID（TSID 时间有序——追加序即时间序）。
     */
    @Override
    public Long getId() {
        return id;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
