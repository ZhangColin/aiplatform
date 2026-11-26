package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidate;

/**
 * 候选队列读面行（#559/T5）：GET 清单与增删/排序/启停回执同形（回执＝整队列，
 * 编排结果一次可见）。priority 升序＝队列序（主备＝前两名）；行级启停独立于
 * 配置行 enabled（配置停用＝整队不参装配，候选停用＝单候选读面剔除）。
 *
 * @param priority     队列序（小者先；档内唯一）
 * @param providerKey  通道键（一行＝endpoint×账号 key）
 * @param modelName    实际模型名（计量/单价行匹配腿）
 * @param enabled      行级启停；false＝读面剔除（队列不参队）
 * @param operatorId   最近写者 id（null＝种子/迁移写入）
 * @param operatorName 最近写者名（直读展示）
 * @param updatedAt    最近写入时刻
 */
public record BackofficeModelConfigCandidateResponse(
        @Schema(description = "队列序（小者先；主备＝队列前两名）", example = "2")
        int priority,
        @Schema(description = "通道键（切换单元＝故障物理单元：endpoint×账号 key）",
                example = "deepseek-2")
        String providerKey,
        @Schema(description = "实际模型名（计量/单价行匹配腿）", example = "deepseek-flash")
        String modelName,
        @Schema(description = "行级启停（false＝读面剔除不参队；与配置行启停正交）")
        boolean enabled,
        @Schema(description = "最近写者 id（null＝种子/迁移写入）", example = "700214")
        String operatorId,
        @Schema(description = "最近写者名（直读展示）", example = "运营·候选队列管理员")
        String operatorName,
        @Schema(description = "最近写入时刻")
        LocalDateTime updatedAt) {

    /** 行 → 读面。 */
    public static BackofficeModelConfigCandidateResponse of(ModelConfigCandidate candidate) {
        return new BackofficeModelConfigCandidateResponse(candidate.priority(),
                candidate.providerKey(), candidate.modelName(), candidate.enabled(),
                candidate.operatorId(), candidate.operatorName(), candidate.updatedAt());
    }
}
