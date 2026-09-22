package com.aieducenter.aiplatform.base.skills.domain.model;

import java.util.Map;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;

/**
 * 技能库条目读模型（#247 管理读面）：{@code skl_skills} 一行的呈现——安装时
 * 固化的快照（来源包＋版本标识＝装时 commit），内容面为解析态（frontmatter
 * 全量＋正文），审核面所见即运行时注入面。
 *
 * @param id            条目标识（TSID，安装时生成；管理端点 URL 柄）
 * @param name          技能名（frontmatter name；与来源包合成唯一键）
 * @param description   简介（frontmatter description，清单直读冗余）
 * @param sourcePackage 来源包标识（安装仓库；跨包同名区分键）
 * @param version       版本标识（装时 commit，快照锚）
 * @param status        技能状态（1=启用 2=停用）
 * @param frontmatter   SKILL.md frontmatter 全量（解析态，含 name/description）
 * @param content       SKILL.md 正文（frontmatter 剥离后全文）
 */
public record SkillRecord(
        long id,
        String name,
        String description,
        String sourcePackage,
        String version,
        SkillStatus status,
        Map<String, Object> frontmatter,
        String content) {
}
