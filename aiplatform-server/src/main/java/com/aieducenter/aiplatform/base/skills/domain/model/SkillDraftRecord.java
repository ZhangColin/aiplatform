package com.aieducenter.aiplatform.base.skills.domain.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillDraftStatus;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;

/**
 * 技能草稿读模型（#259，{@code skl_skill_drafts} 行）：内容三件＋血统三件＋扫描
 * 回执（verdict 字符串照 {@code SkillSecurityScanner.Verdict} 枚举名；findings 是
 * scanner Finding 的解析态键值，JSONB 通行——与 frontmatter 同先例不建强类型）＋
 * 状态与终态留痕字段（晋升/拒绝的审核操作者与时刻、拒绝理由——T2 写入、T1 建齐
 * 呈现为空）。草稿<b>不参与任何装配</b>（结构性：装配视图查询不触本表）。
 *
 * @param id           TSID（自荐落库时生成，管理端点 URL 柄）
 * @param scanVerdict  扫描判定（SAFE/CAUTION——DANGEROUS 拒写不落库，见应用服务）
 * @param scanFindings 扫描 findings（键：patternId/severity/category/file/line/
 *                     matchText/description；随草稿留档供审核面）
 * @param createdAt    落库时刻（插入时忽略、读回有值——库列 DEFAULT）
 */
public record SkillDraftRecord(
        long id,
        String name,
        String description,
        String content,
        long projectId,
        String runId,
        SkillSlot slot,
        String scanVerdict,
        List<Map<String, Object>> scanFindings,
        SkillDraftStatus status,
        String operatorId,
        String operatorName,
        LocalDateTime reviewedAt,
        String rejectReason,
        LocalDateTime createdAt) {
}
