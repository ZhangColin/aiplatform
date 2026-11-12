package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;

/**
 * 沉淀草稿读模型（#460，{@code knw_sediment_drafts} 单行）：内容四件＋载荷＋血统
 * 三件＋在途序号＋扫描回执＋状态＋终态四列（#464「数据标注」审结写口，T1 建齐）。
 *
 * @param id            TSID（自荐落库时生成，后台寻址柄）
 * @param kind          沉淀物类别（CASE/MODULE/RULE，库面开放）
 * @param title         标题
 * @param summary       一句话摘要
 * @param content       正文 markdown
 * @param payload       扩展载荷（kind 开放生长面；一期工具不传入，恒空面）
 * @param projectId     血统：来源项目（软引用无 FK）
 * @param runId         血统：来源 run 标识
 * @param slot          血统：来源槽位稳定键（main/executor/subagent）
 * @param inflightSlot  在途序号 1-3（每项目每 kind 在途上限 3 的结构性承载：
 *                      部分唯一索引 (project_id, kind, inflight_slot) WHERE status=1）
 * @param scanVerdict   扫描判定（SAFE/CAUTION；DANGEROUS 拒写不落库）
 * @param scanFindings  扫描 findings 留档（审核面）
 * @param status        状态（1 在途/2 已激活/3 已拒绝）
 * @param operatorId    终态审核操作者（#464 写入）
 * @param operatorName  终态审核操作者名（#464 写入）
 * @param reviewedAt    终态时刻（#464 写入）
 * @param rejectReason  拒绝理由（终态必填，库级 CHECK 锁死）
 * @param createdAt     创建时刻
 */
public record SedimentDraftRecord(
        long id,
        SedimentKind kind,
        String title,
        String summary,
        String content,
        Map<String, Object> payload,
        long projectId,
        String runId,
        String slot,
        int inflightSlot,
        String scanVerdict,
        List<Map<String, Object>> scanFindings,
        SedimentDraftStatus status,
        String operatorId,
        String operatorName,
        LocalDateTime reviewedAt,
        String rejectReason,
        LocalDateTime createdAt) {
}
