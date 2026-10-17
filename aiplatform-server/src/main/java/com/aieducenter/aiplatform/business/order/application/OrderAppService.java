package com.aieducenter.aiplatform.business.order.application;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ConversationHistoryAppService;
import com.aieducenter.aiplatform.business.project.application.DesignPackageAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectKnowledgeAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.web.GuestContext;

import lombok.extern.slf4j.Slf4j;

/**
 * 订单写用例（#18 落下单缝；#28 交易环①详情/取消；#29 报价与改价；#297 交付物
 * 类型＋设计资产包下单冻结——交易机制零改版，下单多冻结一维；#335 下单改版与
 * 订单语义重对位基座）。#337 起 mock 支付闭环退役（同步支付端点与
 * {@code #pay} 用例连代码删除——支付语义并入付款节点凭证核验路径，不留两轨
 * 并存；已支付＝开工条件满足的判定接通归 #338，本服务不再有「支付」入口）：确认下单 = 过双确认门（已确认 PRD 版本＋Demo 确认版本）
 * 后冻结 PRD 确认版全文冗余拷贝＋双版本引用＋交付物类型入单，待报价起步
 * （「待确认报价」占位态）。下单快照的上游＝PRD 确认版本（#330 确认即成版、
 * #332 生成加门：确认是首个构建 run 与下单的共同引用锚——#335 起快照不再读
 * 工作区正文、引用语义落位）。
 * 项目事实（存在性/归档态/双确认引用/终点类型）经 {@code business.project} 应用
 * 层软引用——跨 BC 无 FK，同一口径。
 *
 * <p>「同项目至多一个未终结订单」双保险：预检（{@code findActiveByProject}，
 * 409 ORD_003）+ 库侧部分唯一索引兜底（并发漏过预检时约束拒绝，同译 ORD_003）。
 * place/cancel/submitQuote 刻意不加 {@code @Transactional}：单行插入由仓储自带
 * 事务保证；约束撞错的吸收（catch 后翻译）要求 save 的事务已独立结束，不处在
 * 外层事务中（同 MeteringAppService.report 形制）。{@link #retryArchive}（#158
 * 后台手动补归档口）用 {@link TransactionTemplate} 收「订单＋项目」一个事务，
 * 失败异常上抛（运营须看见拦截原因）；知识沉淀与 SSE 在事务提交后（#5 决议；
 * 沉淀降级不炸——embedding 不可用不允许回滚已成功的归档）。</p>
 */
@Service
@Slf4j
public class OrderAppService {

    /** 未终结订单唯一索引名（约束撞错的判别键，与 V1__baseline.sql 对齐）。 */
    private static final String ACTIVE_ORDER_INDEX = "uk_ord_orders_active";

    private final OrderRepository orderRepository;
    private final ProjectQueryAppService projectQueryAppService;
    private final ProjectLifecycleAppService projectLifecycleAppService;
    private final ProjectKnowledgeAppService projectKnowledgeAppService;
    private final ConversationHistoryAppService conversationHistoryAppService;
    private final DesignPackageAppService designPackageAppService;
    private final OrderQueryAppService orderQueryAppService;
    private final EventsAppService eventsAppService;
    private final TransactionTemplate transactionTemplate;

