package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;
import com.semgravio.aiplatform.business.project.domain.model.ModelProviderTrace;

/**
 * 模型供应商通道变更留痕读面行（#367）：一次实际变更的前后全量值快照＋操作者＋
 * 时刻，倒序最近先。append-only——留痕不随任何动作删除（历史事实跨行存活）；
 * 回滚＝把旧值快照写回（即一次新变更、留新痕）。创建痕 old 全 null、删除痕
 * new 全 null。<b>apiKey 快照读面一律掩码</b>（尾 4 位——留痕不是密钥找回面，
 * 掩码尾 4 足以比对「哪次换的哪把 key」；回滚换 key＝操作者重输全量新值）。
 *
 * @param id            留痕标识（TSID 十进制串）
 * @param oldBaseUrl    变更前 baseUrl（创建痕＝null）
 * @param oldApiKeyMasked 变更前 apiKey 掩码（创建痕/null 键＝null）
 * @param oldEnabled    变更前启用态（创建痕＝null）
 * @param newBaseUrl    变更后 baseUrl（删除痕＝null）
 * @param newApiKeyMasked 变更后 apiKey 掩码（删除痕/null 键＝null）
 * @param newEnabled    变更后启用态（删除痕＝null）
 * @param operatorId    变更操作者 id
 * @param operatorName  变更操作者名（直读展示）
 * @param operatedAt    变更动作时刻
 */
public record BackofficeModelProviderTraceResponse(
        @Schema(description = "留痕标识（TSID 十进制串）")
        String id,
        @Schema(description = "变更前 baseUrl（null＝创建痕——行此前不存在）")
        String oldBaseUrl,
        @Schema(description = "变更前 apiKey 掩码（尾 4 位；null＝此前未配置或创建痕）")
        String oldApiKeyMasked,
        @Schema(description = "变更前启用态（null＝创建痕）")
        Boolean oldEnabled,
        @Schema(description = "变更后 baseUrl（null＝删除痕——行已不存在）")
        String newBaseUrl,
        @Schema(description = "变更后 apiKey 掩码（尾 4 位；null＝删除痕或未配置）")
        String newApiKeyMasked,
        @Schema(description = "变更后启用态（null＝删除痕）")
        Boolean newEnabled,
        @Schema(description = "变更操作者 id", example = "700210")
        String operatorId,
        @Schema(description = "变更操作者名（直读展示）", example = "运营·智能体管理员")
        String operatorName,
        @Schema(description = "变更动作时刻（留痕生成时刻）")
        LocalDateTime operatedAt) {

    /** 留痕行 → 读面行（apiKey 快照经掩码单点——读面零明文）。 */
    public static BackofficeModelProviderTraceResponse of(ModelProviderTrace trace) {
        return new BackofficeModelProviderTraceResponse(Long.toString(trace.id()),
                trace.oldBaseUrl(), ModelProvider.maskApiKey(trace.oldApiKey()),
                trace.oldEnabled(),
                trace.newBaseUrl(), ModelProvider.maskApiKey(trace.newApiKey()),
                trace.newEnabled(),
                trace.operatorId(), trace.operatorName(), trace.createdAt());
    }
}
