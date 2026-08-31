package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 后台订单详情（#29 交易环②，/api/backoffice/orders/{id}）：报价依据的全量
 * 事实——PRD 快照正文（下单冻结）、项目名、下单用户昵称、金额与最新备注、
 * 全部状态时点。
 *
 * @param id               订单标识（TSID 十进制字符串）
 * @param projectId        所属项目标识
 * @param projectName      项目名
 * @param ownerDisplayName 下单用户昵称（下单账号可空/缺档为 null）
 * @param status           订单状态（code）
 * @param statusName       状态名
 * @param amount           当前总价（分；待报价 NULL）
 * @param currency         币种（v1 恒 CNY；待报价 NULL）
 * @param note             当前后台备注（最新价目行；待报价 NULL）
 * @param prdSnapshot      下单时 PRD 全文快照（交易标的，只插不改）
 * @param createdAt        下单时间
 * @param quotedAt         首次报价时点（改价不刷新；待报价 NULL）
 * @param paidAt           支付成功时点（未支付 NULL）
 * @param archivedAt       归档时点（未归档 NULL）
 * @param cancelledAt      取消时点（未取消 NULL）
 */
public record BackofficeOrderDetailResponse(
        String id,
        String projectId,
        String projectName,
        String ownerDisplayName,
        OrderStatus status,
        String statusName,
        Long amount,
        String currency,
        String note,
        String prdSnapshot,
        LocalDateTime createdAt,
        LocalDateTime quotedAt,
        LocalDateTime paidAt,
        LocalDateTime archivedAt,
        LocalDateTime cancelledAt
) {

    /** 聚合 + 项目名 + 用户昵称 → 后台详情。 */
    public static BackofficeOrderDetailResponse of(Order order, String projectName,
                                                   String ownerDisplayName) {
        return new BackofficeOrderDetailResponse(
                order.getId().toString(),
                order.getProjectId().toString(),
                projectName,
                ownerDisplayName,
                order.getStatus(),
                order.getStatus().getName(),
                order.getAmount(),
                order.getCurrency(),
                order.currentQuoteNote(),
                order.getPrdSnapshot(),
                order.getCreatedAt(),
                order.getQuotedAt(),
                order.getPaidAt(),
                order.getArchivedAt(),
                order.getCancelledAt());
    }
}
