package com.aieducenter.aiplatform.base.skills.domain.model;

import java.util.Map;

/**
 * 装时解析态的单个技能（#248 快照安装）：SKILL.md 经 agentscope 同一解析管道
 * （{@code SkillUtil.createFrom}——与内置目录同口径）产出的解析态——frontmatter
 * 全量＋正文，装时固化进 {@code skl_skills} 即审核面所见。身份三件（TSID／
 * 状态／操作者）由安装用例落库时赋（来源包/版本属
 * {@link SkillPackageSnapshot} 快照级事实）。
 *
 * @param name        技能名（frontmatter name；与来源包合成唯一键）
 * @param description 简介（frontmatter description）
 * @param frontmatter SKILL.md frontmatter 全量（解析态键值，含 name/description）
 * @param content     SKILL.md 正文（frontmatter 剥离后全文）
 */
public record ParsedSkill(
        String name,
        String description,
        Map<String, Object> frontmatter,
        String content) {
}
