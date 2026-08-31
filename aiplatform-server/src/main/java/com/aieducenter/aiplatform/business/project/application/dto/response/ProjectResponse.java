package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 项目响应（列表/详情共用）。
 *
 * @param id          项目标识（TSID 十进制字符串）
 * @param name        项目名
 * @param type        项目类型（code）
 * @param typeName    项目类型名
 * @param workspaceId dev 工作区标识（exec/会话寻址锚点）
 * @param status      派生项目状态（code）：IN_PROGRESS / ARCHIVED（归档优先）
 * @param statusName  派生状态名
 * @param archived    是否已归档（单向终点）
 * @param createdAt   创建时间
 * @param updatedAt   更新时间（列表卡「更新于」与首页「最近项目」排序的事实源）
 * @param activeOrder 未终结订单摘要（#28：无 = null——锁定式矩阵与列表
 *                   「待报价/待支付」态的推导输入；跨 BC 软引用）
 */
public record ProjectResponse(
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
        OrderBriefResponse activeOrder
) {
}
