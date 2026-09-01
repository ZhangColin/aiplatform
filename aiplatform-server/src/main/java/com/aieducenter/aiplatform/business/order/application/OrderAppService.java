package com.aieducenter.aiplatform.business.order.application;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.port.PaymentPort;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ProjectKnowledgeAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;

import lombok.extern.slf4j.Slf4j;

/**
 * 订单写用例（#18 落下单缝；#28 交易环①详情/取消；#29 报价与改价；#30 交易环③
 * mock 支付——#37/#39 支付原子化：支付原子（落已支付）与归档（订单 + 项目）拆
 * 两个事务，知识沉淀归档后 best-effort + 订单态变化通知两发）：确认下单 = 冻结
 * 下单时 PRD 全文快照入单，待报价起步。项目事实（存在性/归档态/PRD 正文）经
 * {@code business.project} 应用层软引用——跨 BC 无 FK，同一口径。
 *
 * <p>「同项目至多一个未终结订单」双保险：预检（{@code findActiveByProject}，
 * 409 ORD_003）+ 库侧部分唯一索引兜底（并发漏过预检时约束拒绝，同译 ORD_003）。
 * place/cancel/submitQuote 刻意不加 {@code @Transactional}：单行插入由仓储自带
 * 事务保证；约束撞错的吸收（catch 后翻译）要求 save 的事务已独立结束，不处在
 * 外层事务中（同 MeteringAppService.report 形制）。唯 {@link #pay} 用
 * {@link TransactionTemplate} 收两个短事务——①支付原子独立落「已支付」；②归档
 * 独立事务，失败留「已支付」不抹支付事实（#37/#39）；知识沉淀与 SSE 在事务提交
 * 后（#5 决议：「支付成功归档动作之后」；沉淀降级不炸——embedding 不可用不允许
 * 回滚已成功的支付）。</p>
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
    private final PaymentPort paymentPort;
    private final PlatformNotificationAppService notificationAppService;
    private final TransactionTemplate transactionTemplate;

    public OrderAppService(OrderRepository orderRepository,
                           ProjectQueryAppService projectQueryAppService,
                           ProjectLifecycleAppService projectLifecycleAppService,
                           ProjectKnowledgeAppService projectKnowledgeAppService,
                           PaymentPort paymentPort,
                           PlatformNotificationAppService notificationAppService,
                           TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.projectQueryAppService = projectQueryAppService;
        this.projectLifecycleAppService = projectLifecycleAppService;
        this.projectKnowledgeAppService = projectKnowledgeAppService;
        this.paymentPort = paymentPort;
        this.notificationAppService = notificationAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 确认下单：读当前 PRD → 冻结快照入单（待报价）。下单即冻结迭代——指令区
     * 停止受理意见（project 上下文冻结守卫，{@code ORD_006}）。
     *
     * @return 订单（待报价起步，快照已冻结）
     * @throws ApplicationException PRJ_001 项目不存在 / PRJ_015 PRD 未产出
     *                              （项目上下文原样透传）；ORD_004 项目已归档；
     *                              ORD_003 该项目已有未终结订单（预检或并发撞索引）
     */
    public OrderResponse place(Long projectId) {
        if (projectQueryAppService.detail(projectId).archived()) {
            throw new ApplicationException(OrderMessage.ORDER_PROJECT_ARCHIVED);
        }
        if (orderRepository.findActiveByProject(projectId).isPresent()) {
            throw new ApplicationException(OrderMessage.ORDER_ALREADY_ACTIVE);
        }
        Order order = Order.place(projectId, RequestContext.getUserId(),
                projectQueryAppService.prd(projectId).content());
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
        publishStatusChanged(order);
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
     * 已终结拒绝（聚合守卫 ORD_005）。事务取舍同 {@link #place}——单行状态
     * 更新由仓储自带事务保证，刻意不加 {@code @Transactional}。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_005 已支付或已终结
     */
    public OrderResponse cancel(Long orderId) {
        Order order = requireOrder(orderId);
        order.cancel();
        OrderResponse response = OrderResponse.of(orderRepository.save(order));
        publishStatusChanged(order);
        return response;
    }

    /**
     * 提交报价（#29 后台动作，机机面经 BackofficeOrderController 进入）：待报价态
     * 首次调用 = 报价，已报价态重复调用 = 改价——聚合内一次事务同时落价目行
     * （append-only）与订单现值。事务取舍同 {@link #cancel}：单聚合保存（级联
     * 追加价目行）由仓储自带事务保证。通知只在状态真变化（首次报价）时发射——
     * 改价不换状态不发。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_008 金额无效；
     *                              ORD_009 备注超长；ORD_007 已支付或已终结
     */
    public OrderResponse submitQuote(Long orderId, Long amount, String note) {
        Order order = requireOrder(orderId);
        boolean firstQuote = order.getStatus() == OrderStatus.PENDING_QUOTE;
        order.quote(amount, note);
        OrderResponse response = OrderResponse.of(orderRepository.save(order));
        if (firstQuote) {
            publishStatusChanged(order);
        }
        return response;
    }

    /**
     * mock 支付（#37/#39 支付原子化）：已报价（=待支付）态同步只走成功路径，支付
     * 成功即订单落「已支付」真实中间态。拆两个事务——①支付原子（{@link Order#pay}
     * + save，独立短事务，落 paidAt/paymentNo）；②归档（{@link Order#archive} +
     * save + {@link ProjectLifecycleAppService#archive} 项目归档，独立事务）——
     * 归档失败 catch 留「已支付」不抹支付事实（补偿归后续批次）。通知两发：
     * 支付落定发「已支付」、归档落定发「已归档」，归档失败只发前者；通知以库内
     * 真值为准（归档事务回滚不还原内存对象，失败路径不得误发「已归档」）。知识
     * 沉淀在归档后 best-effort（取归档时最新版 PRD 入库，唯一沉淀触发点；内部
     * 降级不炸，丢失容忍）。
     *
     * @return 订单（归档成功 = 已归档终态；归档失败 = 已支付中间态，paidAt/
     *         paymentNo 已落、archivedAt 未落）
     * @throws ApplicationException ORD_001 订单不存在；ORD_011 非待支付状态
     *                              （聚合守卫，含已支付/已归档/已取消）
     */
    public OrderResponse pay(Long orderId) {
        Order order = requireOrder(orderId);
        order.requirePayable(); // 非待支付不触支付端口（ORD_011）
        String paymentNo = paymentPort.pay(order.getId(), order.getAmount(), order.getCurrency());
        Long projectId = order.getProjectId();

        // ① 支付原子（独立短事务）：已支付真实落库——支付成功即原子事实
        transactionTemplate.executeWithoutResult(status -> {
            order.pay(paymentNo);
            orderRepository.save(order);
        });
        publishStatusChanged(order); // 支付落定发「已支付」

        // ② 归档（独立事务）：失败留已支付、日志留痕，不抹支付事实（补偿归后续批次）
        try {
            transactionTemplate.executeWithoutResult(status -> {
                order.archive();
                orderRepository.save(order);
                projectLifecycleAppService.archive(projectId);
            });
        } catch (RuntimeException e) {
            log.warn("订单 {} 支付成功但归档失败（留已支付，补偿归后续批次）：{}",
                    orderId, e.getMessage());
        }

        // 通知以库内真值为准（归档事务回滚不还原内存对象）：重读库定真——已归档才
        // 发「已归档」并触发沉淀
        Order persisted = requireOrder(orderId);
        if (persisted.getStatus() == OrderStatus.ARCHIVED) {
            publishStatusChanged(persisted); // 归档落定发「已归档」
            projectKnowledgeAppService.sinkPrd(projectId);
        }
        return OrderResponse.of(persisted);
    }

    private void publishStatusChanged(Order order) {
        notificationAppService.publish(OrderEventTypes.ORDER_STATUS_CHANGED, Map.of(
                OrderEventTypes.PROJECT_ID_FIELD, order.getProjectId().toString(),
                OrderEventTypes.ORDER_ID_FIELD, order.getId().toString(),
                OrderEventTypes.STATUS_FIELD, order.getStatus().getCode(),
                OrderEventTypes.STATUS_NAME_FIELD, order.getStatus().getName()));
    }

    private static boolean violatesActiveOrderIndex(DataIntegrityViolationException e) {
        return mentions(e.getMessage()) || mentions(e.getMostSpecificCause().getMessage());
    }

    private static boolean mentions(String message) {
        return message != null && message.contains(ACTIVE_ORDER_INDEX);
    }

    private Order requireOrder(Long orderId) {
        // 带价目历史：detail/cancel/submitQuote/pay 的响应拼装要读价目集合，取齐再出会话
        return orderRepository.findWithHistory(orderId)
                .orElseThrow(() -> new ApplicationException(OrderMessage.ORDER_NOT_FOUND));
    }
}
