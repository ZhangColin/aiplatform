package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.ModelRoutingAppService;
import com.semgravio.aiplatform.business.project.domain.decisionhint.DecisionHintKind;
import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;
import com.semgravio.aiplatform.business.project.domain.model.AgentOperationalConfig;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

/**
 * 智能体配置读面（#251）：GET 与 PUT 回执同形。生效值（库值或枚举默认）＋
 * 覆盖标记（运营要看到「现在跑的是覆盖还是默认」）＋枚举默认预览（清空回落
 * 即落此值——回复默认前可先看落点）＋增强工具开关生效态（#252 起生效装配）＋
 * 模型路由策略生效态（#354，仅 run 执行体消费）＋最近
 * 写者。全库三座智能体（main/executor/designer）各自寻址读写。
 *
 * @param agentKey                智能体稳定键（寻址腿）
 * @param agentName               展示名
 * @param systemPrompt            生效 systemPrompt（库覆盖值或枚举默认——装配实取值）
 * @param modelId                 生效模型档位键（ADR-0031 语义键）
 * @param systemPromptOverridden  true＝systemPrompt 为库覆盖值（false＝枚举默认）
 * @param modelIdOverridden       true＝模型档位为库覆盖值（false＝枚举默认；路由开
 *                                时为兜底基础档——信号升档可越过；路由关时＝钉死
 *                                人工档）
 * @param defaultSystemPrompt     枚举默认 systemPrompt（预览：清空覆盖即落此值）
 * @param defaultModelId          枚举默认模型档位（同上）
 * @param webSearchEnabled        联网搜索开关生效态（无行语境＝true；#252 起生效装配）
 * @param fetchUrlEnabled         网页抓取开关存储态（同上）
 * @param routingEnabled          模型路由开关生效态（#354：开＝路由判定优先；关＝
 *                                既有人工定档口径。仅 run 执行体消费）
 * @param routingStrongModelId    强模型档生效值（路由升档落点；库覆盖值或代码缺省）
 * @param routingStrongModelIdOverridden true＝强模型档为库覆盖值（false＝代码缺省）
 * @param defaultRoutingStrongModelId 代码缺省强模型档（预览：清空覆盖即落此值）
 * @param routingFileCountThreshold 文件面阈值生效值（库覆盖值或代码缺省）
 * @param routingFileCountThresholdOverridden true＝文件面阈值为库覆盖值
 * @param defaultRoutingFileCountThreshold 代码缺省文件面阈值（同上预览）
 * @param routingEscalatingSizeLabels 升档规模标签映射生效值（#426：集内标签的片
 *                                升强档；库覆盖值或代码缺省〔大〕）
 * @param routingEscalatingSizeLabelsOverridden true＝映射为库覆盖值（false＝代码缺省）
 * @param defaultRoutingEscalatingSizeLabels 代码缺省映射（预览：清空覆盖即落此值）
 * @param verifyModelId           verify 子智能体档位覆盖值（#424；null＝随执行体生效档
 *                                ——含路由升档，缺省同档不降）
 * @param verifyModelIdOverridden true＝verify 档位为库覆盖值（false＝随执行体档）
 * @param verifyRoundBudget       单场验证轮次预算生效值（#424；库覆盖值或代码缺省）
 * @param verifyRoundBudgetOverridden true＝轮次预算为库覆盖值（false＝代码缺省）
 * @param defaultVerifyRoundBudget 代码缺省轮次预算（预览：清空覆盖即落此值）
 * @param finalVerifyFixBudget    整轨终验修复预算生效值（#427；库覆盖值或代码缺省）
 * @param finalVerifyFixBudgetOverridden true＝终验修复预算为库覆盖值（false＝代码缺省）
 * @param defaultFinalVerifyFixBudget 代码缺省终验修复预算（预览：清空覆盖即落此值）
 * @param verificationPresenceGateEnabled 验证在场闸开关生效态（#433；#435 起缺省
 *                                false＝闸关——本 run 无验证登记不收口仅在该闸被
 *                                运营打开时生效。仅 run 执行体行消费）
 * @param proofOfOutputGateEnabled 空转产出证明闸开关生效态（#433；#435 起缺省
 *                                false＝闸关——零 diff 且无确认不收口仅在该闸被运营
 *                                打开时生效。仅 run 执行体行消费）
 * @param finalVerificationGateEnabled 整轨终验拦「生成完成」开关生效态（#433；
 *                                #435 起缺省 false＝闸关——终验不起 run、不拦完成
 *                                叙事。仅 run 执行体行消费）
 * @param singleSessionGenerationEnabled 单会话连续编码开关生效态（#436；缺省
 *                                true＝单会话开——阶段 0＋全部切片同会话槽位连续
 *                                追加；false＝旧「每片新会话」形态。仅 run 执行体
 *                                行消费）
 * @param parallelSliceMachineEnabled 切片并行机器开关生效态（#436；缺省 false＝
 *                                fork/波/并行/合并全关；true＝宽波 fork 并行旧行为。
 *                                仅 run 执行体行消费）
 * @param operatorId              最近写者 id（admin 侧 TSID；null＝从未配置）
 * @param operatorName            最近写者名（直读展示）
 * @param updatedAt               最近写入时刻（null＝从未配置）
 */
