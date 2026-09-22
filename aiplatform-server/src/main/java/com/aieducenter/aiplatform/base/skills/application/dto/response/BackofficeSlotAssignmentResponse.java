package com.aieducenter.aiplatform.base.skills.application.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 槽位指派读面（#249）：槽位键＋该槽位当前指派的技能清单行（与技能清单行
 * 共形——含状态列，运营可见「指派了但已停用」的实态；停用行不参与装配合成）。
 * GET 与 PUT 回执同形。
 *
 * @param slot   职能槽位键（main/executor/subagent）
 * @param skills 已指派条目（含停用行；排序同技能清单：来源包、名称）
 */
public record BackofficeSlotAssignmentResponse(
        @Schema(description = "职能槽位键（main=主智能体 / executor=run 执行体 / "
                + "subagent=子智能体）", example = "executor")
        String slot,
        @Schema(description = "已指派技能清单行（与技能清单行共形，含停用行——停用行"
                + "不参与装配合成；跨包同名列靠来源包区分）")
        List<BackofficeSkillSummaryResponse> skills) {

    /** 槽位＋指派条目 → 读面（清单行映射单源复用）。 */
    public static BackofficeSlotAssignmentResponse of(String slot,
            List<BackofficeSkillSummaryResponse> skills) {
        return new BackofficeSlotAssignmentResponse(slot, skills);
    }
}
