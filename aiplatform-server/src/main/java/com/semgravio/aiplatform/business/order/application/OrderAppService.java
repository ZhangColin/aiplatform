package com.semgravio.aiplatform.business.order.application;

import java.util.function.Consumer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.order.application.dto.response.OrderResponse;
import com.semgravio.aiplatform.business.order.domain.aggregate.Contract;
import com.semgravio.aiplatform.business.order.domain.aggregate.Order;
import com.semgravio.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.semgravio.aiplatform.business.order.domain.enums.OrderStatus;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;
import com.semgravio.aiplatform.business.order.domain.repository.ContractRepository;
import com.semgravio.aiplatform.business.order.domain.repository.OrderRepository;
import com.semgravio.aiplatform.business.project.application.ConversationHistoryAppService;
import com.semgravio.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.semgravio.aiplatform.business.project.application.ProjectQueryAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.semgravio.aiplatform.web.GuestContext;

import lombok.extern.slf4j.Slf4j;

/**
 * 订单写用例（#18 落下单缝；#28 交易环①详情/取消；#29 报价与改价；#297 交付物
 * 类型下单冻结；#611 交付物类型收窄为系统单值〔设计线退役〕；#335 下单改版与
 * 订单语义重对位基座；#338 取消边界收窄＋归档取版对齐）。#337 起 mock 支付闭环
 * 退役（同步支付端点与 {@code #pay} 用例连代码删除——支付语义并入付款节点凭证
 * 核验路径，不留两轨并存；「已支付＝开工条件满足」的判定器＝{@link
 * KickoffAppService}〔#338 接通〕，本服务不再有「支付」入口）：确认下单 = 过下单
 * 前置门（PRD 已产出＋存在已确认用户面版本，<b>#629 商业面改接</b>）后冻结当前
 * 工作区 PRD 全文冗余拷贝＋已确认用户面版本号＋交付物类型入单，待报价起步
 * （「待确认报价」占位态）。版本引用收敛为一个用户面版本号（不再读旧 PRD/Demo
 * 版本表）；历史订单自含双版本引用与 PRD 快照，照常可读。
 * 项目事实（存在性/归档态/双确认引用）经 {@code business.project} 应用
 * 层软引用——跨 BC 无 FK，同一口径。
 *
 * <p>「同项目至多一个未终结订单」双保险：预检（{@code findActiveByProject}，
 * 409 ORD_003）+ 库侧部分唯一索引兜底（并发漏过预检时约束拒绝，同译 ORD_003）。
 * place/submitQuote 刻意不加 {@code @Transactional}：单行插入由仓储自带事务
 * 保证；约束撞错的吸收（catch 后翻译）要求 save 的事务已独立结束，不处在
 * 外层事务中（同 MeteringAppService.report 形制）。{@link #cancel}/
 * {@link #cancelByBackoffice}（#338 收窄后）用 {@link TransactionTemplate} 收
 * 「订单取消＋活跃合同联动作废」一个事务；{@link #retryArchive}（#158 后台
 * 手动补归档口——已归档＝后台人工终局的唯一触发）同款收「订单＋项目」一个
 * 事务，失败异常上抛（运营须看见拦截原因）；SSE 在事务提交后（#5 决议）。
 * 知识入库 #640 起改挂用户面版本成版——订单归档不再触发沉淀。</p>
 */
@Service
@Slf4j
public class OrderAppService {

    /** 未终结订单唯一索引名（约束撞错的判别键，与 V1__baseline.sql 对齐）。 */
    private static final String ACTIVE_ORDER_INDEX = "uk_ord_orders_active";

    /**
     * 取消联动作废的系统操作者（#338）：用户取消路径无操作者，而作废留痕要求
     * 操作者非空（ORD_035 审计本体）——系统身份如实落痕，不放松聚合守卫。
     */
    private static final Operator CANCEL_LINKAGE_OPERATOR = new Operator("system", "订单取消联动");

    private final OrderRepository orderRepository;
    private final ContractRepository contractRepository;
    private final ProjectQueryAppService projectQueryAppService;
    private final ProjectLifecycleAppService projectLifecycleAppService;
    private final ConversationHistoryAppService conversationHistoryAppService;
    private final EventsAppService eventsAppService;
    private final TransactionTemplate transactionTemplate;

