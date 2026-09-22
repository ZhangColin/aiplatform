package com.aieducenter.aiplatform.base.skills.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 显式更新命令（#250，ADR-0021）：按来源包整体重拉快照。地址取清单行
 * {@code sourcePackage} 原值（装时已规范化的仓库地址身份）回传——admin 不自拼
 * 原始 URL，同源身份单源。字段合法性由更新用例裁决（SKL_014 空、SKL_012 未装），
 * 不在命令层重复校验——照 {@code SkillInstallCommand} 形制。
 *
 * @param sourcePackage 来源包标识（清单行 sourcePackage 原值；服务端再规范化）
 */
public record SkillUpdateCommand(
        @Schema(description = "来源包标识（取技能清单行 sourcePackage 原值回传；显式更新"
                + "＝重拉快照入库＋版本留痕，永不自动跟新）",
                example = "https://github.com/mattpocock/skills")
        String sourcePackage) {
}
