package com.aieducenter.aiplatform.base.skills.application.dto.response;

import java.util.Map;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSource;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台技能详情（#247，审核面）：清单行同形元数据＋技能全文——frontmatter
 * （解析态全量）与正文（frontmatter 剥离后）分面可读，所见即运行时注入面；
 * 运营读全文判安装风险（注入面＋方法论重叠把关，#246 审核口径）。
 *
 * @param id            技能柄（两形制同清单行）
 * @param name          技能名
 * @param description   简介
 * @param source        来源 code（1=内置 2=安装）
 * @param sourceName    来源名（直读展示）
 * @param sourcePackage 来源包标识（内置为 null）
 * @param version       版本标识（装时 commit；内置为 null）
 * @param status        状态 code（1=启用 2=停用；内置恒 1）
 * @param statusName    状态名（直读展示）
 * @param frontmatter   SKILL.md frontmatter 全量（解析态键值，含 name/description）
 * @param content       SKILL.md 正文（frontmatter 剥离后全文）
 * @param operatorId    最近管理动作操作者 id（安装＝装者、启停＝最近动作者；
 *                      内置与未管理过为 null——#248 落痕）
 * @param operatorName  最近管理动作操作者名（直读展示；内置与未管理过为 null）
 */
public record BackofficeSkillDetailResponse(
        @Schema(description = "技能柄（opaque 串两形制，同清单行 id：内置＝builtin:<技能名>、"
                + "安装＝TSID 十进制串）", example = "builtin:prd-writing")
        String id,
        String name,
        String description,
        @Schema(description = "来源 code（1=内置 2=安装）", example = "1")
        Integer source,
        String sourceName,
        String sourcePackage,
        String version,
        Integer status,
        String statusName,
        @Schema(description = "SKILL.md frontmatter 全量（解析态键值——审核面所见即运行时注入面）",
                example = "{\"name\": \"prd-writing\", \"description\": \"撰写或修订 PRD 时使用\"}")
        Map<String, Object> frontmatter,
        @Schema(description = "SKILL.md 正文（frontmatter 剥离后全文，审核承载面）")
        String content,
        @Schema(description = "最近管理动作操作者 id（安装＝装者、启停＝最近动作者；"
                + "内置为 null）", example = "700200")
        String operatorId,
        @Schema(description = "最近管理动作操作者名（直读展示；内置为 null）",
                example = "运营·技能管理员")
        String operatorName) {

    /** 库条目 → 详情（来源＝安装）。 */
    public static BackofficeSkillDetailResponse of(SkillRecord record) {
        return new BackofficeSkillDetailResponse(
                Long.toString(record.id()),
                record.name(),
                record.description(),
                SkillSource.INSTALLED.getCode(),
                SkillSource.INSTALLED.getName(),
                record.sourcePackage(),
                record.version(),
                record.status().getCode(),
                record.status().getName(),
                record.frontmatter(),
                record.content(),
                record.operatorId(),
                record.operatorName());
    }

    /** 内置技能 → 详情（来源＝内置；来源包/版本标识无、状态恒启用）。 */
    public static BackofficeSkillDetailResponse builtin(BuiltinSkill skill) {
        return new BackofficeSkillDetailResponse(
                BackofficeSkillSummaryResponse.builtinId(skill.name()),
                skill.name(),
                skill.description(),
                SkillSource.BUILTIN.getCode(),
                SkillSource.BUILTIN.getName(),
                null,
                null,
                SkillStatus.ENABLED.getCode(),
                SkillStatus.ENABLED.getName(),
                skill.frontmatter(),
                skill.content(),
                null,
                null);
    }
}
