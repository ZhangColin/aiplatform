package com.aieducenter.aiplatform.business.order.domain.aggregate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

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

import com.aieducenter.aiplatform.business.order.domain.entity.OrderPriceEntry;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;

/**
 * 订单聚合根（{@code ord_orders}）：确认下单后的交易载体。下单即拷贝 PRD 全文
 * 快照入单（自含，不依赖工作区存亡——源码不快照，交付经 source-package 实时取）；
 * 金额单位分（Long）、v1 恒 CNY，随报价落值。projectId 跨上下文软引用
 * （prj_projects，无 FK）；ownerAccountId 冗余下单账号（按用户查）。
 *
 * <p>状态机（五态单向）：{@link #cancel}（未支付态取消即回迭代）与 {@link #quote}
 * （报价/改价，已报价态重复调用 = 改价）已落位，支付/归档随后续切片；终态判定
 * （{@link OrderStatus#isTerminal}——「同项目至多一个未终结订单」的应用预检与
 * 库侧部分唯一索引共用该口径）。同项目并发下单的最终防线 = 库侧唯一索引，
 * 聚合不做跨行查重。改价留痕在 {@link OrderPriceEntry}（append-only，
 * {@link #amount} 恒等于最新价目行金额）。</p>
 */
@Entity
@Table(name = "ord_orders")
@Aggregate
@Getter
public class Order extends Auditable implements AggregateRoot<Order, Long> {

    /** 币种（ISO 4217；v1 恒 CNY，不开放多币种）。 */
    public static final String CURRENCY_CNY = "CNY";

    /** 报价备注上限（与 {@code ord_price_entries.note} 列宽对齐）。 */
    public static final int QUOTE_NOTE_MAX_LENGTH = 1000;

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

    /**
     * 价目行（append-only 改价留痕）：首次报价与每次改价各一行，只经
     * {@link #quote} 追加；无孤儿清除（价目行随单终身保留，与「只追加不改写」
     * 一致，故不设 orphanRemoval）。{@code @OrderBy} 定死加载序 = 追加序
     * （TSID 时间有序），「最新一条」的判定不依赖库返回顺序。
     */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id ASC")
    private final List<OrderPriceEntry> priceEntries = CollUtil.newArrayList();

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
     * 取消（未支付态的显式动作，取消即解冻回迭代）：自任何未支付态（待报价/
     * 已报价）可达；已支付（支付成功即联动归档，#30）与已终结（已归档/已取消）
     * 拒绝。取消后同项目可再下新单（重新购买 = 新单新快照）。
     */
    public void cancel() {
        if (!status.isUnpaid()) {
            throw new DomainException(OrderMessage.ORDER_CANCEL_NOT_ALLOWED);
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    /**
     * 提交报价（后台动作）：待报价态首次调用 = 报价（待报价 → 已报价，落
     * {@code quotedAt}）；已报价态重复调用 = 改价（状态不变，{@code quotedAt}
     * 不刷新——改价时点留痕在价目行）。两者统一限未支付态：已支付/已终结拒绝
     * （ORD_007）。每次调用追加一条价目行（append-only），当前金额与币种取
     * 最新价目行——聚合内单事务保证「留痕行」与「订单现值」一致。
     *
     * @param amount 总价（分，正数；非法抛 ORD_008）
     * @param note   报价备注（后台文本；可空，超长抛 ORD_009）
     */
    public void quote(Long amount, String note) {
        if (amount == null || amount <= 0) {
            throw new DomainException(OrderMessage.ORDER_QUOTE_AMOUNT_INVALID);
        }
        if (note != null && note.length() > QUOTE_NOTE_MAX_LENGTH) {
            throw new DomainException(OrderMessage.ORDER_QUOTE_NOTE_TOO_LONG);
        }
        if (!status.isUnpaid()) {
            throw new DomainException(OrderMessage.ORDER_QUOTE_NOT_ALLOWED);
        }
        if (status == OrderStatus.PENDING_QUOTE) {
            this.status = OrderStatus.QUOTED;
            this.quotedAt = LocalDateTime.now();
        }
        this.amount = amount;
        this.currency = CURRENCY_CNY;
        this.priceEntries.add(OrderPriceEntry.record(amount, CURRENCY_CNY, note));
    }

    /**
     * 当前报价备注（用户面「后台备注」）＝最新价目行的 note；未报价为 null。
     */
    public String currentQuoteNote() {
        return priceEntries.isEmpty() ? null
                : priceEntries.get(priceEntries.size() - 1).getNote();
    }

    /**
     * 改价历史（新 → 旧）：价目行按追加序倒排（TSID 时间有序，毫秒同拍按 id
     * 破平）；未报价为空表。
     */
    public List<OrderPriceEntry> priceHistoryNewestFirst() {
        return priceEntries.stream()
                .sorted(Comparator.comparing(OrderPriceEntry::getId).reversed())
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
