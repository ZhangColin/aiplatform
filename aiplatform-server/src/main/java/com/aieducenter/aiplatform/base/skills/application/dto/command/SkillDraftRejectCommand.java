package com.aieducenter.aiplatform.base.skills.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 草稿拒绝命令（#262 T2）：理由必填（终态留档拒绝须有据）。字段合法性由拒绝
 * 用例裁决（SKL_018 空），不在命令层重复校验——照 {@code SkillInstallCommand}
 * 形制。
 *
 * @param reason 拒绝理由（随终态草稿留档，审核面可查；trim 后非空）
 */
public record SkillDraftRejectCommand(
        @Schema(description = "拒绝理由（终态留档可查——拒绝须有据；空白即 400 SKL_018）",
                example = "正文与现有技能 prd-writing 方法论重叠，解消方向不明")
        String reason) {
}
