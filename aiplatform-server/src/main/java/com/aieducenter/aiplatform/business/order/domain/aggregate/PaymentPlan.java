package com.aieducenter.aiplatform.business.order.domain.aggregate;

import java.util.List;
import java.util.Optional;

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

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.exception.DomainException;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.business.order.domain.entity.PaymentNode;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.PaymentNodeSpec;

/**
 * 付款计划聚合根（{@code ord_payment_plans}，#337）：挂订单下、随「确认范围并
 * 准备合同」同刻冻结创建（合同创建口共用同一冻结正本——plan 行与合同行同一
 * 事务落库）；撤回重建＋范围重冻（草稿重录）→ 新计划行、旧计划随旧合同留痕
 * （行 append-only 不改写，<b>活跃计划＝订单名下最新行</b>——无状态列、无双写
 * 漂移，同「合同活跃唯一」的推导面互补）。合同号定格在行上（附言码
 * {@code <合同号>-K<序>} 的正身源——计划自含，不依赖合同存亡）。
 *
 * <p>节点数不固定（原型 40/40/20 仅示例）：{@link #enterNodes} 整组录入（序号
 * 按列表序派生、开工门标志随行定）；支付活动未开始（全部待支付且无留痕行、
 * 无凭证——凭证腿在应用层校验）是重录的唯一窗口（ORD_044），已开始的计划节点
 * 定格、变动走合同侧撤回重建（新计划）。</p>
 */
@Entity
@Table(name = "ord_payment_plans")
@Aggregate
@Getter
public class PaymentPlan extends Auditable implements AggregateRoot<PaymentPlan, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 所属订单（ord_orders 同 BC 引用）。 */
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    /** 创建时点的合同（撤回重建/范围重冻→新计划行挂新冻结集）。 */
    @Column(name = "contract_id", nullable = false, updatable = false)
    private Long contractId;

    /** 合同号定格（附言码派生正身——计划自含，不依赖合同存亡）。 */
    @Column(name = "contract_no", nullable = false, updatable = false)
    private String contractNo;

    /**
     * 付款节点（录入序即展示序）：未录入（刚随合同创建）为空表——「付款计划
     * 待后台录入」是一等状态；支付未开始窗口内整组替换（orphanRemoval 清旧行）。
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "plan_id", nullable = false)
    @OrderBy("seq ASC, id ASC")
    private final List<PaymentNode> nodes = CollUtil.newArrayList();

    protected PaymentPlan() {
    }

    /**
     * 创建付款计划（#337 冻结同刻）：随合同创建（用户「确认范围并准备合同」与
     * 后台重建）与范围重冻（草稿重录）各起一行——新行即活跃计划（订单名下最新
     * 行），旧行随旧冻结集留痕。起步零节点（后台录入归 {@link #enterNodes}）。
     */
    public static PaymentPlan create(Order order, Contract contract) {
        if (order == null || contract == null || contract.getContractNo() == null) {
            throw new DomainException(OrderMessage.ORDER_FIELDS_INCOMPLETE);
        }
        PaymentPlan plan = new PaymentPlan();
        plan.orderId = order.getId();
        plan.contractId = contract.getId();
        plan.contractNo = contract.getContractNo();
        return plan;
    }

    /**
     * 节点录入（#337 后台动作，整组替换语义——「付款节点录入/开工门设定」）：
     * 序号按列表序派生（1 起，录入序即展示序）、附言码自合同号定格、渠道起步
     * 线下转账。重录窗口＝支付活动未开始：全部节点待支付且无核验留痕（聚合腿；
     * 凭证在场腿由应用层校验，两腿同译 ORD_044——打回回待支付但留痕/凭证在，
     * 不复开录入窗口）。
     *
     * @param specs 节点规格列表（≥1 条；名称必填 ≤200 字、金额为正〔分〕，
     *              非法抛 ORD_039）
     */
    public void enterNodes(List<PaymentNodeSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new DomainException(OrderMessage.PAYMENT_NODES_INVALID);
        }
        for (PaymentNodeSpec spec : specs) {
            if (spec == null || spec.name() == null || spec.name().isBlank()
                    || spec.name().trim().length() > PaymentNode.NAME_MAX_LENGTH
                    || spec.amount() == null || spec.amount() <= 0) {
                throw new DomainException(OrderMessage.PAYMENT_NODES_INVALID);
            }
        }
        if (nodes.stream().anyMatch(PaymentNode::paymentStarted)) {
            throw new DomainException(OrderMessage.PAYMENT_NODES_LOCKED);
        }
        nodes.clear();
        for (int i = 0; i < specs.size(); i++) {
            nodes.add(PaymentNode.enter(i + 1, specs.get(i), contractNo));
        }
    }

    /**
     * 节点寻址（聚合内）：节点 id 不在集合内（含旧计划节点）＝不存在（ORD_037）
     * ——活跃计划外的节点一律不可动作（旧计划随旧合同留痕、定格不可付）。
     */
    public PaymentNode node(Long nodeId) {
        return Optional.ofNullable(nodeOrNull(nodeId))
                .orElseThrow(() -> new DomainException(OrderMessage.PAYMENT_NODE_NOT_FOUND));
    }

    /** 节点寻址（可空形态——存在性预检面）。 */
    public PaymentNode nodeOrNull(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        return nodes.stream()
                .filter(node -> nodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    /** 计划总额（分）＝节点金额合计（占比展示换算的分母；未录入为 0）。 */
    public Long totalAmount() {
        return nodes.stream().mapToLong(PaymentNode::getAmount).sum();
    }

    /** 累计已核到账（分）＝节点 settled 合计（前台「已付」推导面）。 */
    public Long totalSettled() {
        return nodes.stream().mapToLong(PaymentNode::getSettledAmount).sum();
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
