package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidateTrace;

/**
 * 候选编排留痕读面行（#559/T5）：一次编排（增/删/排序/启停/换指向）中每个实际
 * 变更身份的前后快照＋操作者＋时刻，倒序最近先。快照以候选身份（通道×实际
 * 模型名）为轴——移动/换位在该轴上＝同身份 priority 前后可见；新增痕 old 全
 * null、删除痕 new 全 null。回滚＝按痕旧值写回（重排/重建候选＝一次新编排留
 * 新痕，不做版本树）。append-only 跨行存活（配置删除后留痕仍可读）。
 *
 * @param id             留痕标识（TSID 十进制串）
 * @param oldPriority    变更前队列序（新增痕＝null）
 * @param oldProviderKey 变更前通道键（候选身份腿一）
 * @param oldModelName   变更前实际模型名（候选身份腿二）
 * @param oldEnabled     变更前行级启停（新增痕＝null）
 * @param newPriority    变更后队列序（删除痕＝null）
 * @param newProviderKey 变更后通道键（删除痕＝null）
 * @param newModelName   变更后实际模型名（删除痕＝null）
 * @param newEnabled     变更后行级启停（删除痕＝null）
 * @param operatorId     变更操作者 id
 * @param operatorName   变更操作者名（直读展示）
 * @param operatedAt     变更动作时刻
 */
public record BackofficeModelConfigCandidateTraceResponse(
        @Schema(description = "留痕标识（TSID 十进制串）")
        String id,
        @Schema(description = "变更前队列序（null＝新增痕——候选此前不在队列）")
        Integer oldPriority,
        @Schema(description = "变更前通道键（null＝新增痕）")
        String oldProviderKey,
        @Schema(description = "变更前实际模型名（null＝新增痕）")
        String oldModelName,
        @Schema(description = "变更前行级启停（null＝新增痕）")
        Boolean oldEnabled,
        @Schema(description = "变更后队列序（null＝删除痕——候选已出队列）")
        Integer newPriority,
        @Schema(description = "变更后通道键（null＝删除痕）")
        String newProviderKey,
        @Schema(description = "变更后实际模型名（null＝删除痕）")
        String newModelName,
        @Schema(description = "变更后行级启停（null＝删除痕）")
        Boolean newEnabled,
        @Schema(description = "变更操作者 id", example = "700214")
        String operatorId,
        @Schema(description = "变更操作者名（直读展示）", example = "运营·候选队列管理员")
        String operatorName,
        @Schema(description = "变更动作时刻（留痕生成时刻）")
        LocalDateTime operatedAt) {

    /** 留痕行 → 读面行。 */
    public static BackofficeModelConfigCandidateTraceResponse of(ModelConfigCandidateTrace trace) {
        return new BackofficeModelConfigCandidateTraceResponse(Long.toString(trace.id()),
                trace.oldPriority(), trace.oldProviderKey(), trace.oldModelName(),
                trace.oldEnabled(),
                trace.newPriority(), trace.newProviderKey(), trace.newModelName(),
                trace.newEnabled(),
                trace.operatorId(), trace.operatorName(), trace.createdAt());
    }
}
