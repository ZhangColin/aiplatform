package com.aieducenter.aiplatform.business.order.domain.aggregate;

import java.time.LocalDateTime;

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

import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;

/**
 * 订单聚合根（{@code ord_orders}）：确认下单后的交易载体。下单即拷贝 PRD 全文
 * 快照入单（自含，不依赖工作区存亡——源码不快照，交付经 source-package 实时取）；
 * 金额单位分（Long）、v1 恒 CNY，随报价落值。projectId 跨上下文软引用
 * （prj_projects，无 FK）；ownerAccountId 冗余下单账号（按用户查）。
 *
 * <p>状态机（五态单向）与报价/改价/支付/归档动作归片4 用例落位；本聚合先立
 * 下单事实与终态判定（{@link OrderStatus#isTerminal}——「同项目至多一个未终结
 * 订单」的应用预检与库侧部分唯一索引共用该口径）。同项目并发下单的最终防线
 * = 库侧唯一索引，聚合不做跨行查重。</p>
 */
@Entity
@Table(name = "ord_orders")
@Aggregate
@Getter
public class Order extends Auditable implements AggregateRoot<Order, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 下单账号（idn_accounts 软引用；无会话上下文可空）。 */
    @Column(name = "owner_account_id", updatable = false)
    private Long ownerAccountId;

    @Column(name = "status", nullable = false)
    private OrderStatus status;

    /** 下单时 PRD 全文快照（交易标的；只插不改）。 */
    @Column(name = "prd_snapshot", nullable = false, updatable = false)
    private String prdSnapshot;

    /** 当前总价（分；待报价 NULL，报价落值、改价取最新价目行）。 */
    @Column(name = "amount")
    private Long amount;

    /** ISO 4217 币种（v1 恒 CNY；随报价落）。 */
    @Column(name = "currency", length = 10)
    private String currency;

    /** 首次报价时点（待报价态 NULL；改价不刷新——留痕在价目行）。 */
    @Column(name = "quoted_at")
    private LocalDateTime quotedAt;

    /** 支付成功时点。 */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** 归档时点（支付成功一事务内联动项目归档）。 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /** 取消时点（未支付态取消即回迭代）。 */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** 支付流水号（mock 平台内生成；真实接入为渠道单号）。 */
    @Column(name = "payment_no", length = 100)
    private String paymentNo;

    protected Order() {
    }

    private Order(Long projectId, Long ownerAccountId, String prdSnapshot) {
        if (projectId == null || prdSnapshot == null || prdSnapshot.isBlank()) {
            throw new DomainException(OrderMessage.ORDER_FIELDS_INCOMPLETE);
        }
        this.projectId = projectId;
        this.ownerAccountId = ownerAccountId;
        this.status = OrderStatus.PENDING_QUOTE;
        this.prdSnapshot = prdSnapshot;
    }

    /**
     * 下单（确认动作的落库事实）：待报价起步，快照在此冻结——此后 PRD 修订
     * 不影响本单（取消再下 = 新单新快照）。
     */
    public static Order place(Long projectId, Long ownerAccountId, String prdSnapshot) {
        return new Order(projectId, ownerAccountId, prdSnapshot);
    }

    /** 是否终态（已归档/已取消）——未终结订单唯一性的判定口径。 */
    public boolean isTerminal() {
        return status.isTerminal();
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
