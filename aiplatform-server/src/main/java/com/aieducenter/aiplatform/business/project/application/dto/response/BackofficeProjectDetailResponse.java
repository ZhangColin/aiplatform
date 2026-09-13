package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 后台项目详情（#159 项目域）：清单字段全量＋归属账号显示名＋订单引用——照用户面
 * activeOrder/latestOrder 先例，与订单域互链（activeOrder 有值即冻结迭代；支付
 * 归档后转空、latestOrder 承接「完整记录」取单面）。归档项目全状态照读。
 *
 * @param id               项目标识（TSID 十进制字符串）
 * @param name             项目名
 * @param ownerDisplayName 归属账号显示名（跨 BC 软引用取名；无主/缺档为 null）
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
 */
public record BackofficeProjectDetailResponse(
        String id,
        String name,
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
        OrderBriefResponse latestOrder
) {

    /** 聚合 + 归属账号显示名 + 订单引用 → 详情。 */
    public static BackofficeProjectDetailResponse of(Project project, String ownerDisplayName,
                                                     OrderBriefResponse activeOrder,
                                                     OrderBriefResponse latestOrder) {
        boolean archived = project.getArchivedAt() != null;
        ProjectStatus status = archived ? ProjectStatus.ARCHIVED : ProjectStatus.IN_PROGRESS;
        return new BackofficeProjectDetailResponse(
                project.getId().toString(),
                project.getName(),
                ownerDisplayName,
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
                latestOrder);
    }
}
