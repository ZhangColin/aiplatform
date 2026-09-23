package com.aieducenter.aiplatform.base.skills.domain.model;

import java.util.Map;

/**
 * 装时解析态的单个技能（#248 快照安装）：SKILL.md 经 agentscope 同一解析管道
 * （{@code SkillUtil.createFrom}——与内置目录同口径）产出的解析态——frontmatter
 * 全量＋正文，装时固化进 {@code skl_skills} 即审核面所见。身份三件（TSID／
 * 状态／操作者）由安装用例落库时赋（来源包/版本属
 * {@link SkillPackageSnapshot} 快照级事实）。
 *
 * <p>scripts/ 资源（#253 安装内容面补课，ADR-0021）：技能目录 {@code scripts/}
 * 子树的文件内容面——键＝技能目录相对路径（{@code scripts/…}），值＝文本内容
 * （非 UTF-8 按框架 {@code base64:} 前缀约定），与框架 {@code AgentSkill.resources}
 * 同形；无 scripts 目录即空 map（纯 Markdown 技能不受影响）。</p>
 *
 * @param name        技能名（frontmatter name；与来源包合成唯一键）
 * @param description 简介（frontmatter description）
 * @param frontmatter SKILL.md frontmatter 全量（解析态键值，含 name/description）
 * @param content     SKILL.md 正文（frontmatter 剥离后全文）
 * @param resources   scripts/ 资源面（技能目录相对路径 → 内容；无 scripts 即空）
 */
public record ParsedSkill(
        String name,
        String description,
        Map<String, Object> frontmatter,
        String content,
        Map<String, String> resources) {
}
