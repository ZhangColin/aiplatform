package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 项目详情响应：列表字段全量。
 *
 * @param id          项目标识（TSID 十进制字符串）
 * @param name        项目名
 * @param type        项目类型（code）
 * @param typeName    项目类型名
 * @param engine      智能体栈名（单栈常量）
 * @param workspaceId dev 工作区标识
 * @param status      派生项目状态（code）：IN_PROGRESS / ARCHIVED（归档优先）
 * @param statusName  派生状态名
 * @param archived    是否已归档（单向终点）
 * @param createdAt   创建时间
 */
public record ProjectDetailResponse(
        String id,
        String name,
        ProjectType type,
        String typeName,
        String engine,
        String workspaceId,
        ProjectStatus status,
        String statusName,
        Boolean archived,
        LocalDateTime createdAt
) {
}
