package com.semgravio.aiplatform.base.knowledge.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台标注详情（#464「数据标注」审核面）：清单行同形字段＋正文全文＋载荷＋扫描
 * 回执全文（findings 逐条：patternId/severity/category/file/line/matchText/
 * description）＋终态留痕字段（激活/拒绝后回填；在途恒 null）。审核口径：激活
 * 原样升格不改稿（升格 knw_materials 归 #465）、拒绝理由必填、改标签只调类别。
 *
 * @param content      正文全文 markdown（激活升格时原样入库——审核者不改稿）
 * @param payload      扩展载荷（一期恒空面；kind 开放生长面）
 * @param scanFindings 扫描 findings 留档（空列表＝无发现）
 * @param operatorId   终态审核操作者 id（在途为 null）
 * @param operatorName 终态审核操作者名（在途为 null）
 * @param reviewedAt   终态时刻（在途为 null）
 * @param rejectReason 拒绝理由（已拒绝态携带；其余 null）
 */
public record BackofficeSedimentDraftDetailResponse(
        String id,
        String kind,
        String title,
        String summary,
        String slot,
        String projectId,
        String runId,
        String scanVerdict,
        Integer inflightSlot,
        Integer status,
        String statusName,
        LocalDateTime createdAt,
        String content,
        @Schema(description = "扩展载荷（一期恒空面；kind 开放＋载荷 JSONB 生长面）")
        Map<String, Object> payload,
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
    public static BackofficeSedimentDraftDetailResponse of(SedimentDraftRecord record) {
        return new BackofficeSedimentDraftDetailResponse(
                Long.toString(record.id()),
                record.kind().key(),
                record.title(),
                record.summary(),
                record.slot(),
                Long.toString(record.projectId()),
                record.runId(),
                record.scanVerdict(),
                record.inflightSlot(),
                record.status().getCode(),
                record.status().getName(),
                record.createdAt(),
                record.content(),
                record.payload(),
                record.scanFindings(),
                record.operatorId(),
                record.operatorName(),
                record.reviewedAt(),
                record.rejectReason());
    }
}
