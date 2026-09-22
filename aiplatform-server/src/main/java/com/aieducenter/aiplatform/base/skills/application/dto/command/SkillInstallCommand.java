package com.aieducenter.aiplatform.base.skills.application.dto.command;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 安装命令（#248 快照安装）：git 仓库地址＋可选排除目录段。安装即装时固化
 * ——clone 解析全部 SKILL.md 入库，版本＝装时 HEAD commit（ADR-0021）；同源
 * （规范化地址）重复安装被拒（更新走显式更新动作，另票）。字段合法性由安装
 * 用例裁决（SKL_002 地址空、SKL_005 零技能），不在命令层重复校验——照
 * {@code OpenPriceEntryCommand} 形制。
 *
 * @param repoUrl     仓库地址（https/ssh/本地路径，宿主 git 凭据适用）
 * @param excludeDirs 排除目录段名单（可缺省）：技能的仓库相对路径任一段命中
 *                    即不入库（如 deprecated 类目目录）；段名精确匹配
 */
public record SkillInstallCommand(
        @Schema(description = "技能仓库 git 地址（https/ssh/本地路径；clone 走宿主 git "
                + "凭据，装时固化快照、版本＝装时 HEAD commit）",
                example = "https://github.com/mattpocock/skills.git")
        String repoUrl,
        @Schema(description = "排除目录段名单（可缺省）：技能的仓库相对路径任一段命中即"
                + "不入库（如 deprecated 类目目录整支排除；段名精确匹配）",
                example = "[\"deprecated\", \"in-progress\"]")
        List<String> excludeDirs) {
}
