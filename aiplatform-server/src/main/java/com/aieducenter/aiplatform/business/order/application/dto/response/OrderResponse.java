package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 订单响应（用户面订单详情，#28 交易环①）：下单/详情/取消三端点同构返回。
 * 金额与价目留痕随报价切片（#29）增补，本片只立状态面与下单时点。
 *
 * @param id          订单标识（TSID 十进制字符串）
 * @param projectId   所属项目标识
 * @param status      订单状态（code）：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消
 * @param statusName  状态名
 * @param createdAt   下单时间（快照冻结时点）
 * @param cancelledAt 取消时点（未取消 NULL）
 */
public record OrderResponse(
        String id,
        String projectId,
        OrderStatus status,
        String statusName,
        LocalDateTime createdAt,
        LocalDateTime cancelledAt
) {

    /** 聚合 → 响应（详情/下单/取消共用拼装）。 */
    public static OrderResponse of(Order order) {
        return new OrderResponse(
                order.getId().toString(),
                order.getProjectId().toString(),
                order.getStatus(),
                order.getStatus().getName(),
                order.getCreatedAt(),
                order.getCancelledAt());
    }
}
