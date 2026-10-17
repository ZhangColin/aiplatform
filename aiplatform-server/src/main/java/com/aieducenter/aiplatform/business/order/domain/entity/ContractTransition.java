package com.aieducenter.aiplatform.business.order.domain.entity;

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

import com.aieducenter.aiplatform.business.order.domain.enums.ContractStatus;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractTransitionAction;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;

/**
 * 合同动作留痕行（{@code ord_contract_transitions}，Contract 聚合内实体，#336）：
 * 每次状态迁移（推进/撤回/作废/失败或过期）与非迁移写动作（签署标记/草稿重录）
 * 各一行，<b>append-only 只插不改写</b>——全列 {@code updatable = false}，聚合外
 * 无任何修改入口；操作人/时间/依据（必填自由文本）随行落。非迁移类动作在行上
 * {@code fromStatus = toStatus}（在场快照）。
 *
 * <p>操作者两列口径同 {@link OrderPriceEntry}（#155 落空口径：缺头/无上下文为
 * {@code null}，读面呈现为空）。</p>
 */
@Entity
@Table(name = "ord_contract_transitions")
@Getter
public class ContractTransition implements DomainEntity<ContractTransition, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属合同（由聚合 OneToMany JoinColumn 管理，不重复映射本列）。 */

    /** 动作（迁移类/非迁移类，见 {@link ContractTransitionAction}）。 */
    @Column(name = "action", nullable = false, updatable = false)
    private ContractTransitionAction action;

    /** 动作前状态。 */
    @Column(name = "from_status", nullable = false, updatable = false)
    private ContractStatus fromStatus;

    /** 动作后状态（非迁移类＝from 同值）。 */
    @Column(name = "to_status", nullable = false, updatable = false)
    private ContractStatus toStatus;

    /** 依据（必填自由文本——人工推进留痕的凭据）。 */
    @Column(name = "basis", nullable = false, updatable = false, length = 1000)
    private String basis;

    /** 操作者标识（admin 侧 TSID 十进制字符串；无头落 NULL，#155 落空口径）。 */
    @Column(name = "operator_id", updatable = false, length = 64)
    private String operatorId;

    /** 操作者昵称（直读展示；无头落 NULL）。 */
    @Column(name = "operator_name", updatable = false, length = 200)
    private String operatorName;

    /** 动作时间（业务时间）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ContractTransition() {
    }

    /**
     * 记一留痕行：动作两侧状态、依据、操作者、业务时间随行落。只经
     * {@code Contract} 动作方法调用（聚合内追加，外部无直接写入口）。
     */
    public static ContractTransition record(ContractTransitionAction action, ContractStatus fromStatus,
            ContractStatus toStatus, String basis, Operator operator) {
        ContractTransition transition = new ContractTransition();
        transition.action = action;
        transition.fromStatus = fromStatus;
        transition.toStatus = toStatus;
        transition.basis = basis;
        if (operator != null) {
            transition.operatorId = operator.id();
            transition.operatorName = operator.name();
        }
        transition.createdAt = LocalDateTime.now();
        return transition;
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
        return sameIdentityAs((ContractTransition) o);
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
