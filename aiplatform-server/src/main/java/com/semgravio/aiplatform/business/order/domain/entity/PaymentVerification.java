package com.semgravio.aiplatform.business.order.domain.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;

import com.cartisan.core.domain.DomainEntity;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.order.domain.enums.PaymentNodeStatus;
import com.semgravio.aiplatform.business.order.domain.enums.PaymentVerificationAction;
import com.semgravio.aiplatform.business.order.domain.model.Operator;

/**
 * 付款核验留痕行（{@code ord_payment_verifications}，PaymentNode 聚合内实体，
 * #337）：每次后台核验动作（到账核验/打回）各一行，<b>append-only 只插不改写</b>
 * ——全列 {@code updatable = false}，聚合外无任何修改入口。「不足留痕」「打回
 * 依据留痕」的审计正身：实到金额/实到时间/关联凭证随行落（到账核验），依据
 * 必填（打回）。
 *
 * <p>操作者两列口径同 {@code ContractTransition}（#336 审计口径：核验动作以
 * 操作者为追责正身，必携校验在聚合动作方法）。</p>
 */
@Entity
@Table(name = "ord_payment_verifications")
@Getter
public class PaymentVerification implements DomainEntity<PaymentVerification, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属付款节点（由聚合 OneToMany JoinColumn 管理，不重复映射本列）。 */

    /** 动作（到账核验/打回，见 {@link PaymentVerificationAction}）。 */
    @Column(name = "action", nullable = false, updatable = false)
    private PaymentVerificationAction action;

    /** 动作前节点状态。 */
    @Column(name = "from_status", nullable = false, updatable = false)
    private PaymentNodeStatus fromStatus;

    /** 动作后节点状态。 */
    @Column(name = "to_status", nullable = false, updatable = false)
    private PaymentNodeStatus toStatus;

    /** 实到金额（分；到账核验必录——不足留痕与「还需支付」推导的事实源）。 */
    @Column(name = "actual_amount", updatable = false)
    private Long actualAmount;

    /** 实到时间（到账核验必录，业务时间——非录入时间）。 */
    @Column(name = "arrived_at", updatable = false)
    private LocalDateTime arrivedAt;

    /** 关联凭证（ord_payment_vouchers 软引用，可空——跨聚合引用行身份不设 FK）。 */
    @Column(name = "voucher_id", updatable = false)
    private Long voucherId;

    /** 依据/备注（打回必填留痕；到账核验可空备注）。 */
    @Column(name = "basis", updatable = false, length = 1000)
    private String basis;

    /** 操作者标识（admin 侧 TSID 十进制字符串）。 */
    @Column(name = "operator_id", updatable = false, length = 64)
    private String operatorId;

    /** 操作者昵称（直读展示）。 */
    @Column(name = "operator_name", updatable = false, length = 200)
    private String operatorName;

    /** 动作时间（业务时间）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected PaymentVerification() {
    }

    /**
     * 记一留痕行：动作两侧状态、核验事实（金额/时间/凭证）、依据、操作者随行
     * 落。只经 {@code PaymentNode} 动作方法调用（聚合内追加，外部无直接写入口）。
     */
    public static PaymentVerification record(PaymentVerificationAction action,
            PaymentNodeStatus fromStatus, PaymentNodeStatus toStatus, Long actualAmount,
            LocalDateTime arrivedAt, Long voucherId, String basis, Operator operator) {
        PaymentVerification verification = new PaymentVerification();
        verification.action = action;
        verification.fromStatus = fromStatus;
        verification.toStatus = toStatus;
        verification.actualAmount = actualAmount;
        verification.arrivedAt = arrivedAt;
        verification.voucherId = voucherId;
        verification.basis = basis;
        if (operator != null) {
            verification.operatorId = operator.id();
            verification.operatorName = operator.name();
        }
        verification.createdAt = LocalDateTime.now();
        return verification;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return sameIdentityAs((PaymentVerification) o);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
