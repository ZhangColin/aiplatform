package com.aieducenter.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 智能体配置变更留痕（#251，append-only）：一次实际变更（值面有动）的前后全量
 * 值快照＋操作者。旧值快照＝回滚依据——回滚＝把旧值写回（即一次新变更、留新痕，
 * 不做版本树，ADR-0021）。值面四件（systemPrompt、modelId、两工具开关）全量
 * 留痕：每痕都是完整状态对，与 #252 工具开关变更共表共机制。
 *
 * @param id                  留痕标识（TSID，写痕用例生成）
 * @param agentKey            智能体稳定键（无 FK——留痕跨配置行存活）
 * @param oldSystemPrompt     变更前 systemPrompt（null＝此前即缺省回落）
 * @param oldModelId          变更前模型档位（null 同上）
 * @param oldWebSearchEnabled 变更前联网搜索开关
 * @param oldFetchUrlEnabled  变更前网页抓取开关
 * @param newSystemPrompt     变更后 systemPrompt（null＝本次清空回落）
 * @param newModelId          变更后模型档位（null 同上）
 * @param newWebSearchEnabled 变更后联网搜索开关
 * @param newFetchUrlEnabled  变更后网页抓取开关
 * @param operatorId          变更操作者 id（写操作必留痕）
 * @param operatorName        变更操作者名（直读展示）
 * @param createdAt           留痕时刻（＝变更动作时刻）
 */
public record AgentConfigTrace(
        long id,
        String agentKey,
        String oldSystemPrompt,
        String oldModelId,
        boolean oldWebSearchEnabled,
        boolean oldFetchUrlEnabled,
        String newSystemPrompt,
        String newModelId,
        boolean newWebSearchEnabled,
        boolean newFetchUrlEnabled,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt) {
}