    public OrderAppService(OrderRepository orderRepository,
                           ProjectQueryAppService projectQueryAppService,
                           ProjectLifecycleAppService projectLifecycleAppService,
                           ProjectKnowledgeAppService projectKnowledgeAppService,
                           ConversationHistoryAppService conversationHistoryAppService,
                           DesignPackageAppService designPackageAppService,
                           OrderQueryAppService orderQueryAppService,
                           EventsAppService eventsAppService,
                           TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.projectQueryAppService = projectQueryAppService;
        this.projectLifecycleAppService = projectLifecycleAppService;
        this.projectKnowledgeAppService = projectKnowledgeAppService;
        this.conversationHistoryAppService = conversationHistoryAppService;
        this.designPackageAppService = designPackageAppService;
        this.orderQueryAppService = orderQueryAppService;
        this.eventsAppService = eventsAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 确认下单（#335 引用语义落位）：下单前置门＝存在已确认 PRD 版本＋Demo 确认
     * 版本（「确认此版本，准备签约」＝下单入口——现「确认下单」按钮语义改挂；
     * 无确认如实拦截 ORD_018/ORD_019，前端 toast 提示）；过门即冻结双版本引用＋
     * PRD 确认版全文冗余拷贝（快照源＝已确认版本，非工作区正文——订单自含）＋
     * 交付物类型入单（待报价起步，「待确认报价」占位态——后台落定报价〔＋范围
     * 文本，#336 起〕后翻已报价）。下单即冻结迭代——对话区停止受理意见（project
     * 上下文冻结守卫，{@code ORD_006}）。交付物类型自项目终点类型冻结（#297
     * 一单一程——此后项目终点变更不影响本单，取消再下 = 新单新类型）。设计面单
     * （设计/系统＋设计）在下单时冻结设计资产包：<b>暂存打包先于落库</b>（打包
     * 失败＝下单失败零残留，不产生「有单无包」半成态）、<b>落名在落库后</b>（以
     * 落库分配的订单 id 定格该单冻结件——同目录原子改名，失败仅容器中途消亡可
     * 达，如实上抛）；系统单零打包（源码包实时取口径不变）。取消不清理冻结件
     * （后台取件正本，残留备案口径）。
     *
     * @return 订单（待报价起步，双版本引用＋快照与交付物类型已冻结）
     * @throws ApplicationException PRJ_001 项目不存在（项目上下文原样透传）；
     *                              ORD_017 签约门——未登录绑定（访客身份）走下单
     *                              入口被拦，前端认 ORD_017 弹 M01；ORD_004 项目已
     *                              归档；ORD_003 该项目已有未终结订单（预检或并发
     *                              撞索引，先于版本门——重复下单的主导事实）；
     *                              ORD_018 无已确认 PRD 版本；ORD_019 未确认 Demo
     *                              版本；WSP_002 设计资产包打包失败（环境故障，
     *                              零残留）
     */
    public OrderResponse place(Long projectId) {
        // 签约门（#328，spec 段②）：进下单/签约流程即要求绑定——访客身份（有效
        // 访客凭证、无账户会话）被拦，前端认 ORD_017 弹 M01；绑定（登录即平移）
        // 后账户会话在场即过闸。守卫最前：未绑定者不触任何下单副作用。
        if (GuestContext.guestId() != null) {
            throw new ApplicationException(OrderMessage.ORDER_BINDING_REQUIRED);
        }
        ProjectDetailResponse project = projectQueryAppService.detail(projectId);
        if (project.archived()) {
            throw new ApplicationException(OrderMessage.ORDER_PROJECT_ARCHIVED);
        }
        if (orderRepository.findActiveByProject(projectId).isPresent()) {
            throw new ApplicationException(OrderMessage.ORDER_ALREADY_ACTIVE);
        }
        // 下单前置门（#335，spec #320 ④「加门」）：双版本确认缺一即拦（先于昂贵
        // 的打包面——廉价库读守卫前置惯例）。引用集一次取齐：版本号＋确认版全文。
        ProjectQueryAppService.ConfirmedVersionRefs refs =
                projectQueryAppService.confirmedVersionRefs(projectId);
        if (refs.prdVersionNo() == null) {
            throw new ApplicationException(OrderMessage.ORDER_PRD_VERSION_REQUIRED);
        }
        if (refs.demoVersionNo() == null) {
            throw new ApplicationException(OrderMessage.ORDER_DEMO_VERSION_REQUIRED);
        }
        OrderDeliverableType deliverableType =
                OrderDeliverableType.ofCode(project.endpointType().getCode());
        // 快照源＝已确认版本全文（冗余拷贝，#335）——不读工作区（过门即必有确认
        // 版全文，PRJ_015 未产出不再可能是下单障碍）
        String prdSnapshot = refs.prdContent();
        DesignPackageAppService.StagedPackage staged = deliverableType.designInvolved()
                ? designPackageAppService.stageForOrder(projectId)
                : null;
        Order order = Order.place(projectId, RequestContext.getUserId(),
                GuestContext.guestId(), prdSnapshot, deliverableType,
                refs.prdVersionNo(), refs.demoVersionNo());
        try {
            order = orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            // 并发下单撞部分唯一索引（预检漏过）：后到者拒绝，同口径翻译；
            // 非本索引的完整性违例不冒名，原样上抛
            if (violatesActiveOrderIndex(e)) {
                log.info("项目 {} 并发下单撞未终结订单唯一索引，拒绝", projectId);
                throw new ApplicationException(OrderMessage.ORDER_ALREADY_ACTIVE);
            }
            throw e;
        }
        if (staged != null) {
            designPackageAppService.sealForOrder(staged, order.getId());
        }
        publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, order);
        return OrderResponse.of(order);
    }

