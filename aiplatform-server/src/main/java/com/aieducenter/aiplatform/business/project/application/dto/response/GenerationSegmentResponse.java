package com.aieducenter.aiplatform.business.project.application.dto.response;

import com.aieducenter.aiplatform.business.project.domain.enums.GenerationSegmentStatus;

/**
 * 生成轨道片行读模型（#225 计划区只读透出）：项目详情嵌入的片清单条目——
 * 序号、描述、片状态（「最近一次尝试的结局」，正本 = 轨道表）。不含在途派生态
 * （轨道表无此态；「当前片」由前端 run-start 切片序号驱动，读模型不猜）。
 *
 * @param ord        片序（0 = 阶段 0 先起服；1..N = 切片计划逐片）
 * @param description 片描述（阶段 0 固定题 / 切片句「用户能 X」）
 * @param status     片状态（code）：PENDING / CLOSED / FAILED
 * @param statusName 片状态名（#186 枚举出口配 *Name，消费端零映射）
 */
public record GenerationSegmentResponse(
        Integer ord,
        String description,
        GenerationSegmentStatus status,
        String statusName) {
}
