package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountBriefResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 后台订单详情（#29 交易环②，/api/backoffice/orders/{id}）：报价依据的全量
 * 事实——PRD 快照正文（下单冻结）、项目名、下单账号摘要（#243 起 externalId
 * 与昵称同批）、金额与最新备注、价目历史（#155 append-only 全量，新 → 旧）、
 * 全部状态时点。
 *
 * @param id               订单标识（TSID 十进制字符串）
 * @param projectId        所属项目标识
 * @param projectName      项目名
 * @param ownerExternalId  下单账号对外正身（OIDC sub——账号档案读口的寻址键；
 *                         下单账号可空/跨 BC 软引用缺档为 null）
 * @param ownerDisplayName 下单用户昵称（下单账号可空/缺档为 null）
 * @param status           订单状态（code）
 * @param statusName       状态名
 * @param amount           当前总价（分；待报价 NULL）
 * @param currency         币种（v1 恒 CNY；待报价 NULL）
 * @param note             当前后台备注（最新价目行；待报价 NULL）
 * @param priceEntries     价目历史（新 → 旧，append-only 全量；带操作者，存量行
 *                         操作者为空；待报价空表）
 * @param prdSnapshot      下单时 PRD 全文快照（交易标的，只插不改）
 * @param createdAt        下单时间
 * @param quotedAt         首次报价时点（改价不刷新；待报价 NULL）
 * @param paidAt           支付成功时点（未支付 NULL）
 * @param archivedAt       归档时点（未归档 NULL）
 * @param archiveOperatorId   重试归档操作者 id（#158，admin 侧管理员 TSID——
 *                            支付链自动归档/缺透传头为 null）
 * @param archiveOperatorName 重试归档操作者名（直读；口径同取消留痕两列）
 * @param cancelledAt      取消时点（未取消 NULL）
 * @param cancelReason       取消原因（#157 运营取消必填留痕，运营内部口径——
 *                           用户面读面不携带；用户取消/未取消为 null）
 * @param cancelOperatorId   取消操作者 id（admin 侧管理员 TSID；用户取消/缺透传
 *                           头为 null）
 * @param cancelOperatorName 取消操作者名（直读；口径同价目行 operator）
 */
public record BackofficeOrderDetailResponse(
        String id,
        String projectId,
        String projectName,
        String ownerExternalId,
        String ownerDisplayName,
        OrderStatus status,
        String statusName,
        Long amount,
        String currency,
        String note,
        List<BackofficePriceEntryResponse> priceEntries,
        String prdSnapshot,
        LocalDateTime createdAt,
        LocalDateTime quotedAt,
        LocalDateTime paidAt,
        LocalDateTime archivedAt,
        String archiveOperatorId,
        String archiveOperatorName,
        LocalDateTime cancelledAt,
        String cancelReason,
        String cancelOperatorId,
        String cancelOperatorName
) {

    /** 聚合 + 项目名 + 下单账号摘要 → 后台详情（缺档/无主整体 null 呈现）。 */
    public static BackofficeOrderDetailResponse of(Order order, String projectName,
                                                   AccountBriefResponse owner) {
        return new BackofficeOrderDetailResponse(
                order.getId().toString(),
                order.getProjectId().toString(),
                projectName,
                owner == null ? null : owner.externalId(),
                owner == null ? null : owner.displayName(),
                order.getStatus(),
                order.getStatus().getName(),
                order.getAmount(),
                order.getCurrency(),
                order.currentQuoteNote(),
                order.priceHistoryNewestFirst().stream()
                        .map(BackofficePriceEntryResponse::of)
                        .toList(),
                order.getPrdSnapshot(),
                order.getCreatedAt(),
                order.getQuotedAt(),
                order.getPaidAt(),
                order.getArchivedAt(),
                order.getArchiveOperatorId(),
                order.getArchiveOperatorName(),
                order.getCancelledAt(),
                order.getCancelReason(),
                order.getCancelOperatorId(),
                order.getCancelOperatorName());
    }
}
