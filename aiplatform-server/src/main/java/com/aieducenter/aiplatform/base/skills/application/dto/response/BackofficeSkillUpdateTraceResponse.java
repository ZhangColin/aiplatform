package com.aieducenter.aiplatform.base.skills.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.aieducenter.aiplatform.base.skills.domain.model.SkillUpdateTrace;

/**
 * 更新留痕读面行（#250 历史版本可查）：一次显式更新的 from→to 版本＋操作者＋
 * 时刻。append-only——卸载不删留痕（历史事实不随库行消失）；按时间倒序（最近
 * 先），装时版本经链条首个 from 可回溯。
 *
 * @param sourcePackage 来源包标识
 * @param fromVersion   更新前版本
 * @param toVersion     更新后版本
 * @param operatorId    更新操作者 id
 * @param operatorName  更新操作者名（直读展示）
 * @param operatedAt    更新动作时刻
 */
public record BackofficeSkillUpdateTraceResponse(
        @Schema(description = "来源包标识（规范化仓库地址）")
        String sourcePackage,
        @Schema(description = "更新前版本（装时/上次更新 commit）")
        String fromVersion,
        @Schema(description = "更新后版本（更新时刻远端 HEAD commit）")
        String toVersion,
        @Schema(description = "更新操作者 id（显式动作必留痕）", example = "700200")
        String operatorId,
        @Schema(description = "更新操作者名（直读展示）", example = "运营·技能管理员")
        String operatorName,
        @Schema(description = "更新动作时刻（留痕生成时刻）")
        LocalDateTime operatedAt) {

    /** 留痕行 → 读面行。 */
    public static BackofficeSkillUpdateTraceResponse of(SkillUpdateTrace trace) {
        return new BackofficeSkillUpdateTraceResponse(trace.sourcePackage(),
                trace.fromVersion(), trace.toVersion(), trace.operatorId(),
                trace.operatorName(), trace.createdAt());
    }
}
