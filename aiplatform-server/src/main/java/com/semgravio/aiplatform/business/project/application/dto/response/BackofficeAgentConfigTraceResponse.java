package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.domain.model.AgentConfigTrace;

/**
 * 配置变更留痕读面行（#251 历史可查）：一次实际变更的前后全量值快照＋操作者＋
 * 时刻，按时间倒序（最近先）。append-only——留痕不随任何动作删除；回滚＝把旧值
 * 快照 PUT 回去（即一次新变更、留新痕，不做版本树）。值面全量（含路由策略四件
 * #354、验证面两件 #424）留痕。
 *
 * @param id                  留痕标识（TSID 十进制串）
 * @param oldSystemPrompt     变更前 systemPrompt（null＝此前即缺省回落）
 * @param oldModelId          变更前模型档位（null 同上）
 * @param oldWebSearchEnabled 变更前联网搜索开关
 * @param oldFetchUrlEnabled  变更前网页抓取开关
 * @param oldRoutingEnabled   变更前模型路由开关
 * @param oldRoutingStrongModelId 变更前强模型档（null＝回落代码缺省）
 * @param oldRoutingFileCountThreshold 变更前文件面阈值（null 同上）
 * @param oldRoutingEscalatingSizeLabels 变更前升档规模标签映射（存储形逗号接 token，
 *                                       如「中,大」；null 同上；空串＝空映射，#426）
 * @param oldVerifyModelId    变更前 verify 档位（#424；null＝此前即随执行体档）
 * @param oldVerifyRoundBudget 变更前单场验证轮次预算（#424；null＝此前即代码缺省）
 * @param oldFinalVerifyFixBudget 变更前整轨终验修复预算（#427；null＝此前即代码缺省）
 * @param newSystemPrompt     变更后 systemPrompt（null＝本次清空回落）
 * @param newModelId          变更后模型档位（null 同上）
 * @param newWebSearchEnabled 变更后联网搜索开关
 * @param newFetchUrlEnabled  变更后网页抓取开关
 * @param newRoutingEnabled   变更后模型路由开关
 * @param newRoutingStrongModelId 变更后强模型档（null＝本次清空回落代码缺省）
 * @param newRoutingFileCountThreshold 变更后文件面阈值（null 同上）
 * @param newRoutingEscalatingSizeLabels 变更后升档规模标签映射（存储形逗号接 token；
 *                                       null 同上；空串＝空映射）
 * @param newVerifyModelId    变更后 verify 档位（null＝本次清空随执行体档）
 * @param newVerifyRoundBudget 变更后单场验证轮次预算（null＝本次清空回落代码缺省）
 * @param newFinalVerifyFixBudget 变更后整轨终验修复预算（null＝本次清空回落代码缺省）
 * @param oldVerificationPresenceGateEnabled 变更前验证在场闸开关（#433；恒有值——
 *                          缺省态恒 true＝闸开）
 * @param oldProofOfOutputGateEnabled 变更前空转产出证明闸开关（#433；同上）
 * @param oldFinalVerificationGateEnabled 变更前整轨终验拦「生成完成」开关（#433；同上）
 * @param newVerificationPresenceGateEnabled 变更后验证在场闸开关（#433；恒有值）
 * @param newProofOfOutputGateEnabled 变更后空转产出证明闸开关（#433；同上）
 * @param newFinalVerificationGateEnabled 变更后整轨终验拦「生成完成」开关（#433；同上）
 * @param oldSingleSessionGenerationEnabled 变更前单会话连续编码开关（#436；恒有值——
 *                          缺省态恒 true＝单会话开）
 * @param oldParallelSliceMachineEnabled 变更前切片并行机器开关（#436；恒有值——缺省
 *                          态恒 false＝并行机器关）
 * @param newSingleSessionGenerationEnabled 变更后单会话连续编码开关（#436；恒有值）
 * @param newParallelSliceMachineEnabled 变更后切片并行机器开关（#436；恒有值）
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
        @Schema(description = "变更前升档规模标签映射（#426 存储形逗号接 token，如「中,大」；"
                + "null＝此前即回落代码缺省；空串＝空映射）")
        String oldRoutingEscalatingSizeLabels,
        @Schema(description = "变更前 verify 档位（#424；null＝此前即随执行体生效档）")
        String oldVerifyModelId,
        @Schema(description = "变更前单场验证轮次预算（#424；null＝此前即代码缺省）")
        Integer oldVerifyRoundBudget,
        @Schema(description = "变更前整轨终验修复预算（#427；null＝此前即代码缺省）")
        Integer oldFinalVerifyFixBudget,
        @Schema(description = "变更前验证在场闸开关（#433；恒有值——缺省态恒 true＝闸开）")
        boolean oldVerificationPresenceGateEnabled,
        @Schema(description = "变更前空转产出证明闸开关（#433；恒有值）")
        boolean oldProofOfOutputGateEnabled,
        @Schema(description = "变更前整轨终验拦「生成完成」开关（#433；恒有值）")
        boolean oldFinalVerificationGateEnabled,
        @Schema(description = "变更前单会话连续编码开关（#436；恒有值——缺省态恒 true＝单会话开）")
        boolean oldSingleSessionGenerationEnabled,
        @Schema(description = "变更前切片并行机器开关（#436；恒有值——缺省态恒 false＝并行机器关）")
        boolean oldParallelSliceMachineEnabled,
        @Schema(description = "变更前决策时注入总开关（#437；恒有值——缺省态恒 true＝注入开）")
        boolean oldDecisionHintsEnabled,
        @Schema(description = "变更前失败止损阈值（#437；null＝此前即代码缺省）")
        Integer oldDecisionHintErrorStreak,
        @Schema(description = "变更前微指令堆叠上限（#437；null＝此前即代码缺省）")
        Integer oldDecisionHintStackCap,
        @Schema(description = "变更前禁用集（#437 存储形逗号接键；null＝此前即全启用；空串＝全启用显式形）")
        String oldDecisionHintDisabled,
        @Schema(description = "变更前 verify 自决开关（#442；恒有值——缺省态恒 true＝自决开）")
        boolean oldVerifySelfDecisionEnabled,
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
        @Schema(description = "变更后升档规模标签映射（#426 存储形逗号接 token；null＝本次"
                + "清空回落代码缺省；空串＝空映射）")
        String newRoutingEscalatingSizeLabels,
        @Schema(description = "变更后 verify 档位（#424；null＝本次清空随执行体生效档）")
        String newVerifyModelId,
        @Schema(description = "变更后单场验证轮次预算（#424；null＝本次清空回落代码缺省）")
        Integer newVerifyRoundBudget,
        @Schema(description = "变更后整轨终验修复预算（#427；null＝本次清空回落代码缺省）")
        Integer newFinalVerifyFixBudget,
        @Schema(description = "变更后验证在场闸开关（#433；恒有值）")
        boolean newVerificationPresenceGateEnabled,
        @Schema(description = "变更后空转产出证明闸开关（#433；恒有值）")
        boolean newProofOfOutputGateEnabled,
        @Schema(description = "变更后整轨终验拦「生成完成」开关（#433；恒有值）")
        boolean newFinalVerificationGateEnabled,
        @Schema(description = "变更后单会话连续编码开关（#436；恒有值）")
        boolean newSingleSessionGenerationEnabled,
        @Schema(description = "变更后切片并行机器开关（#436；恒有值）")
        boolean newParallelSliceMachineEnabled,
        @Schema(description = "变更后决策时注入总开关（#437；恒有值）")
        boolean newDecisionHintsEnabled,
        @Schema(description = "变更后失败止损阈值（#437；null＝本次清空回落代码缺省）")
        Integer newDecisionHintErrorStreak,
        @Schema(description = "变更后微指令堆叠上限（#437；null＝本次清空回落代码缺省）")
        Integer newDecisionHintStackCap,
        @Schema(description = "变更后禁用集（#437 存储形逗号接键；null＝本次清空全启用）")
        String newDecisionHintDisabled,
        @Schema(description = "变更后 verify 自决开关（#442；恒有值）")
        boolean newVerifySelfDecisionEnabled,
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
                trace.oldRoutingFileCountThreshold(), trace.oldRoutingEscalatingSizeLabels(),
                trace.oldVerifyModelId(), trace.oldVerifyRoundBudget(),
                trace.oldFinalVerifyFixBudget(),
                trace.oldVerificationPresenceGateEnabled(), trace.oldProofOfOutputGateEnabled(),
                trace.oldFinalVerificationGateEnabled(),
                trace.oldSingleSessionGenerationEnabled(), trace.oldParallelSliceMachineEnabled(),
                trace.oldDecisionHintsEnabled(), trace.oldDecisionHintErrorStreak(),
                trace.oldDecisionHintStackCap(), trace.oldDecisionHintDisabled(),
                trace.oldVerifySelfDecisionEnabled(),
                trace.newSystemPrompt(), trace.newModelId(),
                trace.newWebSearchEnabled(), trace.newFetchUrlEnabled(),
                trace.newRoutingEnabled(), trace.newRoutingStrongModelId(),
                trace.newRoutingFileCountThreshold(), trace.newRoutingEscalatingSizeLabels(),
                trace.newVerifyModelId(), trace.newVerifyRoundBudget(),
                trace.newFinalVerifyFixBudget(),
                trace.newVerificationPresenceGateEnabled(), trace.newProofOfOutputGateEnabled(),
                trace.newFinalVerificationGateEnabled(),
                trace.newSingleSessionGenerationEnabled(), trace.newParallelSliceMachineEnabled(),
                trace.newDecisionHintsEnabled(), trace.newDecisionHintErrorStreak(),
                trace.newDecisionHintStackCap(), trace.newDecisionHintDisabled(),
                trace.newVerifySelfDecisionEnabled(),
                trace.operatorId(), trace.operatorName(), trace.createdAt());
    }
}
