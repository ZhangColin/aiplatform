package com.aieducenter.aiplatform.base.skills.domain.model;

import java.util.Map;

/**
 * 内置技能读模型（#247）：classpath {@code skills/} 目录合成的技能呈现——与
 * {@link SkillRecord} 库条目同权进后台清单（来源＝内置），但无库行身份：无
 * TSID／来源包／版本标识（随平台发版，版本即平台版本）／无状态迁移（恒启用）。
 * 寻址柄为合成 id（{@code builtin:<技能名>}，见应用层口径）。
 *
 * @param name        技能名（frontmatter name）
 * @param description 简介（frontmatter description）
 * @param frontmatter SKILL.md frontmatter 全量（解析态，含 name/description）
 * @param content     SKILL.md 正文（frontmatter 剥离后全文）
 */
public record BuiltinSkill(
        String name,
        String description,
        Map<String, Object> frontmatter,
        String content) {
}
