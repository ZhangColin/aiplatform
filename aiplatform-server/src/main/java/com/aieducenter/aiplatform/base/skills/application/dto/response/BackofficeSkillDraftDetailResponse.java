package com.aieducenter.aiplatform.base.skills.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台草稿详情（#259 审核面）：清单行同形字段＋正文全文＋扫描回执全文
 * （findings 逐条：patternId/severity/category/file/line/matchText/description）
 * ＋终态留痕字段（晋升/拒绝后 T2 回填；在途恒 null）。审阅口径见
 * docs/agents/skill-audit-guide.md（血统必查：来源 run 的外部资料接触史）。
 *
 * @param content      技能正文全文（自荐只有正文——a-only，无 scripts）
 * @param scanFindings 扫描 findings 留档（空列表＝无发现）
 * @param operatorId   终态审核操作者 id（在途为 null）
 * @param operatorName 终态审核操作者名（在途为 null）
 * @param reviewedAt   终态时刻（在途为 null）
 * @param rejectReason 拒绝理由（已拒绝态携带；其余 null）
 */
public record BackofficeSkillDraftDetailResponse(
        String id,
        String name,
        String description,
        String slot,
        String projectId,
        String runId,
        String scanVerdict,
        Integer status,
        String statusName,
        LocalDateTime createdAt,
        String content,
        @Schema(description = "扫描 findings 留档：每条 patternId/severity/category/"
                + "file/line/matchText/description（空列表＝无发现）")
        List<Map<String, Object>> scanFindings,
        @Schema(description = "终态审核操作者 id（在途为 null）")
        String operatorId,
        @Schema(description = "终态审核操作者名（在途为 null）")
        String operatorName,
        @Schema(description = "终态时刻（在途为 null）")
        LocalDateTime reviewedAt,
        @Schema(description = "拒绝理由（已拒绝态携带；其余 null）")
        String rejectReason) {

    /** 草稿行 → 详情。 */
    public static BackofficeSkillDraftDetailResponse of(SkillDraftRecord record) {
        return new BackofficeSkillDraftDetailResponse(
                Long.toString(record.id()),
                record.name(),
                record.description(),
                record.slot().key(),
                Long.toString(record.projectId()),
                record.runId(),
                record.scanVerdict(),
                record.status().getCode(),
                record.status().getName(),
                record.createdAt(),
                record.content(),
                record.scanFindings(),
                record.operatorId(),
                record.operatorName(),
                record.reviewedAt(),
                record.rejectReason());
    }
}
