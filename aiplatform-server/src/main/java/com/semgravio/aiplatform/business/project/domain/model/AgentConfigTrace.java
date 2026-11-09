package com.semgravio.aiplatform.business.project.domain.model;

import java.time.LocalDateTime;

/**
 * 智能体配置变更留痕（#251，append-only）：一次实际变更（值面有动）的前后全量
 * 值快照＋操作者。旧值快照＝回滚依据——回滚＝把旧值写回（即一次新变更、留新痕，
 * 不做版本树，ADR-0021）。值面八件（systemPrompt、modelId、两工具开关、路由
 * 策略四件〔#354〕）全量留痕：每痕都是完整状态对，与 #252 工具开关变更共表共
 * 机制。
 *
 * @param id                            留痕标识（TSID，写痕用例生成）
 * @param agentKey                      智能体稳定键（无 FK——留痕跨配置行存活）
 * @param oldSystemPrompt               变更前 systemPrompt（null＝此前即缺省回落）
 * @param oldModelId                    变更前模型档位（null 同上）
 * @param oldWebSearchEnabled           变更前联网搜索开关
 * @param oldFetchUrlEnabled            变更前网页抓取开关
 * @param oldRoutingEnabled             变更前模型路由开关
 * @param oldRoutingStrongModelId       变更前强模型档（null＝回落代码缺省）
 * @param oldRoutingFileCountThreshold  变更前文件面阈值（null 同上）
 * @param oldRoutingEscalatingSizeLabels 变更前升档规模标签映射（存储形逗号接 token，
 *                                       如「中,大」；null 同上；空串＝空映射，#426）
 * @param oldVerifyModelId              变更前 verify 档位（#424；null＝随执行体生效档）
 * @param oldVerifyRoundBudget          变更前单场验证轮次预算（#424；null＝代码缺省）
 * @param oldFinalVerifyFixBudget       变更前整轨终验修复预算（#427；null＝代码缺省）
 * @param oldVerificationPresenceGateEnabled 变更前验证在场闸开关（#433；恒有值——
 *                                       缺省态恒 true＝闸开）
 * @param oldProofOfOutputGateEnabled   变更前空转产出证明闸开关（#433；同上）
 * @param oldFinalVerificationGateEnabled 变更前整轨终验拦「生成完成」开关（#433；同上）
 * @param newSystemPrompt               变更后 systemPrompt（null＝本次清空回落）
 * @param newModelId                    变更后模型档位（null 同上）
 * @param newWebSearchEnabled           变更后联网搜索开关
 * @param newFetchUrlEnabled            变更后网页抓取开关
 * @param newRoutingEnabled             变更后模型路由开关
 * @param newRoutingStrongModelId       变更后强模型档（null＝本次清空回落代码缺省）
 * @param newRoutingFileCountThreshold  变更后文件面阈值（null 同上）
 * @param newRoutingEscalatingSizeLabels 变更后升档规模标签映射（存储形逗号接 token；
 *                                       null 同上；空串＝空映射）
 * @param newVerifyModelId              变更后 verify 档位（null＝清空随执行体档）
 * @param newVerifyRoundBudget          变更后单场验证轮次预算（null＝清空回落代码缺省）
 * @param newFinalVerifyFixBudget       变更后整轨终验修复预算（null＝清空回落代码缺省）
 * @param newVerificationPresenceGateEnabled 变更后验证在场闸开关（#433；恒有值）
 * @param newProofOfOutputGateEnabled   变更后空转产出证明闸开关（#433；同上）
 * @param newFinalVerificationGateEnabled 变更后整轨终验拦「生成完成」开关（#433；同上）
 * @param oldSingleSessionGenerationEnabled 变更前单会话连续编码开关（#436；恒有值——
 *                                       缺省态恒 true＝单会话开）
 * @param oldParallelSliceMachineEnabled 变更前切片并行机器开关（#436；恒有值——缺省
 *                                       态恒 false＝并行机器关）
 * @param newSingleSessionGenerationEnabled 变更后单会话连续编码开关（#436；恒有值）
 * @param newParallelSliceMachineEnabled 变更后切片并行机器开关（#436；恒有值）
 * @param operatorId                    变更操作者 id（写操作必留痕）
 * @param operatorName                  变更操作者名（直读展示）
 * @param createdAt                     留痕时刻（＝变更动作时刻）
 */
public record AgentConfigTrace(
        long id,
        String agentKey,
        String oldSystemPrompt,
        String oldModelId,
        boolean oldWebSearchEnabled,
        boolean oldFetchUrlEnabled,
        boolean oldRoutingEnabled,
        String oldRoutingStrongModelId,
        Integer oldRoutingFileCountThreshold,
        String oldRoutingEscalatingSizeLabels,
        String oldVerifyModelId,
        Integer oldVerifyRoundBudget,
        Integer oldFinalVerifyFixBudget,
        boolean oldVerificationPresenceGateEnabled,
        boolean oldProofOfOutputGateEnabled,
        boolean oldFinalVerificationGateEnabled,
        boolean oldSingleSessionGenerationEnabled,
        boolean oldParallelSliceMachineEnabled,
        String newSystemPrompt,
        String newModelId,
        boolean newWebSearchEnabled,
        boolean newFetchUrlEnabled,
        boolean newRoutingEnabled,
        String newRoutingStrongModelId,
        Integer newRoutingFileCountThreshold,
        String newRoutingEscalatingSizeLabels,
        String newVerifyModelId,
        Integer newVerifyRoundBudget,
        Integer newFinalVerifyFixBudget,
        boolean newVerificationPresenceGateEnabled,
        boolean newProofOfOutputGateEnabled,
        boolean newFinalVerificationGateEnabled,
        boolean newSingleSessionGenerationEnabled,
        boolean newParallelSliceMachineEnabled,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt) {
}
