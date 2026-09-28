package com.aieducenter.aiplatform.base.skills.domain.model;

import java.util.Optional;
import java.util.regex.Pattern;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;

/**
 * 自荐写入参数（#259）：内容三件（name/description/正文——<b>无 scripts 参数，
 * 自产 a-only 结构性锁死</b>，ADR-0022）＋血统三件（来源项目/run 标识/来源槽位）。
 * 校验正本在 {@link #violation()}——常量与框架 {@code SkillManageTool} 同值对齐
 * （框架常量包私有不可引用，升级框架时两处同步）。
 *
 * @param name        技能名（^[a-z0-9][a-z0-9._-]*$ ≤64）
 * @param description 简介（≤1024）
 * @param content     正文（frontmatter 剥离后的 SKILL.md body 口径，≤100k）
 * @param projectId   来源项目（项目删除清理入口的血统锚）
 * @param runId       来源 run 标识（RuntimeContext 透传的本轮平台标识）
 * @param slot        来源槽位（血统：哪类智能体自荐的）
 */
public record SkillDraftProposal(
        String name,
        String description,
        String content,
        Long projectId,
        String runId,
        SkillSlot slot) {

    /** 与框架 SkillManageTool 常量同值（校验照框架常量，ADR-0022）。 */
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;
    public static final int MAX_CONTENT_CHARS = 100_000;
    public static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

    /**
     * 校验正本（内容三件）：违例返回给模型可读的拒因（可修正重提），合规返回空。
     * 静态入口——血统未定的调用面（工具面先校验）与完整提案（应用服务）共用。
     */
    public static Optional<String> contentViolation(String name, String description,
            String content) {
        if (name == null || !VALID_NAME_PATTERN.matcher(name).matches()
                || name.length() > MAX_NAME_LENGTH) {
            return Optional.of("name 不合规：须匹配 " + VALID_NAME_PATTERN.pattern()
                    + " 且长度 ≤" + MAX_NAME_LENGTH + "（当前："
                    + (name == null || name.isBlank() ? "空" : name) + "）");
        }
        if (description == null || description.isBlank()) {
            return Optional.of("description 不能为空");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            return Optional.of("description 超长：长度 ≤" + MAX_DESCRIPTION_LENGTH
                    + "（当前 " + description.length() + "）");
        }
        if (content == null || content.isBlank()) {
            return Optional.of("正文不能为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return Optional.of("正文超长：长度 ≤" + MAX_CONTENT_CHARS
                    + "（当前 " + content.length() + "）");
        }
        return Optional.empty();
    }

    /** 校验正本的实例入口（本提案内容三件）。 */
    public Optional<String> violation() {
        return contentViolation(name, description, content);
    }
}
