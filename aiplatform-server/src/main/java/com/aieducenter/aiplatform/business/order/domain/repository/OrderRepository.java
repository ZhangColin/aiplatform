package com.aieducenter.aiplatform.business.order.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 一批项目名下不在给定状态清单的订单（项目列表嵌入未终结订单事实的批量面）。 */
    List<Order> findByProjectIdInAndStatusNotIn(Collection<Long> projectIds, List<OrderStatus> statuses);

    /** 后台按状态分页拉单（报价工作清单；排序由调用面定死——新单在前）。 */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * 带价目历史的单笔查询：响应拼装（用户面详情/报价返回值要读价目集合）在
     * 仓储事务之外，JOIN FETCH 一次取齐，避免懒加载在会话外炸开。
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.priceEntries WHERE o.id = :id")
    Optional<Order> findWithHistory(@Param("id") Long id);
}
