package com.semgravio.aiplatform.business.project.domain.aggregate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import cn.hutool.core.collection.CollUtil;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.project.domain.entity.ProductionHandoffTransition;
import com.semgravio.aiplatform.business.project.domain.enums.ProductionHandoffAction;
import com.semgravio.aiplatform.business.project.domain.enums.ProductionHandoffStatus;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.Operator;

/**
 * 生产承接聚合根（{@code prj_production_handoffs}，#338，spec #320 实施序④）：
 * 开工判定＝双门禁（合同签署完成 && 全部开工门节点已到账）满足时刻由订单侧
 * 判定器同事务创建——<b>挂项目、只建一次</b>（project_id 唯一索引兜底＋服务端
 * 事务内判定；无终态概念，全生命周期一行）。两态（待安排生产 → 生产中）后台
 * 人工推进，每次动作 append-only 留痕（{@link ProductionHandoffTransition}：
 * 操作人/时间/依据必填）；创建行随开工判定联动落（系统操作者，非人工）。
 *
 * <p>承接字段是面向用户的<b>人工措辞</b>面（进展更新不上工程术语、不展示内部
 * Agent 日志）：交付联系人/下一步说明/进展更新/预计开始时间（可空——仅录入
 * 真实值才显示，空＝待安排不虚构日期）。信息更新不迁移状态（留痕行 from =
 * to），整组替换语义（未录入段归一 null）。</p>
 */
@Entity
@Table(name = "prj_production_handoffs")
@Aggregate
@Getter
public class ProductionHandoff extends Auditable implements AggregateRoot<ProductionHandoff, Long> {

    /** 交付联系人上限（与 {@code prj_production_handoffs.contact} 列宽对齐）。 */
    public static final int CONTACT_MAX_LENGTH = 200;

    /** 下一步说明上限（与 {@code next_step} 列宽对齐）。 */
    public static final int NEXT_STEP_MAX_LENGTH = 1000;

    /** 进展更新上限（与 {@code progress_note} 列宽对齐）。 */
    public static final int PROGRESS_NOTE_MAX_LENGTH = 2000;

    /** 操作依据上限（与 {@code prj_production_handoff_transitions.basis} 列宽对齐）。 */
    public static final int BASIS_MAX_LENGTH = 1000;

    /**
     * 开工判定联动的系统操作者（创建行留痕——判定是系统动作非人工；订单取消
     * 联动作废合同同款「系统操作者留痕、不放松聚合守卫」口径）。
     */
    static final Operator KICKOFF_OPERATOR = new Operator("system", "开工判定");

    /** 创建行依据（开工判定的结构性凭据——双门禁公式即创建理由的正身）。 */
    private static final String CREATE_BASIS = "开工条件满足（合同签署完成且开工门节点全部到账），创建生产承接记录";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属项目（prj_projects 同 BC 引用，库侧唯一约束＝「只建一次」最终防线）。 */
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 触发开工判定的订单（ord_orders 跨 BC 软引用，正身溯源）。 */
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    /** 两态（见 {@link ProductionHandoffStatus}）。 */
    @Column(name = "status", nullable = false)
    private ProductionHandoffStatus status;

    /** 交付联系人（可空＝未录入）。 */
    @Column(name = "contact", length = CONTACT_MAX_LENGTH)
    private String contact;

    /** 下一步说明（人工措辞；可空）。 */
    @Column(name = "next_step", length = NEXT_STEP_MAX_LENGTH)
    private String nextStep;

    /** 进展更新（人工措辞、不上工程术语；可空）。 */
    @Column(name = "progress_note", length = PROGRESS_NOTE_MAX_LENGTH)
    private String progressNote;

    /** 预计开始时间（仅录入真实值才显示；可空＝待安排）。 */
    @Column(name = "estimated_start_at")
    private LocalDateTime estimatedStartAt;

