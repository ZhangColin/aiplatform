package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountBriefResponse;
import com.aieducenter.aiplatform.business.order.domain.aggregate.Order;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 后台订单清单条目（#29 交易环②，/api/backoffice/orders）：四维检索的运营工作
 * 清单行（#156 扩）——足够挑选要处理的单（详情/源码包另取），带下单账号摘要
 * 免二次查档（#243 起 externalId 与显示名同批）。
 *
 * @param id               订单标识（TSID 十进制字符串）
 * @param projectId        所属项目标识
 * @param projectName      项目名（软引用缺档为 null）
 * @param ownerExternalId  下单账号对外正身（OIDC sub——账号档案读口的寻址键；
 *                         下单账号可空/跨 BC 软引用缺档为 null）
 * @param ownerDisplayName 下单用户显示名（下单账号可空或缺档为 null）
 * @param status           订单状态（code）
 * @param statusName       状态名
 * @param amount           当前总价（分；待报价 NULL）
 * @param currency         币种（v1 恒 CNY；待报价 NULL）
 * @param createdAt        下单时间
 * @param quotedAt         首次报价时点（改价不刷新；待报价 NULL）
 */
public record BackofficeOrderSummaryResponse(
        String id,
        String projectId,
        String projectName,
        String ownerExternalId,
        String ownerDisplayName,
        OrderStatus status,
        String statusName,
        Long amount,
        String currency,
        LocalDateTime createdAt,
        LocalDateTime quotedAt
) {

    /** 聚合 + 项目名 + 下单账号摘要 → 清单条目（缺档/无主整体 null 呈现）。 */
    public static BackofficeOrderSummaryResponse of(Order order, String projectName,
                                                    AccountBriefResponse owner) {
        return new BackofficeOrderSummaryResponse(
                order.getId().toString(),
                order.getProjectId().toString(),
                projectName,
                owner == null ? null : owner.externalId(),
                owner == null ? null : owner.displayName(),
                order.getStatus(),
                order.getStatus().getName(),
                order.getAmount(),
                order.getCurrency(),
                order.getCreatedAt(),
                order.getQuotedAt());
    }
}
