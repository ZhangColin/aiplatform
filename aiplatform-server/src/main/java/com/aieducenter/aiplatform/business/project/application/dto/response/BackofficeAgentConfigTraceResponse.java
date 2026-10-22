package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.aieducenter.aiplatform.business.project.domain.model.AgentConfigTrace;

/**
 * 配置变更留痕读面行（#251 历史可查）：一次实际变更的前后全量值快照＋操作者＋
 * 时刻，按时间倒序（最近先）。append-only——留痕不随任何动作删除；回滚＝把旧值
 * 快照 PUT 回去（即一次新变更、留新痕，不做版本树）。值面八件（含路由策略四件，
 * #354）全量留痕。
 *
 * @param id                  留痕标识（TSID 十进制串）
 * @param oldSystemPrompt     变更前 systemPrompt（null＝此前即缺省回落）
 * @param oldModelId          变更前模型档位（null 同上）
 * @param oldWebSearchEnabled 变更前联网搜索开关
 * @param oldFetchUrlEnabled  变更前网页抓取开关
 * @param oldRoutingEnabled   变更前模型路由开关
 * @param oldRoutingStrongModelId 变更前强模型档（null＝回落代码缺省）
 * @param oldRoutingFileCountThreshold 变更前文件面阈值（null 同上）
 * @param oldRoutingSliceFeatureThreshold 变更前片复杂度阈值（null 同上）
 * @param newSystemPrompt     变更后 systemPrompt（null＝本次清空回落）
 * @param newModelId          变更后模型档位（null 同上）
 * @param newWebSearchEnabled 变更后联网搜索开关
 * @param newFetchUrlEnabled  变更后网页抓取开关
 * @param newRoutingEnabled   变更后模型路由开关
 * @param newRoutingStrongModelId 变更后强模型档（null＝本次清空回落代码缺省）
 * @param newRoutingFileCountThreshold 变更后文件面阈值（null 同上）
 * @param newRoutingSliceFeatureThreshold 变更后片复杂度阈值（null 同上）
 * @param operatorId          变更操作者 id
 * @param operatorName        变更操作者名（直读展示）
 * @param operatedAt          变更动作时刻（留痕生成时刻）
 */
public record BackofficeAgentConfigTraceResponse(
        @Schema(description = "留痕标识（TSID 十进制串）")
        String id,
        @Schema(description = "变更前 systemPrompt（null＝此前即缺省回落——回滚即把此值写回）")
        String oldSystemPrompt,
        @Schema(description = "变更前模型档位（null＝此前即缺省回落）")
        String oldModelId,
        @Schema(description = "变更前联网搜索开关")
        boolean oldWebSearchEnabled,
        @Schema(description = "变更前网页抓取开关")
        boolean oldFetchUrlEnabled,
        @Schema(description = "变更前模型路由开关（#354）")
        boolean oldRoutingEnabled,
        @Schema(description = "变更前强模型档（null＝此前即回落代码缺省）")
        String oldRoutingStrongModelId,
        @Schema(description = "变更前文件面阈值（null 同上）")
        Integer oldRoutingFileCountThreshold,
        @Schema(description = "变更前片复杂度阈值（null 同上）")
        Integer oldRoutingSliceFeatureThreshold,
        @Schema(description = "变更后 systemPrompt（null＝本次清空回落枚举默认）")
        String newSystemPrompt,
        @Schema(description = "变更后模型档位（null＝本次清空回落）")
        String newModelId,
        @Schema(description = "变更后联网搜索开关")
        boolean newWebSearchEnabled,
        @Schema(description = "变更后网页抓取开关")
        boolean newFetchUrlEnabled,
        @Schema(description = "变更后模型路由开关（#354）")
        boolean newRoutingEnabled,
        @Schema(description = "变更后强模型档（null＝本次清空回落代码缺省）")
        String newRoutingStrongModelId,
        @Schema(description = "变更后文件面阈值（null 同上）")
        Integer newRoutingFileCountThreshold,
        @Schema(description = "变更后片复杂度阈值（null 同上）")
        Integer newRoutingSliceFeatureThreshold,
        @Schema(description = "变更操作者 id", example = "700200")
        String operatorId,
        @Schema(description = "变更操作者名（直读展示）", example = "运营·技能管理员")
        String operatorName,
        @Schema(description = "变更动作时刻（留痕生成时刻）")
        LocalDateTime operatedAt) {

    /** 留痕行 → 读面行（id 十进制串直出——TSID 柄口径同技能库）。 */
    public static BackofficeAgentConfigTraceResponse of(AgentConfigTrace trace) {
        return new BackofficeAgentConfigTraceResponse(Long.toString(trace.id()),
                trace.oldSystemPrompt(), trace.oldModelId(),
                trace.oldWebSearchEnabled(), trace.oldFetchUrlEnabled(),
                trace.oldRoutingEnabled(), trace.oldRoutingStrongModelId(),
                trace.oldRoutingFileCountThreshold(), trace.oldRoutingSliceFeatureThreshold(),
                trace.newSystemPrompt(), trace.newModelId(),
                trace.newWebSearchEnabled(), trace.newFetchUrlEnabled(),
                trace.newRoutingEnabled(), trace.newRoutingStrongModelId(),
                trace.newRoutingFileCountThreshold(), trace.newRoutingSliceFeatureThreshold(),
                trace.operatorId(), trace.operatorName(), trace.createdAt());
    }
}
