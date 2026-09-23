package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 工具面清单的槽位分组（#252）：按职能槽位（与技能槽位同键）列当前挂载工具——
 * 平台资产（骨架/增强）＋harness 内建编码工具呈现口径（executor 槽）＋子智能体
 * 声明工具面（subagent 槽）。
 */
public record BackofficeAgentToolSlotResponse(
        @Schema(description = "职能槽位稳定键（与技能槽位同键）：main=主智能体 / "
                + "executor=run 执行体 / subagent=子智能体", example = "main")
        String slot,
        @Schema(description = "槽位展示名", example = "主智能体")
        String slotName,
        @Schema(description = "该槽位工具面（呈现序＝平台资产在前、harness 内建在后）")
        List<BackofficeAgentToolResponse> tools) {
}
