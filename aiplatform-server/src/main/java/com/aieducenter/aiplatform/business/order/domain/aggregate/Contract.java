package com.aieducenter.aiplatform.business.order.domain.aggregate;

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

import com.aieducenter.aiplatform.business.order.domain.entity.ContractTransition;
import com.aieducenter.aiplatform.business.order.domain.entity.OrderPriceEntry;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractParty;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractStatus;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractTransitionAction;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;

/**
 * 合同聚合根（{@code ord_contracts}，#336 六态照交接包原样立）：「确认范围并
 * 准备合同」（用户确认动作）或后台重建时刻捕获<b>范围快照冻结集</b>——报价版本
 * 引用（冻结时点价目行）＋范围文本不可变副本＋双版本引用（PRD 确认版全文冗余
 * 拷贝＋版本号、Demo 确认版本号——#335 下单冻结集的合同侧冗余，合同自含）。
 * 编号（SG-＋TSID base36）创建即定格、全局唯一；合同版本自 1 起、草稿同号重录
 * +1。
 *
 * <p>状态机六态单向推进（{@link ContractStatus#nextOnAdvance} 边正本）：
 * 准备中 → 待核对与签署 → 签署进行中 → 签署完成（＝双方签署位均已落签）；
 * 签署失败或过期自待核对/签署中旁落、已撤回或作废自任何非终态旁落。全部后台
 * 人工推进，每次动作 append-only 留痕（{@link ContractTransition}：操作人/时间/
 * 依据必填）。终态永不物理删除（无删除口即口径）。草稿态（准备中/待核对与签署）
 * 是同号重录＋重新冻结的唯一窗口；签署进行中起变动走撤回/作废重建（新号新合同，
 * 同订单活跃唯一交库侧部分唯一索引兜底，聚合不做跨行查重）。</p>
 *
 * <p>预览字段集（双方主体/项目范围/付款计划——交付清单/验收标准/工期口径随
 * 冻结集、费用随冻结金额、合同版本即 {@link #version}）后台结构化录入、前端
 * 渲染预览——<b>不存文档本体</b>（电子签文件＝渠道真接后扩展位）。</p>
 */
@Entity
@Table(name = "ord_contracts")
@Aggregate
@Getter
public class Contract extends Auditable implements AggregateRoot<Contract, Long> {

    /** 编号前缀（SG- 风格，交接包原型口径；正身＝TSID base36）。 */
    public static final String CONTRACT_NO_PREFIX = "SG-";

    /** 操作依据上限（与 {@code ord_contract_transitions.basis} 列宽对齐）。 */
    public static final int BASIS_MAX_LENGTH = 1000;

    /** 主体两段上限（与 {@code ord_contracts.party_a/party_b} 列宽对齐）。 */
    public static final int PARTY_MAX_LENGTH = 200;

    /** 预览文本段上限（项目范围/付款计划与范围快照三段列宽对齐）。 */
    public static final int CONTENT_MAX_LENGTH = 2000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属订单（ord_orders 同 BC 引用；引用集在下单时点已冻结）。 */
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    /** 合同编号（SG-＋TSID base36；创建即定格、永不改）。 */
    @Column(name = "contract_no", nullable = false, updatable = false)
    private String contractNo;

    /** 六态（见 {@link ContractStatus}）。 */
    @Column(name = "status", nullable = false)
    private ContractStatus status;

    /** 合同版本（草稿同号重录 +1；重建＝新号新合同从 1 起）。 */
    @Column(name = "version", nullable = false)
    private int version;

    /** 报价版本引用（冻结时点价目行 id——append-only 行身份即版本；重录重定格）。 */
    @Column(name = "quote_entry_id", nullable = false)
    private Long quoteEntryId;

    /** 冻结金额（分，随冻结价目行定格；重录重定格）。 */
    @Column(name = "amount", nullable = false)
    private Long amount;

    /** 币种（v1 恒 CNY，随冻结价目行定格）。 */
    @Column(name = "currency", nullable = false)
    private String currency;

    /** 范围快照·交付清单（不可变副本，创建/重录时定格——两次冻结之间不改写）。 */
    @Column(name = "scope_deliverables", nullable = false, length = CONTENT_MAX_LENGTH)
    private String scopeDeliverables;

    /** 范围快照·验收方式（不可变副本，口径同上）。 */
    @Column(name = "scope_acceptance", nullable = false, length = CONTENT_MAX_LENGTH)
    private String scopeAcceptance;

    /** 范围快照·工期口径（不可变副本，口径同上）。 */
    @Column(name = "scope_timeline", nullable = false, length = CONTENT_MAX_LENGTH)
    private String scopeTimeline;

    /** PRD 确认版本号（#335 下单冻结值冗余）。 */
    @Column(name = "prd_version_no", nullable = false, updatable = false)
    private int prdVersionNo;

