package com.aieducenter.aiplatform.business.order.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * 订单用例（#18 落下单缝，报价/改价/取消/支付归片4）：确认下单 = 冻结下单时
 * PRD 全文快照入单，待报价起步。项目事实（存在性/归档态/PRD 正文）经
 * {@code business.project} 应用层软引用——跨 BC 无 FK，同一口径。
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

    private final OrderRepository orderRepository;
    private final ProjectQueryAppService projectQueryAppService;

    public OrderAppService(OrderRepository orderRepository,
                           ProjectQueryAppService projectQueryAppService) {
        this.orderRepository = orderRepository;
        this.projectQueryAppService = projectQueryAppService;
    }

    /**
     * 确认下单：读当前 PRD → 冻结快照入单（待报价）。
     *
     * @throws ApplicationException PRJ_001 项目不存在 / PRJ_015 PRD 未产出
     *                              （项目上下文原样透传）；ORD_004 项目已归档；
     *                              ORD_003 该项目已有未终结订单（预检或并发撞索引）
     */
    public Order place(Long projectId) {
        ProjectDetailResponse project = projectQueryAppService.detail(projectId);
        if (project.status() == ProjectStatus.ARCHIVED) {
            throw new ApplicationException(OrderMessage.ORDER_PROJECT_ARCHIVED);
        }
        if (orderRepository.findActiveByProject(projectId).isPresent()) {
            throw new ApplicationException(OrderMessage.ORDER_ALREADY_ACTIVE);
        }
        Order order = Order.place(projectId, RequestContext.getUserId(),
                projectQueryAppService.prd(projectId).content());
        try {
            return orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            // 并发下单撞部分唯一索引（预检漏过）：后到者拒绝，同口径翻译
            log.info("项目 {} 并发下单撞未终结订单唯一索引，拒绝", projectId);
            throw new ApplicationException(OrderMessage.ORDER_ALREADY_ACTIVE);
        }
    }
}
