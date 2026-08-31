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
     * 谓词与库侧部分唯一索引（uk_ord_orders_active）同口径：非终态各一行满足
     * 唯一索引，本查询即 0/1 行。
     */
    default Optional<Order> findActiveByProject(Long projectId) {
        List<Order> active = findByProjectIdAndStatusNotIn(projectId,
                List.of(OrderStatus.ARCHIVED, OrderStatus.CANCELLED));
        return active.stream().findFirst();
    }

    /** 项目名下非指定状态的订单（未终结 = 不在终态清单，见 {@link #findActiveByProject}）。 */
    List<Order> findByProjectIdAndStatusNotIn(Long projectId, List<OrderStatus> statuses);
}
