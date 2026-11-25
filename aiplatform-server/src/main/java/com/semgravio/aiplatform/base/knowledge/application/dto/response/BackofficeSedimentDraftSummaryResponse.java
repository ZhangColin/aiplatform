package com.semgravio.aiplatform.base.knowledge.application.dto.response;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台标注队列清单行（#464「数据标注」面）：分诊面——类别/标题/摘要＋来源回溯
 * （来源项目标识＋项目名快照/版本号/提交哈希/原句）＋血统三件（项目/run/槽位）＋
 * 扫描判定＋状态＋时间；正文与 findings 全文在详情。id 为 TSID 十进制串（顶层
 * Long→string 口径，消费方按 opaque 串回传）。#642 起不再有在途序号。
 *
 * @param id                草稿柄（详情寻址，TSID 十进制串）
 * @param kind              沉淀物类别（CASE/MODULE/RULE）
 * @param title             标题
 * @param summary           一句话摘要
 * @param slot              来源槽位稳定键（main/executor/refinement）
 * @param projectId         来源项目 id（string 口径；软引用——项目删除后未审草稿随清）
 * @param runId             来源 run/批次标识（血统溯源锚）
 * @param sourceProjectName 来源项目名快照（项目删除后仍可读）
 * @param sourceVersionNo   来源用户面版本号
 * @param sourceCommitHash  来源提交哈希
 * @param sourceSentence    来源用户原句
 * @param scanVerdict       扫描判定（SAFE/CAUTION——DANGEROUS 拒写不落库）
 * @param status            状态 code（1=在途 2=已激活 3=已拒绝）
 * @param statusName        状态名（直读展示）
 * @param createdAt         自荐时刻
 */
public record BackofficeSedimentDraftSummaryResponse(
        @Schema(description = "草稿柄（详情寻址，TSID 十进制串，勿做数值假设）")
        String id,
        @Schema(description = "沉淀物类别（CASE=需求案例 MODULE=功能模块 RULE=业务规则）",
                example = "CASE")
        String kind,
        String title,
        String summary,
        @Schema(description = "来源槽位稳定键（main=主智能体 executor=run 执行体 "
                + "refinement=候选经验提炼）", example = "refinement")
        String slot,
        @Schema(description = "来源项目 id（血统——审核溯源与项目删除清理入口）")
        String projectId,
        @Schema(description = "来源 run/批次标识（血统——批次级溯源锚）")
        String runId,
        @Schema(description = "来源项目名快照（项目改名/删除后仍可读）", example = "宠物店官网")
        String sourceProjectName,
        @Schema(description = "来源用户面版本号", example = "2")
        Integer sourceVersionNo,
        @Schema(description = "来源提交哈希")
        String sourceCommitHash,
        @Schema(description = "来源用户原句（从该版本修改意见中提炼）")
        String sourceSentence,
        @Schema(description = "写入前静态扫描判定（SAFE/CAUTION；DANGEROUS 已在写入口"
                + "拒收不落库）", example = "SAFE")
        String scanVerdict,
        @Schema(description = "状态 code（1=在途 2=已激活 3=已拒绝）", example = "1")
        Integer status,
        String statusName,
        LocalDateTime createdAt) {

    /** 草稿行 → 清单行。 */
    public static BackofficeSedimentDraftSummaryResponse of(SedimentDraftRecord record) {
        return new BackofficeSedimentDraftSummaryResponse(
                Long.toString(record.id()),
                record.kind().key(),
                record.title(),
                record.summary(),
                record.slot(),
                Long.toString(record.projectId()),
                record.runId(),
                record.sourceProjectName(),
                record.sourceVersionNo(),
                record.sourceCommitHash(),
                record.sourceSentence(),
                record.scanVerdict(),
                record.status().getCode(),
                record.status().getName(),
                record.createdAt());
    }
}
