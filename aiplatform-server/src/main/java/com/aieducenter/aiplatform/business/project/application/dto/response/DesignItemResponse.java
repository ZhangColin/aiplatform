package com.aieducenter.aiplatform.business.project.application.dto.response;

import com.aieducenter.aiplatform.business.project.domain.enums.DesignItemStatus;

/**
 * 设计轨道件行读模型（#290 计划区只读透出，对偶 {@link GenerationSegmentResponse}）：
 * 项目详情嵌入的设计物清单条目——序号、标题（清单章条目原文）、件状态（「最近一次
 * 尝试的结局」，正本 = 轨道表）。不含在途派生态（「当前件」由前端 run-start 设计物
 * 序号驱动，读模型不猜）。
 *
 * @param ord           件序（1..N = 清单条目序——首产推进序，无阶段 0 对偶物）
 * @param title         件标题（清单章条目首行原文——设计会话的任务锚与重产的对照键）
 * @param status        件状态（code）：PENDING / CLOSED / FAILED / FINALIZED
 * @param statusName    件状态名（#186 枚举出口配 *Name，消费端零映射）
 * @param finalizedPath 定稿选定的稿（#291：工作区锚定形路径，未定稿 = null——
 *                      交付物包与一致性桥的取件锚、画布定稿标记的数据源）
 */
public record DesignItemResponse(
        Integer ord,
        String title,
        DesignItemStatus status,
        String statusName,
        String finalizedPath) {
}
