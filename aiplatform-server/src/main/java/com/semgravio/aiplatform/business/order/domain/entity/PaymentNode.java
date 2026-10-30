package com.semgravio.aiplatform.business.order.domain.entity;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import cn.hutool.core.collection.CollUtil;

import lombok.Getter;

import com.cartisan.core.domain.DomainEntity;
import com.cartisan.core.exception.DomainException;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.business.order.domain.enums.PaymentChannelType;
import com.semgravio.aiplatform.business.order.domain.enums.PaymentNodeStatus;
import com.semgravio.aiplatform.business.order.domain.enums.PaymentVerificationAction;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;
import com.semgravio.aiplatform.business.order.domain.model.PaymentNodeSpec;

/**
 * 付款节点（{@code ord_payment_nodes}，PaymentPlan 聚合内实体，#337）：序号/
 * 名称/金额（绝对额录入、占比展示换算）/五态/附言码（{@code <合同号>-K<序>}
 * 式，录入时自合同号定格派生）/开工门标志（后台录入时定）＋渠道接口位（渠道
 * 类型枚举＋渠道流水号可空字段）＋累计已核到账金额（{@code settledAmount}——
 * 不足留痕后仍累积，前台以 {@code amount - settledAmount} 推导「还需支付金额
 * 与对应节点」）。
 *
 * <p>五态 v1 只走凭证核验路径（{@link PaymentNodeStatus} 边正本）：待支付 →
 * （用户上传凭证）线下转账待核验 →（后台核验）已到账/打回（回待支付＋依据
 * 留痕）；「支付处理中/支付失败或关闭」枚举立着、路径 v1 不通。节点转已到账
 * 要求全额——累计核到账 ≥ 节点金额才转，不足回待支付留痕（留痕行即审计正身，
 * {@link PaymentVerification}）。</p>
 */
@Entity
@Table(name = "ord_payment_nodes")
@Getter
public class PaymentNode implements DomainEntity<PaymentNode, Long> {

    /** 节点名称上限（与 {@code ord_payment_nodes.name} 列宽对齐）。 */
    public static final int NAME_MAX_LENGTH = 200;

    /** 渠道流水号上限（与 {@code ord_payment_nodes.channel_ref_no} 列宽对齐）。 */
    public static final int CHANNEL_REF_NO_MAX_LENGTH = 100;

    /** 依据/备注上限（与 {@code ord_payment_verifications.basis} 列宽对齐）。 */
    public static final int BASIS_MAX_LENGTH = 1000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属付款计划（由聚合 OneToMany JoinColumn 管理，不重复映射本列）。 */

    /** 序号（1 起——录入序即展示序，附言码随序定格）。 */
    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    /** 名称（如「首期开工款」）。 */
    @Column(name = "name", nullable = false, updatable = false, length = NAME_MAX_LENGTH)
    private String name;

    /** 金额（分，绝对额录入——占比是展示换算不落库）。 */
    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    /** 五态（见 {@link PaymentNodeStatus}）。 */
    @Column(name = "status", nullable = false)
    private PaymentNodeStatus status;

    /** 附言码（{@code <合同号>-K<序>}，录入时定格、用户转账必填回执关联位）。 */
    @Column(name = "remark_code", nullable = false, updatable = false, length = 60)
    private String remarkCode;

    /** 开工门标志（后台录入时定；#338 开工判定按本标志取节点集合）。 */
    @Column(name = "kickoff_gate", nullable = false, updatable = false)
    private boolean kickoffGate;

    /** 渠道类型（渠道接口位最小形态：v1 恒线下转账、扫码占位）。 */
    @Column(name = "channel_type", nullable = false)
    private PaymentChannelType channelType;

    /** 渠道流水号（可空；核验录入时落值——渠道真接后由回执对账填充）。 */
    @Column(name = "channel_ref_no", length = CHANNEL_REF_NO_MAX_LENGTH)
    private String channelRefNo;

    /** 累计已核到账金额（分，初始 0；不足留痕后仍累积）。 */
    @Column(name = "settled_amount", nullable = false)
    private Long settledAmount;

