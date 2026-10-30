package com.semgravio.aiplatform.business.project.domain.entity;

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

import com.semgravio.aiplatform.business.project.domain.enums.ProductionHandoffAction;
import com.semgravio.aiplatform.business.project.domain.enums.ProductionHandoffStatus;
import com.semgravio.aiplatform.business.project.domain.model.Operator;

/**
 * 生产承接动作留痕行（{@code prj_production_handoff_transitions}，#338）：
 * 每次状态迁移（推进）与非迁移写动作（信息更新；创建行随开工判定联动落）各
 * 一行，<b>append-only 只插不改写</b>——全列 {@code updatable = false}，聚合外
 * 无任何修改入口；操作人/时间/依据（必填自由文本）随行落。非迁移类动作在行上
 * {@code fromStatus = toStatus}（在场快照）。
 *
 * <p>操作者两列口径同 {@code ContractTransition}（#336）：创建行＝开工判定
 * 系统操作者（"system"），人工动作缺头为 {@code null} 由聚合守卫前置拒绝
 * （PRJ_059——审计本体动作不落无名留痕）。</p>
 */
@Entity
@Table(name = "prj_production_handoff_transitions")
@Getter
public class ProductionHandoffTransition implements DomainEntity<ProductionHandoffTransition, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属承接记录（由聚合 OneToMany JoinColumn 管理，不重复映射本列）。 */

    /** 动作（迁移类/非迁移类，见 {@link ProductionHandoffAction}）。 */
    @Column(name = "action", nullable = false, updatable = false)
    private ProductionHandoffAction action;

    /** 动作前状态。 */
    @Column(name = "from_status", nullable = false, updatable = false)
    private ProductionHandoffStatus fromStatus;

    /** 动作后状态（非迁移类＝from 同值）。 */
    @Column(name = "to_status", nullable = false, updatable = false)
    private ProductionHandoffStatus toStatus;

    /** 依据（必填自由文本——人工推进留痕的凭据）。 */
    @Column(name = "basis", nullable = false, updatable = false, length = 1000)
    private String basis;

    /** 操作者标识（admin 侧 TSID 十进制字符串；创建行＝开工判定系统操作者）。 */
    @Column(name = "operator_id", updatable = false, length = 64)
    private String operatorId;

    /** 操作者昵称（直读展示）。 */
    @Column(name = "operator_name", updatable = false, length = 200)
    private String operatorName;

    /** 动作时间（业务时间）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ProductionHandoffTransition() {
    }

    /**
     * 记一留痕行：动作两侧状态、依据、操作者、业务时间随行落。只经
     * {@code ProductionHandoff} 动作方法调用（聚合内追加，外部无直接写入口）。
     */
    public static ProductionHandoffTransition record(ProductionHandoffAction action,
            ProductionHandoffStatus fromStatus, ProductionHandoffStatus toStatus,
            String basis, Operator operator) {
        ProductionHandoffTransition transition = new ProductionHandoffTransition();
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
        return sameIdentityAs((ProductionHandoffTransition) o);
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
