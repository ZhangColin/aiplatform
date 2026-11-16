package com.semgravio.aiplatform.business.billing.domain.aggregate;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

/**
 * 项目计费态聚合根（{@code bil_project_billing}）：<b>两轨判定单读点</b>
 * （ADR-0047）——扣减源（一次性选定）＋付费状态（翻转不可逆）落一行，行缺席
 * 即试用开局（新项目一律试用，惰性建行：首次源选定或后台项目池入账才出现）。
 * 拦截闸、派发门、协议分轨、体验提示四消费面同读本聚合（经应用层读点）。
 *
 * <p><b>扣减源</b>（{@link #selectSource}）：用户一次性选定（岔路口卡/确认扣减卡
 * 载体在后续票，本聚合只收选定事实）；重复选定拒绝（BILL_008——不可自助更改）。
 * 后台运营可调整（{@link #reselectSourceByBackoffice}，操作者留痕）。选「账户
 * 余额」时 {@code sourceAccountId} 定格（选定即冻结——结算取款池不随项目归属
 * 后续漂移），须有账户（BILL_009——访客先登录绑定）。</p>
 *
 * <p><b>付费状态</b>（{@link #flipToPaid}）：翻转锚在钱的承诺事实上、<b>不可逆</b>
 * （已翻转再翻＝BILL_012）；前置＝源已选定（BILL_010）；「源池余额为正」是钱的
 * 承诺事实面，读池在应用层（聚合读不到池），翻转前校验。计费边界＝
 * {@code flippedAt} 时间点不看活动类型：翻转前一切智能体调用＝平台获客成本，
 * 翻转后走扣减（成本观测切片按本时间戳分桶，#536）。</p>
 */
@Entity
@Table(name = "bil_project_billing")
@Aggregate
@Getter
public class ProjectBilling extends Auditable implements AggregateRoot<ProjectBilling, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 项目（prj_projects 软引用，无 FK）。 */
    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 扣减源；NULL = 未选（试用开局缺省）。 */
    @Column(name = "deduction_source")
    private DeductionSource deductionSource;

    /**
     * 选「账户余额」时的账户定格（idn_accounts 软引用；项目源恒 NULL）。随源
     * 选定/调整可变（账户源→项目源清空、后台改选账户源落新值）——列须可更新。
     */
    @Column(name = "source_account_id")
    private Long sourceAccountId;

    /** 源选定/最近调整时点。 */
    @Column(name = "source_selected_at")
    private Instant sourceSelectedAt;

    /** 后台调整操作者（用户自选/无头落 NULL）。 */
    @Column(name = "source_operator_id", length = 64)
    private String sourceOperatorId;

    /** 后台调整操作者名（直读展示）。 */
    @Column(name = "source_operator_name", length = 200)
    private String sourceOperatorName;

    /** 付费状态翻转时点（计费边界锚）；NULL = 试用。单向置位不可逆。 */
    @Column(name = "flipped_at")
    private Instant flippedAt;

    /** 翻转门留痕（门①岔路口/门②拦截卡）；NULL = 未翻转。 */
    @Column(name = "flip_gate")
    private FlipGate flipGate;

    protected ProjectBilling() {
    }

    private ProjectBilling(Long projectId) {
        if (projectId == null) {
            throw new DomainException(BillingMessage.PROJECT_BILLING_FIELDS_INCOMPLETE);
        }
        this.projectId = projectId;
    }

    /** 试用开局建行（源未设、未翻转——首次源选定/项目池入账时惰性落）。 */
    public static ProjectBilling trialOf(Long projectId) {
        return new ProjectBilling(projectId);
    }

    /** 付费状态（翻转过即付费；试用＝源未设或未翻转的缺省态）。 */
    public boolean paid() {
        return flippedAt != null;
    }

    /** 扣减源是否已选定。 */
    public boolean sourceSelected() {
        return deductionSource != null;
    }

    /**
     * 用户一次性选定扣减源（载体卡在后续票，本聚合只收选定事实）：重复选定拒绝
     * （BILL_008）；账户源须有账户定格（BILL_009）。项目源的 {@code accountId}
     * 入参忽略（池挂项目不挂账户）。
     */
    public void selectSource(DeductionSource source, Long accountId) {
        if (this.deductionSource != null) {
            throw new DomainException(BillingMessage.SOURCE_ALREADY_SELECTED);
        }
        requireSourceSelectable(source, accountId);
        applySource(source, accountId, null);
    }

    /**
     * 后台运营调整扣减源（#151 留痕口径）：选定语义与用户面一致，差异仅在可
     * 覆盖既有选定＋操作者落痕（{@code null} = 无头落空）。用户选错池的救济通道。
     */
    public void reselectSourceByBackoffice(DeductionSource source, Long accountId, Operator operator) {
        requireSourceSelectable(source, accountId);
        applySource(source, accountId, operator);
    }

    /**
     * 翻转为付费（门①/门②同一语义，门别只留痕）：已翻转拒绝（BILL_012，不可逆）
     * ；源未选定拒绝（BILL_010）。落 {@code flippedAt}＝计费边界（此后一切智能体
     * 调用走扣减）；「源池余额为正」前置在应用层校验（聚合读不到池）。
     */
    public void flipToPaid(FlipGate gate) {
        if (flippedAt != null) {
            throw new DomainException(BillingMessage.FLIP_ALREADY_PAID);
        }
        if (deductionSource == null) {
            throw new DomainException(BillingMessage.FLIP_SOURCE_REQUIRED);
        }
        if (gate == null) {
            throw new DomainException(BillingMessage.PROJECT_BILLING_FIELDS_INCOMPLETE);
        }
        this.flippedAt = Instant.now();
        this.flipGate = gate;
    }

    private void requireSourceSelectable(DeductionSource source, Long accountId) {
        if (source == null) {
            throw new DomainException(BillingMessage.PROJECT_BILLING_FIELDS_INCOMPLETE);
        }
        if (source == DeductionSource.ACCOUNT && accountId == null) {
            throw new DomainException(BillingMessage.SOURCE_ACCOUNT_REQUIRED);
        }
    }

    private void applySource(DeductionSource source, Long accountId, Operator operator) {
        this.deductionSource = source;
        this.sourceAccountId = source == DeductionSource.ACCOUNT ? accountId : null;
        this.sourceSelectedAt = Instant.now();
        this.sourceOperatorId = operator == null ? null : operator.id();
        this.sourceOperatorName = operator == null ? null : operator.name();
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
