package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountBriefResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 后台项目清单条目（#159 项目域，/api/backoffice/projects）：用户报障定位用的
 * 监管清单行——足够认出项目（详情另取），带归属账号摘要免二次查档（#243 起
 * externalId 与显示名同批）。订单引用不在清单行（#159 口径归详情，与订单域
 * 互链）。
 *
 * @param id               项目标识（TSID 十进制字符串）
 * @param name             项目名
 * @param ownerExternalId  归属账号对外正身（OIDC sub——账号档案读口的寻址键；
 *                         无主/跨 BC 软引用缺档为 null）
 * @param ownerDisplayName 归属账号显示名（无主/缺档为 null）
 * @param type             项目类型（code）
 * @param typeName         项目类型名
 * @param status           派生项目状态（code）：1=进行中 3=已归档（归档优先）
 * @param statusName       派生状态名
 * @param archived         是否已归档（单向终点）
 * @param createdAt        创建时间
 * @param updatedAt        更新时间（审计列）
 */
public record BackofficeProjectSummaryResponse(
        String id,
        String name,
        String ownerExternalId,
        String ownerDisplayName,
        ProjectType type,
        String typeName,
        ProjectStatus status,
        String statusName,
        Boolean archived,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** 聚合 + 归属账号摘要 → 清单条目（缺档/无主整体 null 呈现）。 */
    public static BackofficeProjectSummaryResponse of(Project project, AccountBriefResponse owner) {
        boolean archived = project.getArchivedAt() != null;
        ProjectStatus status = archived ? ProjectStatus.ARCHIVED : ProjectStatus.IN_PROGRESS;
        return new BackofficeProjectSummaryResponse(
                project.getId().toString(),
                project.getName(),
                owner == null ? null : owner.externalId(),
                owner == null ? null : owner.displayName(),
                project.getType(),
                project.getType().getName(),
                status,
                status.getName(),
                archived,
                project.getCreatedAt(),
                project.getUpdatedAt());
    }
}