    /** PRD 确认版全文冗余拷贝（合同自含，不依赖版本表与订单存亡）。 */
    @Column(name = "prd_snapshot", nullable = false, updatable = false)
    private String prdSnapshot;

    /** Demo 确认版本号引用（#15 冻结集）。 */
    @Column(name = "demo_version_no", nullable = false, updatable = false)
    private int demoVersionNo;

    /** 甲方主体（客户方；后台录入，草稿态可改）。 */
    @Column(name = "party_a", length = PARTY_MAX_LENGTH)
    private String partyA;

    /** 乙方主体（服务方；后台录入，草稿态可改）。 */
    @Column(name = "party_b", length = PARTY_MAX_LENGTH)
    private String partyB;

    /** 项目范围（后台录入，草稿态可改）。 */
    @Column(name = "project_scope", length = CONTENT_MAX_LENGTH)
    private String projectScope;

    /** 付款计划（文本面；节点对象归付款计划域后续票）。 */
    @Column(name = "payment_plan", length = CONTENT_MAX_LENGTH)
    private String paymentPlan;

    /** 甲方签署位（未签/已签）。 */
    @Column(name = "party_a_signed", nullable = false)
    private boolean partyASigned;

    /** 乙方签署位（未签/已签）。 */
    @Column(name = "party_b_signed", nullable = false)
    private boolean partyBSigned;

    /** 甲方落签时点。 */
    @Column(name = "party_a_signed_at")
    private LocalDateTime partyASignedAt;

    /** 乙方落签时点。 */
    @Column(name = "party_b_signed_at")
    private LocalDateTime partyBSignedAt;