    public OrderAppService(OrderRepository orderRepository,
                           ContractRepository contractRepository,
                           ProjectQueryAppService projectQueryAppService,
                           ProjectLifecycleAppService projectLifecycleAppService,
                           ConversationHistoryAppService conversationHistoryAppService,
                           EventsAppService eventsAppService,
                           TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.contractRepository = contractRepository;
        this.projectQueryAppService = projectQueryAppService;
        this.projectLifecycleAppService = projectLifecycleAppService;
        this.conversationHistoryAppService = conversationHistoryAppService;
        this.eventsAppService = eventsAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 确认下单（#335 引用语义落位；<b>#629 商业面改接用户面版本</b>——ADR-0056）：
     * 下单前置门＝PRD 已产出＋存在已确认用户面版本（「确认此版本，准备签约」＝
     * 下单入口；缺失如实拦截 ORD_018/ORD_019，前端 toast 提示）；过门即冻结当前
     * 工作区 PRD 全文冗余拷贝＋已确认用户面版本号（旧的两套版本引用收敛为一个；
     * 不读旧 PRD/Demo 版本表——订单自含）＋交付物类型入单（待报价起步，「待确认
     * 报价」占位态——后台落定报价〔＋范围文本，#336 起〕后翻已报价）。下单即冻结
     * 迭代——对话区停止受理意见（project 上下文冻结守卫，{@code ORD_006}）。交付物
     * 类型一律冻结为系统单值（#611——设计线退役后不再从项目终点类型映射设计交付物；
     * 只插不改）。取消即解冻回迭代。
     *
     * @return 订单（待报价起步，已确认用户面版本号＋PRD 快照与交付物类型已冻结）
     * @throws ApplicationException PRJ_001 项目不存在（项目上下文原样透传）；
     *                              ORD_017 签约门——未登录绑定（访客身份）走下单
     *                              入口被拦，前端认 ORD_017 弹 M01；ORD_004 项目已
     *                              归档；ORD_003 该项目已有未终结订单（预检或并发
     *                              撞索引，先于版本门——重复下单的主导事实）；
     *                              ORD_018 PRD 未产出（当前工作区无 PRD 全文）；
     *                              ORD_019 未确认用户面版本
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
        // 下单前置门（#629，ADR-0056 商业面改接）：PRD 已产出（冻结其全文）＋存在
        // 已确认用户面版本，缺一即拦（先于昂贵的打包面——廉价读守卫前置惯例）。
        ProjectQueryAppService.OrderFreezeRefs refs =
                projectQueryAppService.orderFreezeRefs(projectId);
        if (refs.prdContent() == null) {
            throw new ApplicationException(OrderMessage.ORDER_PRD_VERSION_REQUIRED);
        }
        if (refs.confirmedVersionNo() == null) {
            throw new ApplicationException(OrderMessage.ORDER_DEMO_VERSION_REQUIRED);
        }
        // 快照源＝当前工作区 PRD 全文（冗余拷贝）＋已确认用户面版本号（两套旧版本
        // 引用收敛为一个，不再依赖旧 PRD/Demo 版本表）
        // #611 交付物类型收窄为系统单值：不再从项目终点类型映射（设计线退役）
        Order order = Order.place(projectId, RequestContext.getUserId(),
                GuestContext.guestId(), refs.prdContent(), OrderDeliverableType.SYSTEM,
                refs.confirmedVersionNo());
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
        publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, order);
        return OrderResponse.of(order);
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
     * 已终结拒绝（聚合守卫 ORD_005）。<b>取消边界收窄（#338 接通，spec #320
     * ④）</b>：最近合同已签署完成后关门（ORD_047——需求变化走对话区变更沟通，
     * 不建平台机制；聚合读不到合同，守卫在本应用层）；取消时活跃（非终态）
     * 合同联动作废留痕（同事务，系统操作者落痕——用户取消无操作者，不放松
     * 聚合 ORD_035 审计守卫）。无合同单（含遗留）行为不变。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_005 已支付或已终结；
     *                              ORD_047 合同已签署完成
     */
    public OrderResponse cancel(Long orderId) {
        Order order = requireOrder(orderId);
        requireCancellable(order);
        cancelWithContractLinkage(order, Order::cancel, CANCEL_LINKAGE_OPERATOR,
                "用户取消订单，活跃合同联动作废");
        OrderResponse response = OrderResponse.of(order);
        publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, order);
        return response;
    }

    /**
     * 运营取消订单（#157 后台写口，经 BackofficeOrderController 进入）：状态
     * 语义与用户取消 {@link #cancel} 完全一致（守卫 ORD_005/ORD_047、通知同发、
     * 取消联动同款、解冻回迭代可再下单），差异仅在必填取消原因＋操作者留痕
     * 落订单行（运营内部口径，不呈现用户面读面）——联动作废留痕溯操作者本人
     * （缺头落系统操作者，不放松聚合守卫）。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_005 已支付或已终结；
     *                              ORD_047 合同已签署完成；ORD_013 原因缺失；
     *                              ORD_014 原因超长
     */
    public OrderResponse cancelByBackoffice(Long orderId, String reason, Operator operator) {
        Order order = requireOrder(orderId);
        requireCancellable(order);
        cancelWithContractLinkage(order, o -> o.cancelByBackoffice(reason, operator),
                operator == null ? CANCEL_LINKAGE_OPERATOR : operator,
                "后台取消订单：" + reason);
        OrderResponse response = OrderResponse.of(order);
        publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, order);
        return response;
    }

    /**
     * 取消门收窄守卫（#338，应用层——聚合读不到合同）：最近合同已签署完成
     * 即拒（ORD_047，谓词单点＝{@code findLatestSignedByOrderId}）。非未支付态
     * 先归聚合守卫（ORD_005 既有语义正身，先到先得——已支付蕴含签署完成，报
     * 「已支付不可取消」语义更准）。
     */
    private void requireCancellable(Order order) {
        if (!order.getStatus().isUnpaid()) {
            return;
        }
        if (contractRepository.findLatestSignedByOrderId(order.getId()).isPresent()) {
            throw new ApplicationException(OrderMessage.ORDER_CANCEL_SIGNED);
        }
    }

    /**
     * 取消＋活跃合同联动作废（#338，同事务——先例 {@link #retryArchive} 形制）：
     * 订单取消动作与合同作废一起落库（半成态不留），无活跃合同时退化为单行
     * 取消（行为与收窄前一致）。作废依据必含「取消订单」语、操作者随行留痕。
     * 合同在事务内带留痕加载（懒集合并发初始化安全，同付款域事务取舍）。
     */
    private void cancelWithContractLinkage(Order order, Consumer<Order> cancelAction,
            Operator voidOperator, String voidBasis) {
        Long activeContractId = contractRepository.findActiveByOrderId(order.getId())
                .map(Contract::getId).orElse(null);
        transactionTemplate.executeWithoutResult(status -> {
            cancelAction.accept(order);
            orderRepository.save(order);
            if (activeContractId != null) {
                Contract activeContract = contractRepository.findWithTrail(activeContractId).orElseThrow();
                activeContract.voidContract(voidBasis, voidOperator);
                contractRepository.save(activeContract);
            }
        });
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
     * 库定真，已归档发「已归档」通知（知识入库改挂成版，#640——本口不再触发）。
     * 操作者留痕落订单行（缺头落空；支付链自动归档为 NULL）。
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
        return settleArchive(orderId);
    }

    /**
     * 归档收尾（#158 起支付链与重试归档共用）：重读库定真——归档事务回滚不还原
     * 内存对象，通知以库内真值为准（已归档才发「已归档」通知）。
     * #640 起知识入库改挂用户面版本成版，订单归档不再触发沉淀（取版辅助逻辑
     * 随之退场）。
     */
    private OrderResponse settleArchive(Long orderId) {
        Order persisted = requireOrder(orderId);
        if (persisted.getStatus() == OrderStatus.ARCHIVED) {
            publishNotification(OrderEventTypes.ORDER_STATUS_CHANGED, persisted); // 归档落定发「已归档」
        }
        return OrderResponse.of(persisted);
    }

    /**
     * 通知族发射（payload 构造单点＝{@code OrderEventTypes#notificationPayload}，
     * #338 收敛——两事件同形：projectId/orderId/status/statusName，不含金额；归属
     * 路由键双键（ownerAccountId/ownerGuestId）取自订单聚合——含后台路径，非操作者
     * 上下文，ADR-0018；#326 访客名下订单走访客键）。
     */
    private void publishNotification(String eventType, Order order) {
        eventsAppService.publishNotification(eventType, OrderEventTypes.notificationPayload(order));
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
