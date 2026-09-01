package com.aieducenter.aiplatform.business.order.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.repository.OrderRepository;

/**
 * 订单读面（#28 交易环①）：向 project 上下文供给「未终结订单事实」——项目
 * 详情/列表的嵌入字段（锁定式矩阵与四态过滤的推导输入）与指令区冻结守卫共用。
 * 与 {@link OrderAppService}（写面，依赖 project 读面）分立两 bean，避免
 * project ⇄ order 应用服务互相构造注入成环。
 */
@Service
public class OrderQueryAppService {

    private final OrderRepository orderRepository;

    public OrderQueryAppService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 项目名下的未终结订单（至多一张，谓词同库侧部分唯一索引；无则空）。
     */
    public Optional<OrderBriefResponse> activeOrderOf(Long projectId) {
        return orderRepository.findActiveByProject(projectId)
                .map(OrderBriefResponse::of);
    }

    /**
     * 冻结守卫（#28「下单即冻结迭代」的判定面）：项目挂着未终结订单即抛
     * ORD_006——指令区意见受理（project 上下文）经此守门，错误语义归订单侧
     * 单点，调用方不触订单 domain 词汇。
     */
    public void requireNoActiveOrder(Long projectId) {
        if (orderRepository.findActiveByProject(projectId).isPresent()) {
            throw new ApplicationException(OrderMessage.ORDER_FROZEN);
        }
    }

    /**
     * 项目最近一张订单（任意状态，#30）：归档终态项目页的「完整记录」嵌入面——
     * 支付归档后订单转终态、activeOrder 归空，项目详情改挂本嵌入供订单卡取单。
     */
    public Optional<OrderBriefResponse> latestOrderOf(Long projectId) {
        return orderRepository.findFirstByProjectIdOrderByCreatedAtDescIdDesc(projectId)
                .map(OrderBriefResponse::of);
    }

    /**
     * 一批项目 → 未终结订单摘要（项目列表批量嵌入；每项目至多一张，空批入空映射）。
     */
    public Map<Long, OrderBriefResponse> activeOrdersOf(Collection<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        return orderRepository.findByProjectIdInAndStatusNotIn(projectIds, OrderStatus.TERMINAL)
                .stream()
                .collect(Collectors.toMap(Order::getProjectId, OrderBriefResponse::of,
                        (left, right) -> left)); // 唯一索引保证单行，合并函数仅防御
    }
}