    /** 转已到账时点（实到时间，核验录入——未到账为 null）。 */
    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /**
     * 核验留痕（append-only）：每次到账核验/打回各一行，只经聚合动作方法追加。
     * {@code @OrderBy} 定死加载序＝追加序（TSID 时间有序）。
     */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "node_id", nullable = false)
    @OrderBy("id ASC")
    private final List<PaymentVerification> verifications = CollUtil.newArrayList();

    protected PaymentNode() {
    }

    /**
     * 录入一节点（{@code PaymentPlan#enterNodes} 内调用）：起步待支付、渠道
     * 恒线下转账（v1 唯一走通路径）、附言码自合同号定格派生（{@code <合同号>
     * -K<序>} 式，#337 格式实施定）。
     */
    public static PaymentNode enter(int seq, PaymentNodeSpec spec, String contractNo) {
        PaymentNode node = new PaymentNode();
        node.seq = seq;
        node.name = spec.name().trim();
        node.amount = spec.amount();
        node.status = PaymentNodeStatus.PENDING;
        node.remarkCode = contractNo + "-K" + seq;
        node.kickoffGate = spec.kickoffGate();
        node.channelType = PaymentChannelType.OFFLINE_TRANSFER;
        node.settledAmount = 0L;
        return node;
    }

    /**
     * 用户上传凭证（#337 凭证路径第一跳）：待支付/待核验可达（已到账不再收；
     * 「处理中/失败」v1 不可达），翻线下转账待核验。上传仅代表已提交——凭证
     * 行在聚合外落（追加保留不覆盖），核验结论在留痕行。
     */
    public void submitVoucher() {
        if (!status.canSubmitVoucher()) {
            throw new DomainException(OrderMessage.PAYMENT_ACTION_NOT_ALLOWED);
        }
        this.status = PaymentNodeStatus.AWAITING_VERIFICATION;
    }

    /**
     * 到账核验（#337 凭证路径第二跳·后台人工录入）：实到金额/实到时间/关联
     * 凭证随行留痕，渠道流水号可空落值。全额判定＝累计核到账 ≥ 节点金额：
     * 足额转已到账（落 receivedAt＝实到时间）；不足回待支付留痕（实到金额已
     * 入累计，前台推导「还需支付金额与对应节点」＝{@code amount - settledAmount}）。
     *
     * @param actualAmount 实到金额（分，正数；非法抛 ORD_041）
     * @param arrivedAt    实到时间（业务时间，必填；缺失抛 ORD_041）
     * @param voucherId    关联凭证（可空——凭证行身份软引用）
     * @param channelRefNo 渠道流水号（可空；超长抛 ORD_046）
     * @param basis        备注（可空；超长抛 ORD_046）
     * @param operator     操作者（必填，ORD_043——核验是审计本体动作）
     */
    public void verifyArrival(Long actualAmount, LocalDateTime arrivedAt, Long voucherId,
            String channelRefNo, String basis, Operator operator) {
        requireOperator(operator);
        if (actualAmount == null || actualAmount <= 0 || arrivedAt == null) {
            throw new DomainException(OrderMessage.PAYMENT_VERIFY_ENTRY_INVALID);
        }
        requireLength(channelRefNo, CHANNEL_REF_NO_MAX_LENGTH);
        requireLength(basis, BASIS_MAX_LENGTH);
        if (!status.canVerify()) {
            throw new DomainException(OrderMessage.PAYMENT_ACTION_NOT_ALLOWED);
        }
        if (channelRefNo != null && !channelRefNo.isBlank()) {
            this.channelRefNo = channelRefNo.trim();
        }
        this.settledAmount = this.settledAmount + actualAmount;
        PaymentNodeStatus toStatus = settledAmount >= amount
                ? PaymentNodeStatus.RECEIVED
                : PaymentNodeStatus.PENDING;
        if (toStatus == PaymentNodeStatus.RECEIVED) {
            this.receivedAt = arrivedAt;
        }
        verifications.add(PaymentVerification.record(PaymentVerificationAction.ARRIVAL,
                status, toStatus, actualAmount, arrivedAt, voucherId, basis, operator));
        this.status = toStatus;
    }

    /**
     * 打回（后台人工动作）：待核验态可达（凭证主张被否——回单不清、对不上账），
     * 回待支付＋依据必填留痕（ORD_042）。打回不动累计金额（核到账只经核验录入
     * 累积）。
     *
     * @param voucherId 关联凭证（可空）
     * @param basis     打回依据（必填自由文本；空白抛 ORD_042，超长抛 ORD_046）
     * @param operator  操作者（必填，ORD_043）
     */
    public void reject(Long voucherId, String basis, Operator operator) {
        requireOperator(operator);
        if (basis == null || basis.isBlank()) {
            throw new DomainException(OrderMessage.PAYMENT_REJECT_BASIS_REQUIRED);
        }
        requireLength(basis, BASIS_MAX_LENGTH);
        if (!status.canVerify()) {
            throw new DomainException(OrderMessage.PAYMENT_ACTION_NOT_ALLOWED);
        }
        verifications.add(PaymentVerification.record(PaymentVerificationAction.REJECT,
                status, PaymentNodeStatus.PENDING, null, null, voucherId, basis.trim(), operator));
        this.status = PaymentNodeStatus.PENDING;
    }

    /** 是否支付活动已开始（录入替换窗口判定：非待支付或有留痕行——打回回待支付但留痕在）。 */
    public boolean paymentStarted() {
        return status != PaymentNodeStatus.PENDING || !verifications.isEmpty();
    }

    /** 还需支付金额（分）：未到账节点的差额推导面（前台「还需支付金额与对应节点」）。 */
    public Long remainingAmount() {
        return status.isReceived() ? 0L : Math.max(0L, amount - settledAmount);
    }

    /** 留痕行（新 → 旧）：按追加序倒排（TSID 时间有序，同拍按 id 破平）。 */
    public List<PaymentVerification> verificationHistoryNewestFirst() {
        return verifications.stream()
                .sorted(Comparator.comparing(PaymentVerification::getId).reversed())
                .toList();
    }

    private static void requireOperator(Operator operator) {
        if (operator == null || operator.id() == null) {
            throw new DomainException(OrderMessage.PAYMENT_OPERATOR_REQUIRED);
        }
    }

    private static void requireLength(String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new DomainException(OrderMessage.PAYMENT_FIELD_TOO_LONG);
        }
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
        return sameIdentityAs((PaymentNode) o);
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
