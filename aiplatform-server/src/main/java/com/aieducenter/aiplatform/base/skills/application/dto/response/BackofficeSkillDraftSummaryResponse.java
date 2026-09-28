package com.aieducenter.aiplatform.base.skills.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftRecord;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台草稿清单行（#259）：审核分诊面——血统三件（来源项目/run/槽位）＋扫描
 * 判定＋状态＋时间；正文与 findings 全文在详情。id 为 TSID 十进制串（顶层
 * Long→string 口径，消费方按 opaque 串回传）。
 *
 * @param id          草稿柄（详情寻址，TSID 十进制串）
 * @param name        技能名（撞名判断键）
 * @param description 简介
 * @param slot        来源槽位稳定键（main/executor/subagent）
 * @param projectId   来源项目 id（string 口径；软引用——项目删除后未终结草稿随清）
 * @param runId       来源 run 标识（血统溯源锚：关联该 run 的事件流与外部资料
 *                    接触史——审核必查项见 docs/agents/skill-audit-guide.md）
 * @param scanVerdict 扫描判定（SAFE/CAUTION——DANGEROUS 拒写不落库）
 * @param status      状态 code（1=在途 2=已晋升 3=已拒绝；列表活跃面恒 1）
 * @param statusName  状态名（直读展示）
 * @param createdAt   自荐时刻
 */
public record BackofficeSkillDraftSummaryResponse(
        @Schema(description = "草稿柄（详情寻址，TSID 十进制串，勿做数值假设）")
        String id,
        String name,
        String description,
        @Schema(description = "来源槽位稳定键（main=主智能体 executor=run 执行体 "
                + "subagent=子智能体）", example = "executor")
        String slot,
        @Schema(description = "来源项目 id（血统——审核溯源与项目删除清理入口）")
        String projectId,
        @Schema(description = "来源 run 标识（血统——外部资料接触史等 run 级溯源锚）")
        String runId,
        @Schema(description = "写入前静态扫描判定（SAFE/CAUTION；DANGEROUS 已在写入"
                + "口拒收不落库）", example = "SAFE")
        String scanVerdict,
        @Schema(description = "状态 code（1=在途 2=已晋升 3=已拒绝；列表＝活跃面恒 1）",
                example = "1")
        Integer status,
        String statusName,
        LocalDateTime createdAt) {

    /** 草稿行 → 清单行。 */
    public static BackofficeSkillDraftSummaryResponse of(SkillDraftRecord record) {
        return new BackofficeSkillDraftSummaryResponse(
                Long.toString(record.id()),
                record.name(),
                record.description(),
                record.slot().key(),
                Long.toString(record.projectId()),
                record.runId(),
                record.scanVerdict(),
                record.status().getCode(),
                record.status().getName(),
                record.createdAt());
    }
}
