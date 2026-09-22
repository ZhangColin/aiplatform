package com.aieducenter.aiplatform.base.skills.application.dto.response;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSource;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台技能清单行（#247）：内置与安装同权共形——来源分解出处（内置＝classpath
 * 合成、安装＝库行），来源包/版本标识/状态仅安装行有值（内置随平台发版：来源包
 * 与版本标识 null、状态恒启用）。
 *
 * @param id            技能柄（详情寻址；两形制——内置 {@code builtin:<技能名>}／
 *                      安装 TSID 十进制串，消费方按 opaque 串回传）
 * @param name          技能名（frontmatter name；安装行与来源包合成唯一键）
 * @param description   简介（frontmatter description）
 * @param source        来源 code（1=内置 2=安装）
 * @param sourceName    来源名（直读展示）
 * @param sourcePackage 来源包标识（内置为 null）
 * @param version       版本标识（装时 commit；内置为 null）
 * @param status        状态 code（1=启用 2=停用；内置恒 1）
 * @param statusName    状态名（直读展示）
 * @param operatorId    最近管理动作操作者 id（安装＝装者、启停＝最近动作者；
 *                      内置与未管理过为 null——#248 落痕）
 * @param operatorName  最近管理动作操作者名（直读展示；内置与未管理过为 null）
 */
public record BackofficeSkillSummaryResponse(
        @Schema(description = "技能柄（详情寻址，opaque 串两形制，勿做数值假设）：内置技能＝"
                + "builtin:<技能名>，安装技能＝TSID 十进制串",
                example = "builtin:prd-writing")
        String id,
        String name,
        String description,
        @Schema(description = "来源 code（1=内置 2=安装）", example = "1")
        Integer source,
        String sourceName,
        @Schema(description = "来源包标识（安装仓库；内置为 null）")
        String sourcePackage,
        @Schema(description = "版本标识（装时 commit；内置为 null）")
        String version,
        @Schema(description = "状态 code（1=启用 2=停用；内置恒 1）", example = "1")
        Integer status,
        String statusName,
        @Schema(description = "最近管理动作操作者 id（安装＝装者、启停＝最近动作者；"
                + "内置为 null）", example = "700200")
        String operatorId,
        @Schema(description = "最近管理动作操作者名（直读展示；内置为 null）",
                example = "运营·技能管理员")
        String operatorName) {

    /** 库条目 → 清单行（来源＝安装）。 */
    public static BackofficeSkillSummaryResponse of(SkillRecord record) {
        return new BackofficeSkillSummaryResponse(
                Long.toString(record.id()),
                record.name(),
                record.description(),
                SkillSource.INSTALLED.getCode(),
                SkillSource.INSTALLED.getName(),
                record.sourcePackage(),
                record.version(),
                record.status().getCode(),
                record.status().getName(),
                record.operatorId(),
                record.operatorName());
    }

    /** 内置技能 → 清单行（来源＝内置；来源包/版本标识无、状态恒启用）。 */
    public static BackofficeSkillSummaryResponse builtin(BuiltinSkill skill) {
        return new BackofficeSkillSummaryResponse(
                builtinId(skill.name()),
                skill.name(),
                skill.description(),
                SkillSource.BUILTIN.getCode(),
                SkillSource.BUILTIN.getName(),
                null,
                null,
                SkillStatus.ENABLED.getCode(),
                SkillStatus.ENABLED.getName(),
                null,
                null);
    }

    /** 内置技能合成柄：{@code builtin:<技能名>}。 */
    static String builtinId(String name) {
        return BUILTIN_ID_PREFIX + name;
    }

    /** 内置柄前缀（寻址合成与分解的单源，应用层详情分解引用）。 */
    public static final String BUILTIN_ID_PREFIX = "builtin:";
}
