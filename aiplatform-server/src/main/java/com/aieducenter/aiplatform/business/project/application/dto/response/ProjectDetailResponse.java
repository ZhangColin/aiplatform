package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 项目详情响应：列表字段全量。
 *
 * @param id             项目标识（TSID 十进制字符串）
 * @param name           项目名
 * @param type           项目类型（code）
 * @param typeName       项目类型名
 * @param workspaceId    dev 工作区标识
 * @param status         派生项目状态（code）：IN_PROGRESS / ARCHIVED（归档优先）
 * @param statusName     派生状态名
 * @param archived       是否已归档（单向终点）
 * @param createdAt      创建时间
 * @param updatedAt      更新时间（审计列）
 * @param prdProducedAt  PRD 产出时点（成果区长出判据；NULL = 闲聊期——指令区占满
 *                       全宽、成果区未长）
 * @param generatedAt    首次生成时点（run 成功收口单向置位；NULL = 未生成过——
 *                       「开始做系统」可发起、「确认下单」不可见的推导口径）
 */
public record ProjectDetailResponse(
        String id,
        String name,
        ProjectType type,
        String typeName,
        String workspaceId,
        ProjectStatus status,
        String statusName,
        Boolean archived,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime prdProducedAt,
        LocalDateTime generatedAt
) {
}
