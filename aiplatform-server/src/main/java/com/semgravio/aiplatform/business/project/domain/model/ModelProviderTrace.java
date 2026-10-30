package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 模型供应商通道变更留痕（#367，append-only，照 {@code AgentConfigTrace} 机制）：
 * 一次实际变更（值面有动）的前后全量值快照＋操作者。旧值快照＝回滚依据——回滚＝
 * 把旧值写回（即一次新变更、留新痕，不做版本树）。与配置面留痕的差异：通道行有
 * 创建与删除生命周期，快照两列组可空——创建痕 old 全 null（行此前不存在）、删除
 * 痕 new 全 null（行已不存在）。apiKey 快照明文落库（与行本体同保护级，ADR-0031
 * 明文落库决定），但<b>读面一律掩码回显</b>（{@link ModelProvider#maskApiKey}
 * 单点）——回显面零明文 key。
 *
 * @param id           留痕标识（TSID，写痕用例生成）
 * @param providerKey  通道键（无 FK——留痕跨行存活）
 * @param oldBaseUrl   变更前 baseUrl（创建痕＝null）
 * @param oldApiKey    变更前 apiKey 明文（库内；读面掩码）
 * @param oldEnabled   变更前启用态（创建痕＝null）
 * @param newBaseUrl   变更后 baseUrl（删除痕＝null）
 * @param newApiKey    变更后 apiKey 明文（库内；读面掩码）
 * @param newEnabled   变更后启用态（删除痕＝null）
 * @param operatorId   变更操作者（写操作必留痕）
 * @param operatorName 变更操作者名（直读展示）
 * @param createdAt    留痕时刻（＝变更动作时刻）
 */
public record ModelProviderTrace(
        long id,
        String providerKey,
        String oldBaseUrl,
        String oldApiKey,
        Boolean oldEnabled,
        String newBaseUrl,
        String newApiKey,
        Boolean newEnabled,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt) {
}