public record BackofficeAgentConfigResponse(
        @Schema(description = "智能体稳定键（main=主智能体 / executor=run 执行体 / designer=设计执行体）",
                example = "main")
        String agentKey,
        @Schema(description = "展示名", example = "主智能体")
        String agentName,
        @Schema(description = "生效 systemPrompt（库覆盖值或枚举默认——装配实取值，"
                + "对照 systemPromptOverridden 知来源）")
        String systemPrompt,
        @Schema(description = "生效模型档位键（ADR-0031 语义键，如 flash/pro）", example = "flash")
        String modelId,
        @Schema(description = "systemPrompt 是否库覆盖值（true＝覆盖；false＝枚举默认）")
        boolean systemPromptOverridden,
        @Schema(description = "模型档位是否库覆盖值（true＝覆盖；false＝枚举默认；"
                + "路由开时为兜底基础档——信号升档可越过；路由关时＝钉死人工档）")
        boolean modelIdOverridden,
        @Schema(description = "枚举默认 systemPrompt（预览：清空覆盖即落此值——身份与"
                + "配置分治的缺省正本）")
        String defaultSystemPrompt,
        @Schema(description = "枚举默认模型档位（清空覆盖即落此值）", example = "flash")
        String defaultModelId,
        @Schema(description = "联网搜索开关生效态（#252 起生效装配：关即退出槽位装配面；无覆盖行语境＝true 开）")
        boolean webSearchEnabled,
        @Schema(description = "网页抓取开关生效态（生效语义同 webSearchEnabled；无覆盖行语境＝true 开）")
        boolean fetchUrlEnabled,
        @Schema(description = "模型路由开关生效态（#354：开＝路由判定优先；关＝既有人工"
                + "定档口径。仅 run 执行体消费，无覆盖行语境＝true 开）")
        boolean routingEnabled,
        @Schema(description = "强模型档生效值（#354 路由升档落点；库覆盖值或代码缺省——"
                + "对照 routingStrongModelIdOverridden 知来源）", example = "pro")
        String routingStrongModelId,
        @Schema(description = "强模型档是否库覆盖值（true＝覆盖；false＝代码缺省）")
        boolean routingStrongModelIdOverridden,
        @Schema(description = "代码缺省强模型档（预览：清空覆盖即落此值）", example = "pro")
        String defaultRoutingStrongModelId,
        @Schema(description = "文件面阈值生效值（#354/#426：应用文件面——已跟踪文件数扣"
                + "基座基数 ≥ 此值升强档；库覆盖值或代码缺省）", example = "30")
        Integer routingFileCountThreshold,
        @Schema(description = "文件面阈值是否库覆盖值（true＝覆盖；false＝代码缺省）")
        boolean routingFileCountThresholdOverridden,
        @Schema(description = "代码缺省文件面阈值（预览：清空覆盖即落此值）", example = "30")
        Integer defaultRoutingFileCountThreshold,
        @Schema(description = "升档规模标签映射生效值（#426：集内标签的片升强档，缺省"
                + "[\"大\"]；空数组＝信号整体关停）", example = "[\"大\"]")
        List<String> routingEscalatingSizeLabels,
        @Schema(description = "映射是否库覆盖值（true＝覆盖；false＝代码缺省）")
        boolean routingEscalatingSizeLabelsOverridden,
        @Schema(description = "代码缺省升档映射（预览：清空覆盖即落此值）", example = "[\"大\"]")
        List<String> defaultRoutingEscalatingSizeLabels,
        @Schema(description = "verify 验证子智能体档位覆盖值（#424 片轻验：null＝随执行体"
                + "生效档——含路由升档，缺省同档不降）", example = "pro")
        String verifyModelId,
        @Schema(description = "verify 档位是否库覆盖值（true＝覆盖钉死；false＝随执行体档）")
        boolean verifyModelIdOverridden,
        @Schema(description = "单场验证轮次预算生效值（#424 防无限复验：登记达预算仍有"
                + "未过项即停修复—复验；库覆盖值或代码缺省）", example = "3")
        Integer verifyRoundBudget,
        @Schema(description = "轮次预算是否库覆盖值（true＝覆盖；false＝代码缺省）")
        boolean verifyRoundBudgetOverridden,
        @Schema(description = "代码缺省轮次预算（预览：清空覆盖即落此值）", example = "3")
        Integer defaultVerifyRoundBudget,
        @Schema(description = "整轨终验修复预算生效值（#427：终验 fail 自动起 fix run 修→复走，"
                + "耗尽版本照成＋已知缺陷如实呈现；库覆盖值或代码缺省）", example = "2")
        Integer finalVerifyFixBudget,
        @Schema(description = "终验修复预算是否库覆盖值（true＝覆盖；false＝代码缺省）")
        boolean finalVerifyFixBudgetOverridden,
        @Schema(description = "代码缺省终验修复预算（预览：清空覆盖即落此值）", example = "2")
        Integer defaultFinalVerifyFixBudget,
        @Schema(description = "验证在场闸开关生效态（#433；#435 起缺省 false＝闸关——本 run 无"
                + "report_verification 登记不收口不生效〔#424〕；true＝闸开。仅 run 执行体行消费）")
        boolean verificationPresenceGateEnabled,
        @Schema(description = "空转产出证明闸开关生效态（#433；#435 起缺省 false＝闸关——段起点"
                + "以来零 diff 且无「能力已存在」确认不收口不生效〔#420〕；true＝闸开。"
                + "仅 run 执行体行消费）")
        boolean proofOfOutputGateEnabled,
        @Schema(description = "整轨终验拦「生成完成」开关生效态（#433；#435 起缺省 false＝闸关——"
                + "终验不起 run；true＝闸开——轨道完成前终验 fail 拦完成叙事〔#427〕。仅 run 执行体行消费）")
        boolean finalVerificationGateEnabled,
        @Schema(description = "单会话连续编码开关生效态（#436，#432 Step 2；缺省 true＝单会话开〔默认〕——"
                + "阶段 0＋全部切片在同一执行会话槽位内连续追加；false＝旧「每片新会话」形态。"
                + "仅 run 执行体行消费）")
        boolean singleSessionGenerationEnabled,
        @Schema(description = "切片并行机器开关生效态（#436，#432 Step 2；缺省 false＝并行机器关〔默认〕——"
                + "fork 隔离工作区/波调度/并行执行/合并全关；true＝宽波 fork 并行旧行为〔#351〕。"
                + "仅 run 执行体行消费，仅单会话关闭后有消费面）")
        boolean parallelSliceMachineEnabled,
        @Schema(description = "决策时注入总开关生效态（#437，#432 Step 3；缺省 true＝注入开〔默认〕——"
                + "执行体 ReAct 循环每迭代判定注入一条微指令；false＝全停原样透传。"
                + "仅 run 执行体行消费）")
        boolean decisionHintsEnabled,
        @Schema(description = "失败止损阈值生效值（#437；库覆盖值或代码缺省）", example = "3")
        Integer decisionHintErrorStreak,
        @Schema(description = "失败止损阈值是否库覆盖值（true＝覆盖；false＝代码缺省）")
        boolean decisionHintErrorStreakOverridden,
        @Schema(description = "代码缺省失败止损阈值（预览：清空覆盖即落此值）", example = "3")
        Integer defaultDecisionHintErrorStreak,
        @Schema(description = "微指令堆叠上限生效值（#437；库覆盖值或代码缺省）", example = "3")
        Integer decisionHintStackCap,
        @Schema(description = "堆叠上限是否库覆盖值（true＝覆盖；false＝代码缺省）")
        boolean decisionHintStackCapOverridden,
        @Schema(description = "代码缺省堆叠上限（预览：清空覆盖即落此值）", example = "3")
        Integer defaultDecisionHintStackCap,
        @Schema(description = "禁用集生效值（#437；库覆盖值——空数组＝全启用）",
                example = "[\"parallel_tools\"]")
        List<String> decisionHintDisabled,
        @Schema(description = "禁用集是否库覆盖值（true＝覆盖；false＝全启用）")
        boolean decisionHintDisabledOverridden,
        @Schema(description = "verify 自决开关生效态（#442，#441-1；缺省 true＝自决开〔默认〕——"
                + "执行体生成过程中自决调用 verify 自测〔#435 自决化〕；false＝自决关闭——"
                + "提示层收窄，生成过程中不自发委派 verify，平台驱动验证腿不受影响。"
                + "仅 run 执行体行消费）")
        boolean verifySelfDecisionEnabled,
        @Schema(description = "最近写者 id（admin 侧 TSID；null＝从未配置）", example = "700200")
        String operatorId,
        @Schema(description = "最近写者名（直读展示）", example = "运营·技能管理员")
        String operatorName,
        @Schema(description = "最近写入时刻（null＝从未配置）")
        LocalDateTime updatedAt) {

    /** 生效面合成单源：枚举缺省正本＋覆盖行（null 先归一缺省态）＋写者＋路由策略缺省＋验证面缺省。 */
    public static BackofficeAgentConfigResponse of(AgentProfile profile, AgentOperationalConfig override) {
        AgentOperationalConfig config =
                override != null ? override : AgentOperationalConfig.defaults(profile.key());
        return new BackofficeAgentConfigResponse(
                profile.key(),
                profile.getName(),
                config.effectiveSystemPrompt(profile.systemPrompt()),
                config.effectiveModelId(profile.modelId()),
                config.systemPrompt() != null,
                config.modelId() != null,
                profile.systemPrompt(),
                profile.modelId(),
                config.webSearchEnabled(),
                config.fetchUrlEnabled(),
                config.routingEnabled(),
                config.routingStrongModelId() != null
                        ? config.routingStrongModelId() : ModelRoutingAppService.DEFAULT_STRONG_MODEL_ID,
                config.routingStrongModelId() != null,
                ModelRoutingAppService.DEFAULT_STRONG_MODEL_ID,
                config.routingFileCountThreshold() != null
                        ? config.routingFileCountThreshold() : ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD,
                config.routingFileCountThreshold() != null,
                ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD,
                config.routingEscalatingSizeLabels() != null
                        ? SliceSizeLabel.namesOf(config.routingEscalatingSizeLabels())
                        : SliceSizeLabel.namesOf(ModelRoutingAppService.DEFAULT_ESCALATING_SIZE_LABELS),
                config.routingEscalatingSizeLabels() != null,
                SliceSizeLabel.namesOf(ModelRoutingAppService.DEFAULT_ESCALATING_SIZE_LABELS),
                config.verifyModelId(),
                config.verifyModelId() != null,
                config.verifyRoundBudget() != null
                        ? config.verifyRoundBudget() : AgentConfigAppService.DEFAULT_VERIFY_ROUND_BUDGET,
                config.verifyRoundBudget() != null,
                AgentConfigAppService.DEFAULT_VERIFY_ROUND_BUDGET,
                config.finalVerifyFixBudget() != null
                        ? config.finalVerifyFixBudget() : AgentConfigAppService.DEFAULT_FINAL_VERIFY_FIX_BUDGET,
                config.finalVerifyFixBudget() != null,
                AgentConfigAppService.DEFAULT_FINAL_VERIFY_FIX_BUDGET,
                config.verificationPresenceGateEnabled(),
                config.proofOfOutputGateEnabled(),
                config.finalVerificationGateEnabled(),
                config.singleSessionGenerationEnabled(),
                config.parallelSliceMachineEnabled(),
                config.decisionHintsEnabled(),
                config.decisionHintErrorStreak() != null
                        ? config.decisionHintErrorStreak()
                        : AgentConfigAppService.DEFAULT_DECISION_HINT_ERROR_STREAK,
                config.decisionHintErrorStreak() != null,
                AgentConfigAppService.DEFAULT_DECISION_HINT_ERROR_STREAK,
                config.decisionHintStackCap() != null
                        ? config.decisionHintStackCap()
                        : AgentConfigAppService.DEFAULT_DECISION_HINT_STACK_CAP,
                config.decisionHintStackCap() != null,
                AgentConfigAppService.DEFAULT_DECISION_HINT_STACK_CAP,
                config.decisionHintDisabled() != null && !config.decisionHintDisabled().isBlank()
                        ? DecisionHintKind.decode(config.decisionHintDisabled()).stream()
                                .map(DecisionHintKind::key).toList()
                        : List.of(),
                config.decisionHintDisabled() != null,
                config.verifySelfDecisionEnabled(),
                config.operatorId(),
                config.operatorName(),
                config.updatedAt());
    }
}
