package com.aieducenter.aiplatform.business.order.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 订单仓储。
 */
public interface OrderRepository extends BaseRepository<Order, Long> {

    /**
     * 项目名下的未终结订单（「同项目至多一个未终结订单」的应用预检面）。
     * 谓词 = {@link OrderStatus#TERMINAL} 取反，与库侧部分唯一索引
     * （uk_ord_orders_active）同口径：非终态至多一行，本查询即 0/1 行。
     */
    default Optional<Order> findActiveByProject(Long projectId) {
        return findByProjectIdAndStatusNotIn(projectId, OrderStatus.TERMINAL)
                .stream().findFirst();
    }

    /** 项目名下不在给定状态清单的订单。 */
    List<Order> findByProjectIdAndStatusNotIn(Long projectId, List<OrderStatus> statuses);
}
