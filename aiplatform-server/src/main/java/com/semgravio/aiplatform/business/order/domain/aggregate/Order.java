package com.semgravio.aiplatform.business.order.domain.aggregate;

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

import com.semgravio.aiplatform.business.order.domain.entity.OrderPriceEntry;
import com.semgravio.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.semgravio.aiplatform.business.order.domain.enums.OrderStatus;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;

/**
 * 订单聚合根（{@code ord_orders}）：确认下单后的交易载体。下单即冻结双版本
 * 引用＋PRD 确认版全文冗余拷贝＋交付物类型入单（#335 引用语义——订单自含，
 * 不依赖版本表与工作区存亡）；系统单源码不快照（交付经 source-package 实时
 * 取），设计面交付物在下单冻结时打包（#297 设计资产包——暂存/落名编排归应用
 * 层，聚合只记类型）；金额单位分（Long）、v1 恒 CNY，随报价落值。projectId
 * 跨上下文软引用（prj_projects，无 FK）；ownerAccountId 冗余下单账号（按用户
 * 查）。
 *
 * <p>状态机（五态单向，骨架不动、语义重对位见 {@link OrderStatus}）：{@link
 * #cancel}（取消门＝未支付态聚合腿；合同签署完成后关门＋取消联动活跃合同
 * 作废留痕的收窄守卫在应用层，#338；运营取消 {@link #cancelByBackoffice}
 * 同守卫带必填原因＋操作者留痕，#157）、{@link #quote}（报价/改价，已报价
 * 态重复调用 = 改价）、{@link #pay}（待支付 → 已支付，落 {@code paidAt}/
 * {@code paymentNo}——<b>新语义＝开工条件满足</b>，#338 起唯一生产调用方＝
 * 开工判定器〔双门禁：合同签署完成 && 开工门节点全部到账〕，mock 支付闭环
 * 已随 #337 退役）与 {@link #archive}（已支付 → 已归档，落 {@code
 * archivedAt}——后台人工终局〔交付完成、尾款核验后〕不再自动，触发只剩
 * {@code retryArchive} 后台口；编排归应用层，#37/#39；运营重试归档
 * {@link #archiveByBackoffice} 同守卫带操作者留痕，#158）；终态判定
 * （{@link OrderStatus#isTerminal}——「同项目至多一个未终结订单」的应用
 * 预检与库侧部分唯一索引共用该口径）。同项目并发下单的最终防线 = 库侧唯一
 * 索引，聚合不做跨行查重。改价留痕在 {@link OrderPriceEntry}（append-only，
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

    /** 范围文本每段上限（#336，与 {@code ord_orders.scope_*} 列宽对齐）。 */
    public static final int SCOPE_MAX_LENGTH = 2000;

    /** 取消原因上限（与 {@code ord_orders.cancel_reason} 列宽对齐，#157）。 */
    public static final int CANCEL_REASON_MAX_LENGTH = 1000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    /** 下单账号（idn_accounts 软引用；无会话上下文可空）。 */
    @Column(name = "owner_account_id", updatable = false)
    private Long ownerAccountId;

    /**
     * 下单访客（#326 访客域：idn_guests 软引用；与 {@link #ownerAccountId} 逻辑互斥
     * ——库层 CHECK 兜底，至多一方落值）。下单者身份的访客腿——SSE 归属路由与
     * 账面归属（访客免注册可下单，绑定平移属 #328）。
     */
    @Column(name = "owner_guest_id", updatable = false)
    private Long ownerGuestId;

    @Column(name = "status", nullable = false)
    private OrderStatus status;

    /**
     * 交付物类型（#297，下单即冻结自项目终点类型；只插不改）：决定下单冻结面——
     * 设计＝设计资产包、系统＋设计＝统一部件容器（源码＋选定设计稿）、系统＝
     * 源码包实时取零冻结件。不参与交易机制（报价/改价/状态机/取消同一律）。
     */
    @Column(name = "deliverable_type", nullable = false, updatable = false)
    private OrderDeliverableType deliverableType;

    /** 下单时 PRD 全文快照（交易标的；只插不改）。 */
    @Column(name = "prd_snapshot", nullable = false, updatable = false)
    private String prdSnapshot;

    /**
     * 下单时已确认 PRD 版本引用（#335：prj_prd_versions 软引用）——{@link #prdSnapshot}
     * 的语义正身＝该版本全文的冗余拷贝（订单自含，不依赖版本表存亡）。只插不改；
     * null＝确认制（V32）之前下的遗留单，如实缺省不虚构引用。
     */
    @Column(name = "prd_version_no", updatable = false)
    private Integer prdVersionNo;

    /**
     * 下单时已确认 Demo 版本引用（#335：下单时项目确认指针〔prj_projects.
     * confirmed_demo_version_no〕的定格值——「确认此版本，准备签约」随单冻结）。
     * 只插不改；null＝遗留单同 {@link #prdVersionNo}。
     */
    @Column(name = "demo_version_no", updatable = false)
    private Integer demoVersionNo;

    /** 当前总价（分；待报价 NULL，报价落值、改价取最新价目行）。 */
    @Column(name = "amount")
    private Long amount;

    /**
     * 范围文本·交付清单（#336 工作副本，后台结构化录入、整组可替换）——「确认
     * 范围并准备合同」时定格为合同上的不可变副本（范围快照）；确认后本副本仍可
     * 改（变动触发草稿合同「需更新」派生标志），快照不漂移。
     */
    @Column(name = "scope_deliverables", length = SCOPE_MAX_LENGTH)
    private String scopeDeliverables;

    /** 范围文本·验收方式（#336，口径同 {@link #scopeDeliverables}）。 */
    @Column(name = "scope_acceptance", length = SCOPE_MAX_LENGTH)
    private String scopeAcceptance;

    /** 范围文本·工期口径（#336，口径同 {@link #scopeDeliverables}）。 */
    @Column(name = "scope_timeline", length = SCOPE_MAX_LENGTH)
    private String scopeTimeline;

    /** ISO 4217 币种（v1 恒 CNY；随报价落）。 */
    @Column(name = "currency", length = 10)
    private String currency;

    /** 首次报价时点（待报价态 NULL；改价不刷新——留痕在价目行）。 */
    @Column(name = "quoted_at")
    private LocalDateTime quotedAt;

    /** 支付成功时点（#338 重对位：＝开工条件满足时点——双门禁判定落定刻）。 */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** 归档时点（支付后独立归档步骤落定）。 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /** 重试归档操作者 id（admin 侧管理员 TSID；支付链自动归档/缺透传头落 NULL，#158）。 */
    @Column(name = "archive_operator_id", length = 64)
    private String archiveOperatorId;

    /** 重试归档操作者名（直读展示；口径同取消留痕两列，#158）。 */
    @Column(name = "archive_operator_name", length = 200)
    private String archiveOperatorName;

    /** 取消时点（未支付态取消即回迭代）。 */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * 取消原因（#157 运营取消必填留痕）：运营内部口径，不呈现任何用户面读面；
     * 用户取消（{@link #cancel}）恒 NULL。
     */
    @Column(name = "cancel_reason", length = CANCEL_REASON_MAX_LENGTH)
    private String cancelReason;

    /** 取消操作者 id（admin 侧管理员 TSID；用户取消/缺透传头落 NULL，#157）。 */
    @Column(name = "cancel_operator_id", length = 64)
    private String cancelOperatorId;

    /** 取消操作者名（直读展示；口径同价目行 operator，#157）。 */
    @Column(name = "cancel_operator_name", length = 200)
    private String cancelOperatorName;

    /** 支付流水号（#338 起＝合同号——开工判定的溯源正身；渠道真接后为渠道单号）。 */
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

    private Order(Long projectId, Long ownerAccountId, Long ownerGuestId, String prdSnapshot,
            OrderDeliverableType deliverableType, int prdVersionNo, int demoVersionNo) {
        if (projectId == null || prdSnapshot == null || prdSnapshot.isBlank()
                || deliverableType == null
                || (ownerAccountId != null && ownerGuestId != null)
                || prdVersionNo <= 0 || demoVersionNo <= 0) {
            throw new DomainException(OrderMessage.ORDER_FIELDS_INCOMPLETE);
        }
        this.projectId = projectId;
        this.ownerAccountId = ownerAccountId;
        this.ownerGuestId = ownerGuestId;
        this.status = OrderStatus.PENDING_QUOTE;
        this.deliverableType = deliverableType;
        this.prdSnapshot = prdSnapshot;
        this.prdVersionNo = prdVersionNo;
        this.demoVersionNo = demoVersionNo;
    }

    /**
     * 下单（确认动作的落库事实，#335 引用语义）：待报价起步，PRD 快照＋双版本
     * 引用＋交付物类型在此冻结——{@code prdSnapshot}＝{@code prdVersionNo} 指向的
     * 已确认版本全文的冗余拷贝（订单自含，不依赖版本表与工作区存亡），{@code
     * demoVersionNo}＝下单时项目确认指针的 Demo 版本（下单前置门在应用层，聚合
     * 只收已过门的正整数版本号）。此后 PRD 修订与项目终点变更均不影响本单
     * （取消再下 = 新单新快照新引用新类型）。下单者归属 owner 双列（账户/访客
     * 互斥，随请求上下文其一，#326）。
     */
    public static Order place(Long projectId, Long ownerAccountId, Long ownerGuestId,
            String prdSnapshot, OrderDeliverableType deliverableType,
            int prdVersionNo, int demoVersionNo) {
        return new Order(projectId, ownerAccountId, ownerGuestId, prdSnapshot, deliverableType,
                prdVersionNo, demoVersionNo);
    }

    /** 是否终态（已归档/已取消）——未终结订单唯一性的判定口径。 */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * 取消（取消门的显式动作，取消即解冻回迭代）：聚合腿门＝任何未支付态
     * （待报价/已报价）可达；已支付与已终结拒绝（ORD_005）。<b>取消边界收窄
     * （#338 接通）</b>：合同签署完成后关门的守卫在应用层（聚合读不到合同，
     * {@code OrderAppService#requireCancellable}——ORD_047）；取消时活跃合同
     * 联动作废留痕同在应用层事务内（系统/操作者留痕）。取消后同项目可再下新单
     * （重新购买 = 新单新快照新引用）。
     */
    public void cancel() {
        if (!status.isUnpaid()) {
            throw new DomainException(OrderMessage.ORDER_CANCEL_NOT_ALLOWED);
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    /**
     * 设计资产包可取守卫（#297 用户面下载 / #298 后台取件共用单点——纯判定先于
     * 支付门与取件）：交付物含设计面（设计/系统＋设计）即过；系统单交付物是
     * 源码包（实时取口径），ORD_016 如实——寻址成功而类型不符不假装有包。
     */
    public void requireDesignDeliverable() {
        if (!deliverableType.designInvolved()) {
            throw new DomainException(OrderMessage.ORDER_DESIGN_PACKAGE_NOT_DELIVERABLE);
        }
    }

    /**
     * 运营取消（#157 后台写口）：状态语义与用户取消完全一致——守卫复用
     * {@link #cancel}（仅待报价/已报价可达，已支付/已终结 ORD_005，不增设状态机
     * 回边），差异仅在必填取消原因＋操作者落痕。原因＝运营内部口径（不呈现用户
     * 面读面）；操作者两列口径同 {@link #quote}（{@code null} = 无头落空）。
     * 输入校验在状态守卫之前（同 {@link #quote} 形制）。
     *
     * @param reason   取消原因（必填；空白抛 ORD_013，超长抛 ORD_014）
     * @param operator 操作者（缺头落空口径，{@code null} 落 NULL 两列）
     */
    public void cancelByBackoffice(String reason, Operator operator) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException(OrderMessage.ORDER_CANCEL_REASON_REQUIRED);
        }
        if (reason.length() > CANCEL_REASON_MAX_LENGTH) {
            throw new DomainException(OrderMessage.ORDER_CANCEL_REASON_TOO_LONG);
        }
        cancel();
        this.cancelReason = reason;
        this.cancelOperatorId = operator == null ? null : operator.id();
        this.cancelOperatorName = operator == null ? null : operator.name();
    }

    /**
     * 提交报价（后台动作）：待报价态首次调用 = 报价（待报价 → 已报价，落
     * {@code quotedAt}）；已报价态重复调用 = 改价（状态不变，{@code quotedAt}
     * 不刷新——改价时点留痕在价目行）。两者统一限未支付态：已支付/已终结拒绝
     * （ORD_007）。每次调用追加一条价目行（append-only，操作者随行落痕），
     * 当前金额与币种取最新价目行——聚合内单事务保证「留痕行」与「订单现值」
     * 一致。
     *
     * @param amount   总价（分，正数；非法抛 ORD_008）
     * @param note     报价备注（后台文本；可空，超长抛 ORD_009）
     * @param operator 操作者（#155 随行落痕；{@code null} = 无头落空口径）
     */
    public void quote(Long amount, String note, Operator operator) {
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
        this.priceEntries.add(OrderPriceEntry.record(amount, CURRENCY_CNY, note, operator));
    }

    /**
     * 录入范围文本（#336 后台动作，整组替换语义）：三段（交付清单/验收方式/工期
     * 口径）必填、每次整组落值。限未支付态（口径同报价，ORD_033）——已支付蕴含
     * 合同签署完成、约定已定，不再受理。工作副本永远领先快照：确认范围后本副本
     * 仍可改，草稿合同的「需更新」标志由对照派生（快照不漂移）。
     *
     * @param scopeDeliverables 交付清单（必填；空白抛 ORD_029，超长抛 ORD_030）
     * @param scopeAcceptance   验收方式（必填；校验同上）
     * @param scopeTimeline     工期口径（必填；校验同上）
     */
    public void enterScope(String scopeDeliverables, String scopeAcceptance, String scopeTimeline) {
        if (isBlank(scopeDeliverables) || isBlank(scopeAcceptance) || isBlank(scopeTimeline)) {
            throw new DomainException(OrderMessage.ORDER_SCOPE_INCOMPLETE);
        }
        if (isOverlong(scopeDeliverables) || isOverlong(scopeAcceptance) || isOverlong(scopeTimeline)) {
            throw new DomainException(OrderMessage.ORDER_SCOPE_TOO_LONG);
        }
        if (!status.isUnpaid()) {
            throw new DomainException(OrderMessage.ORDER_SCOPE_NOT_ALLOWED);
        }
        this.scopeDeliverables = scopeDeliverables.trim();
        this.scopeAcceptance = scopeAcceptance.trim();
        this.scopeTimeline = scopeTimeline.trim();
    }

    /**
     * 范围文本三段是否齐备（#336 确认范围前置·范围腿的判定单点）。
     */
    public boolean scopeEntered() {
        return !isBlank(scopeDeliverables) && !isBlank(scopeAcceptance) && !isBlank(scopeTimeline);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isOverlong(String value) {
        return value != null && value.length() > SCOPE_MAX_LENGTH;
    }

    /**
     * 待支付（已报价）守卫（#37/#39）：支付端口调用前的状态预检面——非待
     * 支付态不触支付（ORD_011）。
     */
    public void requirePayable() {
        if (status != OrderStatus.QUOTED) {
            throw new DomainException(OrderMessage.ORDER_PAY_NOT_ALLOWED);
        }
    }

    /**
     * 支付成功（#37/#39 支付原子化的领域事实；#338 起唯一生产调用方＝开工判定
     * {@code KickoffAppService}）：已报价（=待支付）→ 已支付（<b>新语义＝开工
     * 条件满足</b>：合同签署完成且开工门节点全部到账），落 {@code paidAt}（＝
     * 开工条件满足时点）与支付流水号（判定路径传合同号——无渠道流水的开工溯源
     * 正身）。「已支付」为真实落库中间态（非事务内瞬态）——开工即原子事实，
     * 后续归档是独立步骤（后台人工终局），归档失败不抹开工事实。
     *
     * @param paymentNo 支付流水号（开工判定传合同号；渠道真接后为渠道单号）
     */
    public void pay(String paymentNo) {
        requirePayable();
        if (paymentNo == null || paymentNo.isBlank()) {
            throw new DomainException(OrderMessage.ORDER_FIELDS_INCOMPLETE);
        }
        this.status = OrderStatus.PAID;
        this.paidAt = LocalDateTime.now();
        this.paymentNo = paymentNo;
    }

    /**
     * 归档（#37/#39 支付后的独立步骤）：已支付 → 已归档，落 {@code archivedAt}。
     * 仅已支付态可达——归档是支付成功后的独立动作，非已支付态拒绝（ORD_012）。
     */
    public void archive() {
        if (status != OrderStatus.PAID) {
            throw new DomainException(OrderMessage.ORDER_ARCHIVE_NOT_ALLOWED);
        }
        this.status = OrderStatus.ARCHIVED;
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * 运营重试归档（#158 后台写口）：状态语义与支付链自动归档完全一致——守卫
     * 复用 {@link #archive}（仅已支付可达，ORD_012，不增设状态机回边），差异仅
     * 在操作者落痕（谁补的卡单）。操作者两列口径同 {@link #cancelByBackoffice}
     * （{@code null} = 无头落空）；支付链自动归档（{@link #archive}）两列恒 NULL。
     *
     * @param operator 操作者（缺头落空口径，{@code null} 落 NULL 两列）
     */
    public void archiveByBackoffice(Operator operator) {
        archive(); // 守卫复用：非已支付 ORD_012，留痕不落半截
        this.archiveOperatorId = operator == null ? null : operator.id();
        this.archiveOperatorName = operator == null ? null : operator.name();
    }

    /**
     * 当前报价备注（用户面「后台备注」）＝最新价目行的 note；未报价为 null。
     */
    public String currentQuoteNote() {
        return priceEntries.isEmpty() ? null
                : priceEntries.get(priceEntries.size() - 1).getNote();
    }

    /**
     * 最新价目行（未报价为 {@code null}）：合同冻结集取「冻结时点报价版本」的
     * 单点（#336）。
     */
    public OrderPriceEntry latestPriceEntry() {
        return priceEntries.isEmpty() ? null : priceEntries.get(priceEntries.size() - 1);
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
