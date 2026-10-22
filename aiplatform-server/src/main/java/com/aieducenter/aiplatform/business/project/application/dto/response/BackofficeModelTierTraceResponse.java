package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.aieducenter.aiplatform.business.project.domain.model.ModelTierTrace;

/**
 * 模型档位变更留痕读面行（#367）：一次实际变更的前后全量值快照＋操作者＋时刻，
 * 倒序最近先；append-only、回滚＝旧值写回即一次新变更。创建痕 old 全 null、
 * 删除痕 new 全 null。
 *
 * @param id             留痕标识（TSID 十进制串）
 * @param oldProviderKey 变更前所属通道（创建痕＝null）
 * @param oldModelName   变更前实际模型名（创建痕＝null）
 * @param oldDisplayName 变更前显示名（null＝此前无显示名）
 * @param oldEnabled     变更前启用态（创建痕＝null）
 * @param newProviderKey 变更后所属通道（删除痕＝null）
 * @param newModelName   变更后实际模型名（删除痕＝null）
 * @param newDisplayName 变更后显示名（null＝本次清空回落语义键）
 * @param newEnabled     变更后启用态（删除痕＝null）
 * @param operatorId     变更操作者 id
 * @param operatorName   变更操作者名（直读展示）
 * @param operatedAt     变更动作时刻
 */
public record BackofficeModelTierTraceResponse(
        @Schema(description = "留痕标识（TSID 十进制串）")
        String id,
        @Schema(description = "变更前所属通道键（null＝创建痕）", example = "volcengine")
        String oldProviderKey,
        @Schema(description = "变更前实际模型名（null＝创建痕）")
        String oldModelName,
        @Schema(description = "变更前显示名（null＝此前无显示名）", example = "快模型档")
        String oldDisplayName,
        @Schema(description = "变更前启用态（null＝创建痕）")
        Boolean oldEnabled,
        @Schema(description = "变更后所属通道键（null＝删除痕）", example = "volcengine")
        String newProviderKey,
        @Schema(description = "变更后实际模型名（null＝删除痕）")
        String newModelName,
        @Schema(description = "变更后显示名（null＝本次清空回落语义键）")
        String newDisplayName,
        @Schema(description = "变更后启用态（null＝删除痕）")
        Boolean newEnabled,
        @Schema(description = "变更操作者 id", example = "700210")
        String operatorId,
        @Schema(description = "变更操作者名（直读展示）", example = "运营·智能体管理员")
        String operatorName,
        @Schema(description = "变更动作时刻（留痕生成时刻）")
        LocalDateTime operatedAt) {

    /** 留痕行 → 读面行。 */
    public static BackofficeModelTierTraceResponse of(ModelTierTrace trace) {
        return new BackofficeModelTierTraceResponse(Long.toString(trace.id()),
                trace.oldProviderKey(), trace.oldModelName(), trace.oldDisplayName(),
                trace.oldEnabled(),
                trace.newProviderKey(), trace.newModelName(), trace.newDisplayName(),
                trace.newEnabled(),
                trace.operatorId(), trace.operatorName(), trace.createdAt());
    }
}
