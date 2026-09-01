package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 订单响应（用户面订单详情，#28 交易环① + #29 交易环②金额面 + #30 交易环③
 * 归档终态时间点组）：下单/详情/取消/报价/支付共用同构。金额与改价历史随报价落
 * （待报价态 amount 为 null、价目为空表）；支付/归档时点随支付落（#30——已支付
 * 为事务内瞬态，paidAt 与 archivedAt 同拍）。
 *
 * @param id           订单标识（TSID 十进制字符串）
 * @param projectId    所属项目标识
 * @param status       订单状态（code）：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消
 * @param statusName   状态名
 * @param amount       当前总价（分；待报价 NULL，改价取最新价目行）
 * @param currency     币种（v1 恒 CNY；待报价 NULL）
 * @param note         当前后台备注（最新价目行 note；待报价 NULL）
 * @param quotedAt     首次报价时点（改价不刷新；待报价 NULL）
 * @param priceEntries 改价历史（新 → 旧：时间 + 金额 + 备注，只追加）
 * @param createdAt    下单时间（快照冻结时点）
 * @param cancelledAt  取消时点（未取消 NULL）
 * @param paidAt       支付成功时点（未支付 NULL；与 archivedAt 同拍）
 * @param archivedAt   归档时点（未归档 NULL；归档终态「完整记录」的一环）
 */
public record OrderResponse(
        String id,
        String projectId,
        OrderStatus status,
        String statusName,
        Long amount,
        String currency,
        String note,
        LocalDateTime quotedAt,
        List<PriceEntryResponse> priceEntries,
        LocalDateTime createdAt,
        LocalDateTime cancelledAt,
        LocalDateTime paidAt,
        LocalDateTime archivedAt
) {

    /** 聚合 → 响应（下单/详情/取消/报价/支付共用拼装）。 */
    public static OrderResponse of(Order order) {
        return new OrderResponse(
                order.getId().toString(),
                order.getProjectId().toString(),
                order.getStatus(),
                order.getStatus().getName(),
                order.getAmount(),
                order.getCurrency(),
                order.currentQuoteNote(),
                order.getQuotedAt(),
                order.priceHistoryNewestFirst().stream()
                        .map(PriceEntryResponse::of)
                        .toList(),
                order.getCreatedAt(),
                order.getCancelledAt(),
                order.getPaidAt(),
                order.getArchivedAt());
    }
}