    /**
     * 用户面设计资产包下载（#297，对偶源码包端点——订单锚取件：交付物包是
     * 下单冻结件、按单定格）：守卫序＝订单存在 → 交付物类型（系统单无设计资产包
     * ，ORD_016 如实）→ 支付门（{@link OrderQueryAppService#requireDownloadable}
     * 单点：曾支付/已归档即开放，ORD_015）→ 冻结件读取（PRJ_051 缺失不顶替）。
     * 文件名/HTTP 头归 REST 层。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_016 系统单交付物不含
     *                              设计资产包；ORD_015 未支付（门语义）；PRJ_051
     *                              冻结件缺失；WSP_002 读取失败（环境故障）
     */
    public byte[] downloadableDesignPackage(Long orderId) {
        Order order = requireOrder(orderId);
        order.requireDesignDeliverable();
        orderQueryAppService.requireDownloadable(order.getProjectId());
        return designPackageAppService.frozenPackage(order.getProjectId(), orderId);
    }

    /**
     * 订单详情（用户面）。
     *
     * @throws ApplicationException ORD_001 订单不存在
     */
    public OrderResponse detail(Long orderId) {
        return OrderResponse.of(requireOrder(orderId));
    }

    /**
     * 取消订单（未支付态取消即解冻回迭代）：自待报价/已报价可达，已支付与
     * 已终结拒绝（聚合守卫 ORD_005）。事务取舍同 {@link #place}——单行状态
     * 更新由仓储自带事务保证，刻意不加 {@code @Transactional}。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_005 已支付或已终结
     */
    public OrderResponse cancel(Long orderId) {
        Order order = requireOrder(orderId);
        order.cancel();
        OrderResponse response = OrderResponse.of(orderRepository.save(order));
        publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, order);
        return response;
    }

    /**
     * 运营取消订单（#157 后台写口，经 BackofficeOrderController 进入）：状态
     * 语义与用户取消 {@link #cancel} 完全一致（守卫 ORD_005、通知同发、解冻
     * 回迭代可再下单），差异仅在必填取消原因＋操作者留痕落订单行（运营内部
     * 口径，不呈现用户面读面）。事务取舍同 {@link #cancel}。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_005 已支付或已终结；
     *                              ORD_013 原因缺失；ORD_014 原因超长
     */
    public OrderResponse cancelByBackoffice(Long orderId, String reason, Operator operator) {
        Order order = requireOrder(orderId);
        order.cancelByBackoffice(reason, operator);
        OrderResponse response = OrderResponse.of(orderRepository.save(order));
        publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, order);
        return response;
    }

    /**
     * 提交报价（#29 后台动作，机机面经 BackofficeOrderController 进入）：待报价态
     * 首次调用 = 报价，已报价态重复调用 = 改价——聚合内一次事务同时落价目行
     * （append-only，操作者随行落痕，#155）与订单现值。事务取舍同
     * {@link #cancel}：单聚合保存（级联追加价目行）由仓储自带事务保证。首次报价
     * 发「状态已变化」（状态真变化）+ 落「报价已出」卡；改价发 order-repriced
     * （#204 改价入流，推翻「改价不换状态不发」的静默）+ 追加「报价已更新」卡
     * （append-only 不改旧卡）。两路都先落卡后发信号——信号触发前端重查时卡须已在
     * 库；卡载荷仅事件 + 订单引用，不含金额（#203 视镜语义）。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_008 金额无效；
     *                              ORD_009 备注超长；ORD_007 已支付或已终结
     */
    public OrderResponse submitQuote(Long orderId, Long amount, String note, Operator operator) {
        Order order = requireOrder(orderId);
        boolean firstQuote = order.getStatus() == OrderStatus.PENDING_QUOTE;
        order.quote(amount, note, operator);
        OrderResponse response = OrderResponse.of(orderRepository.save(order));
        if (firstQuote) {
            conversationHistoryAppService.recordQuote(order.getProjectId(), order.getId(),
                    ConversationHistoryAppService.QUOTE_EVENT_QUOTED);
            publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, order);
        } else {
            conversationHistoryAppService.recordQuote(order.getProjectId(), order.getId(),
                    ConversationHistoryAppService.QUOTE_EVENT_REPRICED);
            publishNotification(OrderEventTypes.ORDER_REPRICED, order);
        }
        return response;
    }

    /**
     * 录入范围文本（#336 后台动作，机机面经 BackofficeOrderController 进入）：
     * 三段（交付清单/验收方式/工期口径）整组替换落订单工作副本（聚合守卫
     * ORD_029/030/033）。工作副本永远领先快照——确认范围后改本副本触发草稿
     * 合同「需更新」派生标志（对照现算，不在此落任何标志）。事务取舍同
     * {@link #cancel}：单聚合更新由仓储自带事务保证。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_029 三段缺失；
     *                              ORD_030 超长；ORD_033 已支付或已终结
     */
    public OrderResponse enterScope(Long orderId, String scopeDeliverables,
            String scopeAcceptance, String scopeTimeline) {
        Order order = requireOrder(orderId);
        order.enterScope(scopeDeliverables, scopeAcceptance, scopeTimeline);
        return OrderResponse.of(orderRepository.save(order));
    }

    /**
     * 重试归档（#158 后台写口，经 BackofficeOrderController 进入）：对「已支付但
     * 归档失败」的卡单手动补归档。归档事务＝订单＋项目一事务（守卫复用：非已
     * 支付 ORD_012、项目重复归档 PRJ_013），失败异常原样上抛（运营须看见拦截
     * 原因，不留静默半成态）。成功收尾同款 {@link #settleArchive}：重读
     * 库定真，已归档发「已归档」通知并触发知识沉淀（沉淀跟随归档成功——支付链
     * 与本口皆触发）。操作者留痕落订单行（缺头落空；支付链自动归档为 NULL）。
     * 不做自动 Scheduled 补偿（隐藏状态机，v1 单量不值当）。
     *
     * @return 订单（已归档终态）
     * @throws ApplicationException ORD_001 订单不存在；ORD_012 非已支付状态（聚合
     *                              守卫，含重复触发）；PRJ_013 项目重复归档
     */
    public OrderResponse retryArchive(Long orderId, Operator operator) {
        Order order = requireOrder(orderId);
        Long projectId = order.getProjectId();
        transactionTemplate.executeWithoutResult(status -> {
            order.archiveByBackoffice(operator);
            orderRepository.save(order);
            projectLifecycleAppService.archive(projectId);
        });
        return settleArchive(orderId, projectId);
    }

    /**
     * 归档收尾（#158 起支付链与重试归档共用）：重读库定真——归档事务回滚不还原
     * 内存对象，通知与沉淀以库内真值为准（已归档才发「已归档」通知并触发知识
     * 沉淀；沉淀 best-effort，降级不炸的既有口径不变）。
     */
    private OrderResponse settleArchive(Long orderId, Long projectId) {
        Order persisted = requireOrder(orderId);
        if (persisted.getStatus() == OrderStatus.ARCHIVED) {
            publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, persisted); // 归档落定发「已归档」
            projectKnowledgeAppService.sinkPrd(projectId);
        }
        return OrderResponse.of(persisted);
    }

    /**
     * 通知族发射（payload 两事件同形：projectId/orderId/status/statusName，不含金额；
     * 归属路由键双键（ownerAccountId/ownerGuestId）取自订单聚合——含后台路径，非操作者
     * 上下文，ADR-0018；#326 访客名下订单走访客键）。
     */
    private void publishNotification(String eventType, Order order) {
        eventsAppService.publishNotification(eventType, Map.of(
                OrderEventTypes.PROJECT_ID_FIELD, order.getProjectId().toString(),
                OrderEventTypes.ORDER_ID_FIELD, order.getId().toString(),
                OrderEventTypes.STATUS_FIELD, order.getStatus().getCode(),
                OrderEventTypes.STATUS_NAME_FIELD, order.getStatus().getName(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(order.getOwnerAccountId()),
                EventsAppService.OWNER_GUEST_FIELD,
                EventsAppService.ownerPayload(order.getOwnerGuestId())));
    }

    private static boolean violatesActiveOrderIndex(DataIntegrityViolationException e) {
        return mentions(e.getMessage()) || mentions(e.getMostSpecificCause().getMessage());
    }

    private static boolean mentions(String message) {
        return message != null && message.contains(ACTIVE_ORDER_INDEX);
    }

    private Order requireOrder(Long orderId) {
        // 带价目历史：detail/cancel/submitQuote 的响应拼装要读价目集合，取齐再出会话
        return orderRepository.findWithHistory(orderId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_NOT_FOUND));
    }
}
