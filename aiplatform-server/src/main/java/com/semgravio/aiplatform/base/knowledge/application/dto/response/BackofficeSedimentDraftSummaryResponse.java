package com.semgravio.aiplatform.base.knowledge.application.dto.response;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台标注队列清单行（#464「数据标注」面）：分诊面——类别/标题/摘要＋血统三件
 * （来源项目/run/槽位）＋扫描判定＋在途序号＋状态＋时间；正文与 findings 全文在
 * 详情。id 为 TSID 十进制串（顶层 Long→string 口径，消费方按 opaque 串回传）。
 *
 * @param id           草稿柄（详情寻址，TSID 十进制串）
 * @param kind         沉淀物类别（CASE/MODULE/RULE）
 * @param title        标题
 * @param summary      一句话摘要
 * @param slot         来源槽位稳定键（main/executor/subagent）
 * @param projectId    来源项目 id（string 口径；软引用——项目删除后未终结草稿随清）
 * @param runId        来源 run 标识（血统溯源锚）
 * @param scanVerdict  扫描判定（SAFE/CAUTION——DANGEROUS 拒写不落库）
 * @param inflightSlot 在途序号 1-3（每项目每 kind 在途上限 3 的结构性承载——终态
 *                     翻行即释放名额；队列可见性随面呈现）
 * @param status       状态 code（1=在途 2=已激活 3=已拒绝）
 * @param statusName   状态名（直读展示）
 * @param createdAt    自荐时刻
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
                + "subagent=子智能体）", example = "executor")
        String slot,
        @Schema(description = "来源项目 id（血统——审核溯源与项目删除清理入口）")
        String projectId,
        @Schema(description = "来源 run 标识（血统——run 级溯源锚）")
        String runId,
        @Schema(description = "写入前静态扫描判定（SAFE/CAUTION；DANGEROUS 已在写入口"
                + "拒收不落库）", example = "SAFE")
        String scanVerdict,
        @Schema(description = "在途序号 1-3（每项目每 kind 在途上限 3 的结构性承载）",
                example = "1")
        Integer inflightSlot,
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
                record.scanVerdict(),
                record.inflightSlot(),
                record.status().getCode(),
                record.status().getName(),
                record.createdAt());
    }
}
