package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 模型档位候选行变更留痕（#559/T5，append-only，照 {@link ModelProviderTrace}
 * 机制）：一次候选编排（增/删/排序/启停/换指向）中每个实际变更身份的前后快照＋
 * 操作者。候选行无稳定行键（PK＝tier_key+priority，排序即键变）——快照以候选
 * 身份（通道×实际模型名，队列内唯一）为轴：新增痕 old 全 null、删除痕 new 全
 * null、移动/换位/启停＝同身份 priority/enabled 前后可见。
 *
 * <p>回滚＝旧值写回即一次新变更留新痕（不做版本树）——候选编排的回滚面是整
 * 队列：按痕旧值重排/重建候选。留痕跨行存活（无 FK——档位/通道删除后历史仍
 * 可读，V19 口径）。</p>
 *
 * @param id              留痕标识（TSID，写痕用例生成）
 * @param tierKey         档位语义键（无 FK——留痕跨一切存活）
 * @param oldPriority     变更前队列序（新增痕＝null）
 * @param oldProviderKey  变更前通道键（候选身份腿一）
 * @param oldModelName    变更前实际模型名（候选身份腿二）
 * @param oldEnabled      变更前行级启停（新增痕＝null）
 * @param newPriority     变更后队列序（删除痕＝null）
 * @param newProviderKey  变更后通道键（删除痕＝null）
 * @param newModelName    变更后实际模型名（删除痕＝null）
 * @param newEnabled      变更后行级启停（删除痕＝null）
 * @param operatorId      变更操作者（写操作必留痕）
 * @param operatorName    变更操作者名（直读展示）
 * @param createdAt       留痕时刻（＝变更动作时刻）
 */
public record ModelTierCandidateTrace(
        long id,
        String tierKey,
        Integer oldPriority,
        String oldProviderKey,
        String oldModelName,
        Boolean oldEnabled,
        Integer newPriority,
        String newProviderKey,
        String newModelName,
        Boolean newEnabled,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt) {
}
