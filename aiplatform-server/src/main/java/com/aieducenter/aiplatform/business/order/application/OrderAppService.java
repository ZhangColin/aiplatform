package com.aieducenter.aiplatform.business.order.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;

import lombok.extern.slf4j.Slf4j;

/**
 * 订单写用例（#18 落下单缝；#28 交易环①接出详情/取消，报价/改价/支付随后续
 * 切片）：确认下单 = 冻结下单时 PRD 全文快照入单，待报价起步。项目事实
 * （存在性/归档态/PRD 正文）经 {@code business.project} 应用层软引用——
 * 跨 BC 无 FK，同一口径。
 *
 * <p>「同项目至多一个未终结订单」双保险：预检（{@code findActiveByProject}，
 * 409 ORD_003）+ 库侧部分唯一索引兜底（并发漏过预检时约束拒绝，同译 ORD_003）。
 * 刻意不加 {@code @Transactional}：单行插入由仓储自带事务保证；约束撞错的
 * 吸收（catch 后翻译）要求 save 的事务已独立结束，不处在外层事务中（同
 * MeteringAppService.report 形制）。</p>
 */
@Service
@Slf4j
public class OrderAppService {

    /** 未终结订单唯一索引名（约束撞错的判别键，与 V1__baseline.sql 对齐）。 */
    private static final String ACTIVE_ORDER_INDEX = "uk_ord_orders_active";

    private final OrderRepository orderRepository;
    private final ProjectQueryAppService projectQueryAppService;

    public OrderAppService(OrderRepository orderRepository,
                           ProjectQueryAppService projectQueryAppService) {
        this.orderRepository = orderRepository;
        this.projectQueryAppService = projectQueryAppService;
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
            return OrderResponse.of(orderRepository.save(order));
        } catch (DataIntegrityViolationException e) {
            // 并发下单撞部分唯一索引（预检漏过）：后到者拒绝，同口径翻译；
            // 非本索引的完整性违例不冒名，原样上抛
            if (violatesActiveOrderIndex(e)) {
                log.info("项目 {} 并发下单撞未终结订单唯一索引，拒绝", projectId);
                throw new ApplicationException(OrderMessage.ORDER_ALREADY_ACTIVE);
            }
            throw e;
        }
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
        return OrderResponse.of(orderRepository.save(order));
    }

    /**
     * 提交报价（#29 后台动作，机机面经 BackofficeOrderController 进入）：待报价态
     * 首次调用 = 报价，已报价态重复调用 = 改价——聚合内一次事务同时落价目行
     * （append-only）与订单现值。事务取舍同 {@link #cancel}：单聚合保存（级联
     * 追加价目行）由仓储自带事务保证。
     *
     * @throws ApplicationException ORD_001 订单不存在；ORD_008 金额无效；
     *                              ORD_009 备注超长；ORD_007 已支付或已终结
     */
    public OrderResponse submitQuote(Long orderId, Long amount, String note) {
        Order order = requireOrder(orderId);
        order.quote(amount, note);
        return OrderResponse.of(orderRepository.save(order));
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
