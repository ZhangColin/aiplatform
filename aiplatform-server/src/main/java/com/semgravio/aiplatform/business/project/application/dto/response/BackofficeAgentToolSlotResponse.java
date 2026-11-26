package com.semgravio.aiplatform.business.project.application.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 工具面清单的槽位分组（#252）：按职能槽位（与技能槽位同键）列当前挂载工具——
 * 平台资产（骨架/增强）。#638 技能清场后槽位只剩 main／executor（subagent 拆掉），
 * 当前实行集只有 main（主智能体）。
 */
public record BackofficeAgentToolSlotResponse(
        @Schema(description = "职能槽位稳定键（与技能槽位同键）：main=主智能体 / "
                + "executor=run 执行体", example = "main")
        String slot,
        @Schema(description = "槽位展示名", example = "主智能体")
        String slotName,
        @Schema(description = "该槽位工具面（呈现序＝平台资产在前、harness 内建在后）")
        List<BackofficeAgentToolResponse> tools) {
}
