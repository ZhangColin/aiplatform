package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.ModelRoutingAppService;
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
 * @param modelConfigKey                 生效模型配置键（ADR-0031 语义键）
 * @param systemPromptOverridden  true＝systemPrompt 为库覆盖值（false＝枚举默认）
 * @param modelConfigKeyOverridden       true＝模型配置为库覆盖值（false＝枚举默认；路由开
 *                                时为兜底基础档——信号升档可越过；路由关时＝钉死
 *                                人工档）
 * @param defaultSystemPrompt     枚举默认 systemPrompt（预览：清空覆盖即落此值）
 * @param defaultModelConfigKey          枚举默认模型配置（同上）
 * @param webSearchEnabled        联网搜索开关生效态（无行语境＝true；#252 起生效装配）
 * @param fetchUrlEnabled         网页抓取开关存储态（同上）
 * @param routingEnabled          模型路由开关生效态（#354：开＝路由判定优先；关＝
 *                                既有人工定档口径。仅 run 执行体消费）
 * @param routingStrongModelConfigKey    强模型档生效值（路由升档落点；库覆盖值或代码缺省）
 * @param routingStrongModelConfigKeyOverridden true＝强模型档为库覆盖值（false＝代码缺省）
 * @param defaultRoutingStrongModelConfigKey 代码缺省强模型档（预览：清空覆盖即落此值）
 * @param routingFileCountThreshold 文件面阈值生效值（库覆盖值或代码缺省）
 * @param routingFileCountThresholdOverridden true＝文件面阈值为库覆盖值
 * @param defaultRoutingFileCountThreshold 代码缺省文件面阈值（同上预览）
 * @param routingEscalatingSizeLabels 升档规模标签映射生效值（#426：集内标签的片
 *                                升强档；库覆盖值或代码缺省〔大〕）
 * @param routingEscalatingSizeLabelsOverridden true＝映射为库覆盖值（false＝代码缺省）
 * @param defaultRoutingEscalatingSizeLabels 代码缺省映射（预览：清空覆盖即落此值）
 * @param routingFileCountSignalEnabled 文件面信号开关生效态（#454，ADR-0037；缺省
 *                                false＝关＝文件面不参与升档判定——观测值照留进判定
 *                                快照；true＝恢复阈值判据。仅 run 执行体行消费）
 * @param routingSizeLabelSignalEnabled 规模标签信号开关生效态（#454，ADR-0037；缺省
 *                                false＝关＝规模标签不参与升档判定；true＝恢复标签
 *                                映射判据。仅 run 执行体行消费）
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
        @Schema(description = "生效模型配置键（ADR-0031 语义键，如 flash/pro）", example = "flash")
        String modelConfigKey,
        @Schema(description = "systemPrompt 是否库覆盖值（true＝覆盖；false＝枚举默认）")
        boolean systemPromptOverridden,
        @Schema(description = "模型配置是否库覆盖值（true＝覆盖；false＝枚举默认；"
                + "路由开时为兜底基础档——信号升档可越过；路由关时＝钉死人工档）")
        boolean modelConfigKeyOverridden,
        @Schema(description = "枚举默认 systemPrompt（预览：清空覆盖即落此值——身份与"
                + "配置分治的缺省正本）")
        String defaultSystemPrompt,
        @Schema(description = "枚举默认模型配置（清空覆盖即落此值）", example = "flash")
        String defaultModelConfigKey,
        @Schema(description = "联网搜索开关生效态（#252 起生效装配：关即退出槽位装配面；无覆盖行语境＝true 开）")
        boolean webSearchEnabled,
        @Schema(description = "网页抓取开关生效态（生效语义同 webSearchEnabled；无覆盖行语境＝true 开）")
        boolean fetchUrlEnabled,
        @Schema(description = "模型路由开关生效态（#354：开＝路由判定优先；关＝既有人工"
                + "定档口径。仅 run 执行体消费，无覆盖行语境＝true 开）")
        boolean routingEnabled,
        @Schema(description = "强模型档生效值（#354 路由升档落点；库覆盖值或代码缺省——"
                + "对照 routingStrongModelConfigKeyOverridden 知来源）", example = "pro")
        String routingStrongModelConfigKey,
        @Schema(description = "强模型档是否库覆盖值（true＝覆盖；false＝代码缺省）")
        boolean routingStrongModelConfigKeyOverridden,
        @Schema(description = "代码缺省强模型档（预览：清空覆盖即落此值）", example = "pro")
        String defaultRoutingStrongModelConfigKey,
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
        @Schema(description = "文件面信号开关生效态（#454，ADR-0037：缺省 false＝关＝文件面"
                + "不参与升档判定〔观测值照留进判定快照〕；true＝恢复阈值判据——阈值旋钮"
                + "照旧生效。失败重试信号不受本开关影响、常开。仅 run 执行体消费）")
        boolean routingFileCountSignalEnabled,
        @Schema(description = "规模标签信号开关生效态（#454，ADR-0037：缺省 false＝关＝规模"
                + "标签不参与升档判定；true＝恢复标签映射判据——映射旋钮照旧生效。"
                + "仅 run 执行体消费）")
        boolean routingSizeLabelSignalEnabled,
        @Schema(description = "最近写者 id（admin 侧 TSID；null＝从未配置）", example = "700200")
        String operatorId,
        @Schema(description = "最近写者名（直读展示）", example = "运营·技能管理员")
        String operatorName,
        @Schema(description = "最近写入时刻（null＝从未配置）")
        LocalDateTime updatedAt) {

    /** 生效面合成单源：枚举缺省正本＋覆盖行（null 先归一缺省态）＋写者＋路由策略缺省。 */
    public static BackofficeAgentConfigResponse of(AgentProfile profile, AgentOperationalConfig override) {
        AgentOperationalConfig config =
                override != null ? override : AgentOperationalConfig.defaults(profile.key());
        return new BackofficeAgentConfigResponse(
                profile.key(),
                profile.getName(),
                config.effectiveSystemPrompt(profile.systemPrompt()),
                config.effectiveModelConfigKey(profile.modelConfigKey()),
                config.systemPrompt() != null,
                config.modelConfigKey() != null,
                profile.systemPrompt(),
                profile.modelConfigKey(),
                config.webSearchEnabled(),
                config.fetchUrlEnabled(),
                config.routingEnabled(),
                config.routingStrongModelConfigKey() != null
                        ? config.routingStrongModelConfigKey() : ModelRoutingAppService.DEFAULT_STRONG_MODEL_CONFIG_KEY,
                config.routingStrongModelConfigKey() != null,
                ModelRoutingAppService.DEFAULT_STRONG_MODEL_CONFIG_KEY,
                config.routingFileCountThreshold() != null
                        ? config.routingFileCountThreshold() : ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD,
                config.routingFileCountThreshold() != null,
                ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD,
                config.routingEscalatingSizeLabels() != null
                        ? SliceSizeLabel.namesOf(config.routingEscalatingSizeLabels())
                        : SliceSizeLabel.namesOf(ModelRoutingAppService.DEFAULT_ESCALATING_SIZE_LABELS),
                config.routingEscalatingSizeLabels() != null,
                SliceSizeLabel.namesOf(ModelRoutingAppService.DEFAULT_ESCALATING_SIZE_LABELS),
                config.routingFileCountSignalEnabled(), config.routingSizeLabelSignalEnabled(),
                config.operatorId(),
                config.operatorName(),
                config.updatedAt());
    }
}
