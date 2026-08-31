package com.aieducenter.aiplatform.business.order.application.dto.response;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 未终结订单摘要（项目详情/列表嵌入，#28 交易环①）：项目面知道「挂着一张
 * 未终结订单、在哪个态」即可——锁定式矩阵与四态列表的推导输入；订单本体
 * （时间戳组/金额/留痕）经订单详情端点取。
 *
 * @param id         订单标识（TSID 十进制字符串）
 * @param status     订单状态（code）：1=待报价 2=已报价（=待支付）；已支付/终态
 *                   不会作为未终结订单出现
 * @param statusName 状态名
 */
public record OrderBriefResponse(
        String id,
        OrderStatus status,
        String statusName
) {

    /** 聚合 → 摘要。 */
    public static OrderBriefResponse of(Order order) {
        return new OrderBriefResponse(
                order.getId().toString(),
                order.getStatus(),
                order.getStatus().getName());
    }
}