    /**
     * 动作留痕（append-only）：创建/推进/信息更新各一行，只经聚合动作方法追加；
     * 无孤儿清除（留痕随承接终身保留）。{@code @OrderBy} 定死加载序＝追加序
     * （TSID 时间有序）。
     */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "handoff_id", nullable = false)
    @OrderBy("id ASC")
    private final List<ProductionHandoffTransition> transitions = CollUtil.newArrayList();

    protected ProductionHandoff() {
    }

    /**
     * 开工判定建单（#338 唯一创建口——系统触发，后台无人工创建面）：起步待安排
     * 生产，创建留痕行随行落（系统操作者＋双门禁公式依据）。只建一次的判定归
     * 应用层事务内预检＋库侧唯一索引（聚合不做跨行查重，同订单/合同口径）。
     *
     * @param projectId 所属项目
     * @param orderId   触发开工判定的订单（正身溯源）
     */
    public static ProductionHandoff createForKickoff(Long projectId, Long orderId) {
        if (projectId == null || orderId == null) {
            throw new DomainException(ProjectMessage.PROJECT_FIELDS_INCOMPLETE);
        }
        ProductionHandoff handoff = new ProductionHandoff();
        handoff.projectId = projectId;
        handoff.orderId = orderId;
        handoff.status = ProductionHandoffStatus.SCHEDULED;
        handoff.transitions.add(ProductionHandoffTransition.record(ProductionHandoffAction.CREATE,
                ProductionHandoffStatus.SCHEDULED, ProductionHandoffStatus.SCHEDULED,
                CREATE_BASIS, KICKOFF_OPERATOR));
        return handoff;
    }

    /**
     * 推进（后台人工动作）：待安排生产 → 生产中（唯一迁移边；两态无终态，生产中
     * 是稳态——后续演化备案，不设回边）。依据必填、操作者随行落痕。
     *
     * @param basis    依据（必填自由文本；空白抛 PRJ_057，超长抛 PRJ_058）
     * @param operator 操作者（必填，PRJ_059——审计本体动作不落无名留痕）
     */
    public void advance(String basis, Operator operator) {
        requireBasis(basis);
        requireOperator(operator);
        if (status != ProductionHandoffStatus.SCHEDULED) {
            throw new DomainException(ProjectMessage.PRODUCTION_ACTION_NOT_ALLOWED);
        }
        appendTransition(ProductionHandoffAction.ADVANCE, ProductionHandoffStatus.IN_PRODUCTION,
                basis, operator);
    }

    /**
     * 信息更新（后台人工动作，非迁移）：交付联系人/下一步说明/进展更新/预计开始
     * 时间整组替换落值（未录入段归一 null——预计开始时间清空＝回待安排呈现，
     * 「仅真实值显示」由读面以 null 判定）。状态不动（留痕行 from = to）。
     *
     * @param contact           交付联系人（可空；超长抛 PRJ_058）
     * @param nextStep          下一步说明（可空；同上）
     * @param progressNote      进展更新（可空；同上）
     * @param estimatedStartAt  预计开始时间（可空＝待安排）
     * @param operator          操作者（必填，PRJ_059）
     * @param basis             依据（必填；空白抛 PRJ_057，超长抛 PRJ_058）
     */
    public void update(String contact, String nextStep, String progressNote,
            LocalDateTime estimatedStartAt, Operator operator, String basis) {
        requireBasis(basis);
        requireOperator(operator);
        if (isOverlong(contact, CONTACT_MAX_LENGTH) || isOverlong(nextStep, NEXT_STEP_MAX_LENGTH)
                || isOverlong(progressNote, PROGRESS_NOTE_MAX_LENGTH)) {
            throw new DomainException(ProjectMessage.PRODUCTION_FIELD_TOO_LONG);
        }
        this.contact = blankToNull(contact);
        this.nextStep = blankToNull(nextStep);
        this.progressNote = blankToNull(progressNote);
        this.estimatedStartAt = estimatedStartAt;
        transitions.add(ProductionHandoffTransition.record(ProductionHandoffAction.UPDATE,
                status, status, basis, operator));
    }

    /** 依据必填校验（人工动作共用：空白 PRJ_057、超长 PRJ_058）。 */
    private static void requireBasis(String basis) {
        if (basis == null || basis.isBlank()) {
            throw new DomainException(ProjectMessage.PRODUCTION_BASIS_REQUIRED);
        }
        if (basis.length() > BASIS_MAX_LENGTH) {
            throw new DomainException(ProjectMessage.PRODUCTION_FIELD_TOO_LONG);
        }
    }

    /**
     * 操作者必填校验（人工动作共用，PRJ_059）：承接动作是人工推进、留痕行以
     * 操作者为追责正身——无名动作不如实留痕即拒绝（口径同合同 ORD_035；创建行
     * 的系统操作者由工厂内建，不经本守卫）。
     */
    private static void requireOperator(Operator operator) {
        if (operator == null || operator.id() == null) {
            throw new DomainException(ProjectMessage.PRODUCTION_OPERATOR_REQUIRED);
        }
    }

    private static boolean isOverlong(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 迁移落痕＋状态前移（聚合内单点：留痕行与状态在同一动作内一致）。 */
    private void appendTransition(ProductionHandoffAction action, ProductionHandoffStatus toStatus,
            String basis, Operator operator) {
        transitions.add(ProductionHandoffTransition.record(action, status, toStatus, basis, operator));
        this.status = toStatus;
    }

    /** 留痕行（新 → 旧）：按追加序倒排（TSID 时间有序，同拍按 id 破平）。 */
    public List<ProductionHandoffTransition> transitionHistoryNewestFirst() {
        return transitions.stream()
                .sorted(Comparator.comparing(ProductionHandoffTransition::getId).reversed())
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return sameIdentityAs((ProductionHandoff) o);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * 聚合 ID。
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
