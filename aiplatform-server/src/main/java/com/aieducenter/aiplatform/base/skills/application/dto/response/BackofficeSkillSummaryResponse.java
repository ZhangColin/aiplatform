package com.aieducenter.aiplatform.base.skills.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSource;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台技能清单行（#247）：内置与安装/自产同权共形——来源分解出处（内置＝
 * classpath 合成、安装/自产＝库行按 {@code source} 列分），来源包/版本标识/状态
 * 仅库行有值（内置随平台发版：来源包与版本标识 null、状态恒启用；自产行包名/
 * 版本＝固定虚拟值 {@code self}）。
 *
 * @param id            技能柄（详情寻址；两形制——内置 {@code builtin:<技能名>}／
 *                      库行 TSID 十进制串，消费方按 opaque 串回传）
 * @param name          技能名（frontmatter name；库行与来源包合成唯一键）
 * @param description   简介（frontmatter description）
 * @param source        来源 code（1=内置 2=安装 3=自产——#262 起库行两值、
 *                      内置读模型合成）
 * @param sourceName    来源名（直读展示）
 * @param sourcePackage 来源包标识（安装仓库；内置为 null、自产＝固定虚拟值 self）
 * @param version       版本标识（装时 commit；自产＝self；内置为 null）
 * @param status        状态 code（1=启用 2=停用；内置恒 1）
 * @param statusName    状态名（直读展示）
 * @param operatorId    最近管理动作操作者 id（安装＝装者、启停＝最近动作者；
 *                      内置与未管理过为 null——#248 落痕）
 * @param operatorName  最近管理动作操作者名（直读展示；内置与未管理过为 null）
 * @param updateAvailable 远端有新版标记（#250 来源包级）：定期只读检查远端 HEAD
 *                       与装时版本不同即 true——更新永远显式点（POST /update），
 *                       平台不自动跟新；null＝未检查过（内置技能恒 null 不适用）
 * @param loadCount      加载次数（#261 使用计数观测面）：口径＝load 实际发生——
 *                      智能体 load 工具真实加载该技能一次即 +1（指派/启停/清单
 *                      重建不计数）；安装与自产一并覆盖；内置技能恒 null 不适用
 * @param lastLoadedAt   最近加载时刻（#261；null＝从未被加载过——含新装行；
 *                      内置技能恒 null 不适用）
 */
public record BackofficeSkillSummaryResponse(
        @Schema(description = "技能柄（详情寻址，opaque 串两形制，勿做数值假设）：内置技能＝"
                + "builtin:<技能名>，安装技能＝TSID 十进制串",
                example = "builtin:prd-writing")
        String id,
        String name,
        String description,
        @Schema(description = "来源 code（1=内置 2=安装 3=自产）", example = "1")
        Integer source,
        String sourceName,
        @Schema(description = "来源包标识（安装仓库；内置为 null、自产＝固定虚拟值 self）")
        String sourcePackage,
        @Schema(description = "版本标识（装时 commit；自产＝self；内置为 null）")
        String version,
        @Schema(description = "状态 code（1=启用 2=停用；内置恒 1）", example = "1")
        Integer status,
        String statusName,
        @Schema(description = "最近管理动作操作者 id（安装＝装者、启停＝最近动作者；"
                + "内置为 null）", example = "700200")
        String operatorId,
        @Schema(description = "最近管理动作操作者名（直读展示；内置为 null）",
                example = "运营·技能管理员")
        String operatorName,
        @Schema(description = "远端有新版标记（来源包级）：定期只读检查远端 HEAD 与装时版本"
                + "不同即 true——更新永远显式点（POST /update），平台不自动跟新远端；"
                + "null＝未检查过（内置/自产技能恒 null 不适用——无远端）", example = "false")
        Boolean updateAvailable,
        @Schema(description = "加载次数（使用计数观测面）：智能体 load 工具真实加载该技能"
                + "一次即 +1（口径＝load 实际发生，指派/启停/清单重建不计数）；安装与自产"
                + "一并覆盖；0＝从未被加载过；内置技能恒 null 不适用", example = "3")
        Long loadCount,
        @Schema(description = "最近加载时刻（使用计数观测面）；null＝从未被加载过（含新装行）；"
                + "内置技能恒 null 不适用")
        LocalDateTime lastLoadedAt) {

    /** 库条目 → 清单行（来源随行——#262 起安装/自产按 source 列分）。 */
    public static BackofficeSkillSummaryResponse of(SkillRecord record) {
        return new BackofficeSkillSummaryResponse(
                Long.toString(record.id()),
                record.name(),
                record.description(),
                record.source().getCode(),
                record.source().getName(),
                record.sourcePackage(),
                record.version(),
                record.status().getCode(),
                record.status().getName(),
                record.operatorId(),
                record.operatorName(),
                record.updateAvailable(),
                record.loadCount(),
                record.lastLoadedAt());
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
                null,
                null,
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