    /**
     * 动作留痕（append-only）：每次状态迁移与后台写动作各一行，只经聚合动作
     * 方法追加；无孤儿清除（留痕随合同终身保留）。{@code @OrderBy} 定死加载序
     * ＝追加序（TSID 时间有序）。
     */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "contract_id", nullable = false)
    @OrderBy("id ASC")
    private final List<ContractTransition> transitions = CollUtil.newArrayList();

    protected Contract() {
    }

    /**
     * 创建合同（#336 冻结集捕获）：「确认范围并准备合同」（用户动作）与后台
     * 重建共用同一冻结正本——冻结时点最新价目行（报价版本引用＋金额）＋订单
     * 范围文本三段的不可变副本＋双版本引用与 PRD 全文冗余拷贝（订单侧已
     * updatable=false，合同侧再冗余一份＝合同自含）。起步准备中、版本 1、编号
     * 创建即定格。
     *
     * @param order 所属订单（已过确认门：已报价＋范围文本齐备——前置守卫在应用
     *              层，聚合只收已过门的订单；范围文本三段自订单工作副本现值
     *              读取，不做第二数据源）
     * @param quote 冻结时点价目行（报价版本引用正身）
     */
    public static Contract create(Order order, OrderPriceEntry quote) {
        if (order == null || quote == null) {
            throw new DomainException(OrderMessage.ORDER_FIELDS_INCOMPLETE);
        }
        // 范围文本与双版本引用是合同必冻结维：范围未录入＝确认门未过（ORD_026）；
        // #335 确认制之前下的遗留单如实无版本引用——不虚构，该类老单不进合同流
        if (!order.scopeEntered()) {
            throw new DomainException(OrderMessage.CONTRACT_SCOPE_REQUIRED);
        }
        if (order.getPrdVersionNo() == null || order.getDemoVersionNo() == null) {
            throw new DomainException(OrderMessage.ORDER_FIELDS_INCOMPLETE);
        }
        Contract contract = new Contract();
        contract.id = TsidGenerator.newInstance().generate();
        contract.contractNo = CONTRACT_NO_PREFIX + Long.toString(contract.id, 36);
        contract.orderId = order.getId();
        contract.status = ContractStatus.PREPARING;
        contract.version = 1;
        contract.captureFreeze(order, quote);
        return contract;
    }

    /** 定格冻结集（创建/重录共用：报价版本引用＋范围文本不可变副本＋双版本冗余——全部自订单现值单源取）。 */
    private void captureFreeze(Order order, OrderPriceEntry quote) {
        this.quoteEntryId = quote.getId();
        this.amount = quote.getAmount();
        this.currency = quote.getCurrency();
        this.scopeDeliverables = order.getScopeDeliverables();
        this.scopeAcceptance = order.getScopeAcceptance();
        this.scopeTimeline = order.getScopeTimeline();
        this.prdVersionNo = order.getPrdVersionNo();
        this.prdSnapshot = order.getPrdSnapshot();
        this.demoVersionNo = order.getDemoVersionNo();
    }

    /**
     * 是否终态（签署完成/签署失败或过期/已撤回或作废）——「同订单至多一份活跃
     * 合同」的判定口径（库侧部分唯一索引同谓词）。
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * 推进（后台人工动作，线性单向边正本＝{@link ContractStatus#nextOnAdvance}）：
     * 准备中 → 待核对与签署 → 签署进行中 → 签署完成。到签署完成的边带双方均签
     * 守卫（{@link #bothSigned}，ORD_027）；到头（终态）无推进边（ORD_021）。
     * 每次推进 append-only 留痕（依据必填、操作人随行）。
     *
     * @param basis   依据（必填自由文本；空白抛 ORD_022，超长抛 ORD_023）
     * @param operator 操作者（缺头落空口径，{@code null} 落 NULL 两列）
     */
    public void advance(String basis, Operator operator) {
        requireBasis(basis);
        requireOperator(operator);
        ContractStatus next = status.nextOnAdvance();
        if (next == null) {
            throw new DomainException(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED);
        }
        if (next == ContractStatus.SIGNED && !bothSigned()) {
            throw new DomainException(OrderMessage.CONTRACT_BOTH_PARTIES_NOT_SIGNED);
        }
        appendTransition(ContractTransitionAction.ADVANCE, next, basis, operator);
    }

    /**
     * 失败或过期（后台人工旁落）：自待核对与签署/签署进行中可达（用户放弃核对或
     * 超期未签的如实终局；准备中还没到用户面，失败不成立）——签署进行中已部分
     * 落签同样可达（作废重来走重建）。其余态 ORD_021。留痕同 {@link #advance}。
     */
    public void markFailed(String basis, Operator operator) {
        requireBasis(basis);
        requireOperator(operator);
        if (status != ContractStatus.AWAITING_SIGNATURE && status != ContractStatus.SIGNING) {
            throw new DomainException(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED);
        }
        appendTransition(ContractTransitionAction.FAIL, ContractStatus.FAILED_OR_EXPIRED, basis, operator);
    }

    /**
     * 撤回（后台人工旁落）：自任何非终态可达——撤回后重建＝新号新合同（签署
     * 进行中起内容变动的唯一正路；草稿态直接重录，撤回留给出错的整案回炉）。
     * 终态再动 ORD_021。留痕同 {@link #advance}。
     */
    public void withdraw(String basis, Operator operator) {
        requireBasis(basis);
        requireOperator(operator);
        if (status.isTerminal()) {
            throw new DomainException(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED);
        }
        appendTransition(ContractTransitionAction.WITHDRAW, ContractStatus.WITHDRAWN_OR_VOIDED, basis, operator);
    }

    /**
     * 作废（后台人工旁落）：守卫与去向同 {@link #withdraw}（同一终态，动作语义
     * 分立——撤回＝我方主动收回、作废＝判定无效；区分只在留痕行的 action）。
     */
    public void voidContract(String basis, Operator operator) {
        requireBasis(basis);
        requireOperator(operator);
        if (status.isTerminal()) {
            throw new DomainException(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED);
        }
        appendTransition(ContractTransitionAction.VOID, ContractStatus.WITHDRAWN_OR_VOIDED, basis, operator);
    }

    /**
     * 签署标记（后台人工动作＝电子签渠道真接前的承接口径）：仅在签署进行中态
     * 落签位（ORD_021），已落签的签署位幂等拒（ORD_028）。落签只翻签署位与时点，
     * 不自动迁移——「签署完成」由 {@link #advance} 显式推进（双方均签守卫），
     * 人工判定与留痕保持一次一动作。
     */
    public void sign(ContractParty party, String basis, Operator operator) {
        requireBasis(basis);
        requireOperator(operator);
        if (status != ContractStatus.SIGNING) {
            throw new DomainException(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED);
        }
        if (party == ContractParty.PARTY_A) {
            if (partyASigned) {
                throw new DomainException(OrderMessage.CONTRACT_PARTY_ALREADY_SIGNED);
            }
            this.partyASigned = true;
            this.partyASignedAt = LocalDateTime.now();
        } else {
            if (partyBSigned) {
                throw new DomainException(OrderMessage.CONTRACT_PARTY_ALREADY_SIGNED);
            }
            this.partyBSigned = true;
            this.partyBSignedAt = LocalDateTime.now();
        }
        // 非迁移动作：from = to（在场快照）
        transitions.add(ContractTransition.record(ContractTransitionAction.SIGN_MARK,
                status, status, basis, operator));
    }

    /** 依据必填校验（全部后台动作共用：空白 ORD_022、超长 ORD_023）。 */
    private void requireBasis(String basis) {
        if (basis == null || basis.isBlank()) {
            throw new DomainException(OrderMessage.CONTRACT_BASIS_REQUIRED);
        }
        if (basis.length() > BASIS_MAX_LENGTH) {
            throw new DomainException(OrderMessage.CONTRACT_BASIS_TOO_LONG);
        }
    }

    /**
     * 操作者必填校验（全部后台动作共用，ORD_035）：合同动作是人工推进、留痕行
     * 以操作人为追责正身——无名动作不如实留痕即拒绝（与订单写口 #155 落空口径
     * 有意分立：那是状态变更的顺手落痕，这是审计本体）。
     */
    private static void requireOperator(Operator operator) {
        if (operator == null || operator.id() == null) {
            throw new DomainException(OrderMessage.CONTRACT_OPERATOR_REQUIRED);
        }
    }

    /**
     * 草稿重录（#336 变动三路径之草稿路）：同号（编号不变）＋内容整组录入＋
     * 冻结集取订单<b>现值</b>重新定格（改价/改范围后的「需更新」处置正路，重冻
     * 即清零）＋合同版本 +1。仅草稿态可达（ORD_031）——签署进行中起走撤回/
     * 作废重建（新号新合同）。状态不前移（重录不是迁移）；留痕行 from = to。
     *
     * @param partyA       甲方主体（可空＝未录入，空白归一 null）
     * @param partyB       乙方主体（同上）
     * @param projectScope 项目范围（同上）
     * @param paymentPlan  付款计划文本面（同上；节点对象归付款计划域后续票）
     * @param order        所属订单现值（重冻取其最新价目行＋范围文本工作副本）
     * @param operator     操作者（缺头落空口径）
     * @param basis        依据（必填）
     */
    public void record(String partyA, String partyB, String projectScope, String paymentPlan,
            Order order, Operator operator, String basis) {
        requireBasis(basis);
        requireOperator(operator);
        if (!status.isDraft()) {
            throw new DomainException(OrderMessage.CONTRACT_RE_RECORD_NOT_ALLOWED);
        }
        if (isOverlong(partyA, PARTY_MAX_LENGTH) || isOverlong(partyB, PARTY_MAX_LENGTH)
                || isOverlong(projectScope, CONTENT_MAX_LENGTH) || isOverlong(paymentPlan, CONTENT_MAX_LENGTH)) {
            throw new DomainException(OrderMessage.CONTRACT_CONTENT_TOO_LONG);
        }
        OrderPriceEntry quote = order == null ? null : order.latestPriceEntry();
        if (quote == null) {
            throw new DomainException(OrderMessage.CONTRACT_QUOTE_REQUIRED);
        }
        if (!order.scopeEntered()) {
            throw new DomainException(OrderMessage.CONTRACT_SCOPE_REQUIRED);
        }
        this.partyA = blankToNull(partyA);
        this.partyB = blankToNull(partyB);
        this.projectScope = blankToNull(projectScope);
        this.paymentPlan = blankToNull(paymentPlan);
        captureFreeze(order, quote);
        this.version++;
        // 非迁移动作：from = to（在场快照）
        transitions.add(ContractTransition.record(ContractTransitionAction.RE_RECORD,
                status, status, basis, operator));
    }

    /**
     * 「需更新」派生标志（#336，不落库——对照订单现值现算，重冻即清零、无双写
     * 漂移）：草稿态且冻结集与订单现值不一致（改价或改范围后未重录）＝true；
     * 签署进行中/终态恒 false（变动处置不走标志——签署中走撤回重建、已签走
     * 变更沟通不碰原合同）。对照口径＝冻结内容（金额＋范围文本三段）与现值
     * 的相等性：同额改价不虚报（合同面无实变）。
     */
    public boolean needsUpdate(Order order) {
        if (!status.isDraft() || order == null) {
            return false;
        }
        return !Objects.equals(amount, order.getAmount())
                || !Objects.equals(scopeDeliverables, order.getScopeDeliverables())
                || !Objects.equals(scopeAcceptance, order.getScopeAcceptance())
                || !Objects.equals(scopeTimeline, order.getScopeTimeline());
    }

    private static boolean isOverlong(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }


    /** 迁移落痕＋状态前移（聚合内单点：留痕行与状态在同一动作内一致）。 */
    private void appendTransition(ContractTransitionAction action, ContractStatus toStatus,
            String basis, Operator operator) {
        transitions.add(ContractTransition.record(action, status, toStatus, basis, operator));
        this.status = toStatus;
    }

    /**
     * 双方是否均已落签（「签署完成」推进守卫的单点）。
     */
    public boolean bothSigned() {
        return partyASigned && partyBSigned;
    }

    /**
     * 留痕行（新 → 旧）：按追加序倒排（TSID 时间有序，同拍按 id 破平）。
     */
    public List<ContractTransition> transitionHistoryNewestFirst() {
        return transitions.stream()
                .sorted(Comparator.comparing(ContractTransition::getId).reversed())
                .toList();
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
