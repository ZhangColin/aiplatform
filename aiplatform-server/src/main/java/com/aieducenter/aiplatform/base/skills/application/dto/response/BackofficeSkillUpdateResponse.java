package com.aieducenter.aiplatform.base.skills.application.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 显式更新回执（#250）：一次「重拉快照入库」的结果确认面——from→to 版本＋操作
 * 者＋更新后该包终态行集＋被移除技能名（确认更新动了什么，对齐卸载回执「确认
 * 移除了什么」的口径）。远端未前进时 from==to、行集即原样（无留痕追加）。
 *
 * @param sourcePackage     来源包标识
 * @param fromVersion       更新前版本（装时/上次更新 commit；远端未前进时与 to 相等）
 * @param toVersion         更新后版本（更新时刻远端 HEAD commit）
 * @param removedSkillNames 本次更新移除的技能名（远端已删、无指派在身的行；无即空）
 * @param operatorId        更新操作者 id（显式动作必留痕）
 * @param operatorName      更新操作者名（直读展示）
 * @param skills            更新后该来源包全部条目（清单行同形，名称序）
 */
public record BackofficeSkillUpdateResponse(
        @Schema(description = "来源包标识（规范化仓库地址）")
        String sourcePackage,
        @Schema(description = "更新前版本（装时/上次更新 commit；远端未前进时与 toVersion 相等）")
        String fromVersion,
        @Schema(description = "更新后版本（更新时刻远端 HEAD commit——快照锚翻新）")
        String toVersion,
        @Schema(description = "本次更新移除的技能名（远端已删且无指派在身的行；有指派在身的"
                + "移除会被整体拒绝 SKL_013，不至此）")
        List<String> removedSkillNames,
        @Schema(description = "更新操作者 id（显式动作必留痕）", example = "700200")
        String operatorId,
        @Schema(description = "更新操作者名（直读展示）", example = "运营·技能管理员")
        String operatorName,
        @Schema(description = "更新后该来源包全部条目（清单行同形；同名行原地翻新保留 id/状态/指派，"
                + "新增行插入、消失行移除）")
        List<BackofficeSkillSummaryResponse> skills) {
}
