package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 模型档位变更留痕（#367，append-only，照 {@code AgentConfigTrace} 机制）：
 * 一次实际变更（值面有动）的前后全量值快照＋操作者，回滚＝旧值写回即一次新变更。
 * 创建痕 old 全 null、删除痕 new 全 null（生命周期口径同 {@link ModelProviderTrace}）。
 *
 * @param id              留痕标识（TSID，写痕用例生成）
 * @param tierKey         档位语义键（无 FK——留痕跨行存活）
 * @param oldProviderKey  变更前所属通道（创建痕＝null）
 * @param oldModelName    变更前实际模型名（创建痕＝null）
 * @param oldDisplayName  变更前显示名（null＝无显示名）
 * @param oldEnabled      变更前启用态（创建痕＝null）
 * @param newProviderKey  变更后所属通道（删除痕＝null）
 * @param newModelName    变更后实际模型名（删除痕＝null）
 * @param newDisplayName  变更后显示名（null＝无显示名）
 * @param newEnabled      变更后启用态（删除痕＝null）
 * @param operatorId      变更操作者（写操作必留痕）
 * @param operatorName    变更操作者名（直读展示）
 * @param createdAt       留痕时刻（＝变更动作时刻）
 */
public record ModelTierTrace(
        long id,
        String tierKey,
        String oldProviderKey,
        String oldModelName,
        String oldDisplayName,
        Boolean oldEnabled,
        String newProviderKey,
        String newModelName,
        String newDisplayName,
        Boolean newEnabled,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt) {
}
