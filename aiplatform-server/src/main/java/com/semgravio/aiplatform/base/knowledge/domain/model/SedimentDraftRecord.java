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
 * <p><b>#641 事后提炼来源五件</b>（ADR-0058）：来源项目名快照、来源版本号、来源
 * 提交哈希、来源原句、提炼批次标识——候选经验从哪个项目、哪一版、哪句用户原话
 * 提炼而来，人审据此回溯（来源项目删除后快照仍可读）；提炼批次标识与处理记录行
 * 互溯。血统三件（项目/run/槽位）保留：提炼行以 slot=refinement、runId=批次标识
 * 填满非空列。</p>
 *
 * @param id                 TSID（后台寻址柄）
 * @param kind               沉淀物类别（CASE/MODULE/RULE，库面开放）
 * @param title              标题
 * @param summary            一句话摘要
 * @param content            正文 markdown
 * @param payload            扩展载荷（kind 开放生长面）
 * @param projectId          血统：来源项目（软引用无 FK）
 * @param runId              血统：来源 run/批次标识
 * @param slot               血统：来源槽位稳定键（main/executor/subagent/refinement）
 * @param inflightSlot       在途序号 1-3（#642 前保留列；新提炼行固定 1）
 * @param scanVerdict        扫描判定（SAFE/CAUTION；DANGEROUS 拒写不落库）
 * @param scanFindings       扫描 findings 留档（审核面）
 * @param status             状态（1 在途/2 已激活/3 已拒绝）
 * @param operatorId         终态审核操作者（#464 写入）
 * @param operatorName       终态审核操作者名（#464 写入）
 * @param reviewedAt         终态时刻（#464 写入）
 * @param rejectReason       拒绝理由（终态必填，库级 CHECK 锁死）
 * @param createdAt          创建时刻
 * @param sourceProjectName  来源项目名快照（#641；项目删除后仍可读）
 * @param sourceVersionNo    来源用户面版本号（#641）
 * @param sourceCommitHash   来源提交哈希（#641）
 * @param sourceSentence     来源用户原句（#641）
 * @param distillBatchId     提炼批次标识（#641；与处理记录互溯）
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
        LocalDateTime createdAt,
        String sourceProjectName,
        Integer sourceVersionNo,
        String sourceCommitHash,
        String sourceSentence,
        String distillBatchId) {

    /**
     * 旧形兼容构造（#460–#638 自荐/审结测试仍按原字段面造行）：来源五件缺省 null。
     * 生产新行一律走全参构造（事后提炼填来源）。
     */
    public SedimentDraftRecord(long id, SedimentKind kind, String title, String summary,
            String content, Map<String, Object> payload, long projectId, String runId,
            String slot, int inflightSlot, String scanVerdict,
            List<Map<String, Object>> scanFindings, SedimentDraftStatus status,
            String operatorId, String operatorName, LocalDateTime reviewedAt,
            String rejectReason, LocalDateTime createdAt) {
        this(id, kind, title, summary, content, payload, projectId, runId, slot,
                inflightSlot, scanVerdict, scanFindings, status, operatorId, operatorName,
                reviewedAt, rejectReason, createdAt, null, null, null, null, null);
    }
}
