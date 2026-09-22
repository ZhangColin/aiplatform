package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountBriefResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 后台项目详情（#159 项目域，#164 补成本指针）：清单字段全量＋归属账号摘要
 * （#243 起 externalId 与显示名同批）＋订单引用——照用户面 activeOrder/
 * latestOrder 先例，与订单域互链（activeOrder 有值即冻结迭代；支付归档后
 * 转空、latestOrder 承接「完整记录」取单面）。归档项目全状态照读。
 *
 * @param id               项目标识（TSID 十进制字符串）
 * @param name             项目名
 * @param ownerExternalId  归属账号对外正身（OIDC sub——账号档案读口的寻址键；
 *                         无主/跨 BC 软引用缺档为 null）
 * @param ownerDisplayName 归属账号显示名（无主/缺档为 null）
 * @param workspaceId      dev 工作区标识（排障时工作区互查的锚点）
 * @param type             项目类型（code）
 * @param typeName         项目类型名
 * @param status           派生项目状态（code）：1=进行中 3=已归档（归档优先）
 * @param statusName       派生状态名
 * @param archived         是否已归档（单向终点）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间（审计列）
 * @param prdProducedAt    PRD 产出时点（NULL = 未产出）
 * @param generatedAt      首次生成时点（NULL = 从未生成）
 * @param activeOrder      未终结订单摘要（无 = null；跨 BC 软引用）
 * @param latestOrder      最近一张订单摘要（任意状态；从未下单 = null）
 * @param costSummary      成本汇总指针（项目全量口径：总成本按币种＋unpriced 有无
 *                        标记；明细下钻走成本域端点——「成本读面归成本域、只留
 *                        指针」的形态落地）
 */
public record BackofficeProjectDetailResponse(
        String id,
        String name,
        String ownerExternalId,
        String ownerDisplayName,
        String workspaceId,
        ProjectType type,
        String typeName,
        ProjectStatus status,
        String statusName,
        Boolean archived,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime prdProducedAt,
        LocalDateTime generatedAt,
        OrderBriefResponse activeOrder,
        OrderBriefResponse latestOrder,
        CostSummary costSummary
) {

    /** 聚合 + 归属账号摘要 + 订单引用 + 成本汇总指针 → 详情（缺档/无主整体 null 呈现）。 */
    public static BackofficeProjectDetailResponse of(Project project, AccountBriefResponse owner,
                                                     OrderBriefResponse activeOrder,
                                                     OrderBriefResponse latestOrder,
                                                     CostSummary costSummary) {
        boolean archived = project.getArchivedAt() != null;
        ProjectStatus status = archived ? ProjectStatus.ARCHIVED : ProjectStatus.IN_PROGRESS;
        return new BackofficeProjectDetailResponse(
                project.getId().toString(),
                project.getName(),
                owner == null ? null : owner.externalId(),
                owner == null ? null : owner.displayName(),
                project.getWorkspaceId().toString(),
                project.getType(),
                project.getType().getName(),
                status,
                status.getName(),
                archived,
                project.getCreatedAt(),
                project.getUpdatedAt(),
                project.getPrdProducedAt(),
                project.getGeneratedAt(),
                activeOrder,
                latestOrder,
                costSummary);
    }

    /**
     * 成本汇总指针（#164）：总成本按币种（token × 事件时点生效单价，分桶直读
     * 不折算、键 = ISO 4217 币种码）＋unpriced 有无标记（有无量但时点无生效价
     * 的分量——true 时成本不完整，明细走成本域下钻端点）。无用量项目＝空 cost
     * ＋false（明确空态）。
     */
    public record CostSummary(
            @Schema(description = "总成本按币种分桶直读不折算：键 = ISO 4217 币种码、值 = 金额；"
                    + "无用量（或全未配价）为空对象",
                    example = "{\"USD\": 12.34}")
            Map<String, BigDecimal> cost,
            boolean unpriced
    ) {

        public CostSummary {
            // 保序拷贝：cost 键序 = 聚合 SQL 的币种码序（API 输出确定性）
            cost = cost == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
        }
    }
}
