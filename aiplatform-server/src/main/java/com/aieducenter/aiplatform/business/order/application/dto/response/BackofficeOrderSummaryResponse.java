package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 后台订单清单条目（#29 交易环②，/api/backoffice/orders）：按状态过滤的报价
 * 工作清单行——足够挑选要处理的单（详情/源码包另取）。
 *
 * @param id          订单标识（TSID 十进制字符串）
 * @param projectId   所属项目标识
 * @param projectName 项目名（软引用缺档为 null）
 * @param status      订单状态（code）
 * @param statusName  状态名
 * @param amount      当前总价（分；待报价 NULL）
 * @param currency    币种（v1 恒 CNY；待报价 NULL）
 * @param createdAt   下单时间
 * @param quotedAt    首次报价时点（改价不刷新；待报价 NULL）
 */
public record BackofficeOrderSummaryResponse(
        String id,
        String projectId,
        String projectName,
        OrderStatus status,
        String statusName,
        Long amount,
        String currency,
        LocalDateTime createdAt,
        LocalDateTime quotedAt
) {

    /** 聚合 + 项目名 → 清单条目。 */
    public static BackofficeOrderSummaryResponse of(Order order, String projectName) {
        return new BackofficeOrderSummaryResponse(
                order.getId().toString(),
                order.getProjectId().toString(),
                projectName,
                order.getStatus(),
                order.getStatus().getName(),
                order.getAmount(),
                order.getCurrency(),
                order.getCreatedAt(),
                order.getQuotedAt());
    }
}
