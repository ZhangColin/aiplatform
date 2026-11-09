package com.semgravio.aiplatform.business.project.application.dto.command;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 智能体配置覆盖态命令（#251，PUT 全量语义）：请求体即该智能体覆盖态终态——
 * 值面全量替换，与槽位指派「清单即终态」同款形制。覆盖两件 null／缺省／
 * 纯空白＝清空覆盖（装配回落枚举默认——GET 的 defaultSystemPrompt/defaultModelId
 * 即回落落点）；工具开关两件缺省 true＝开。
 *
 * <p>档位键即时校验（#367，ADR-0031）：modelId 与 routingStrongModelId 非空时
 * 必须在模型档位面（/model-tiers）有行且启用——错值写不进去（PRJ_074/075，
 * 白名单从档位表来，不再靠留痕回滚兜底）；null＝清空回落缺省不校验。
 * 工具开关已生效装配（#252：关即退出槽位装配面、开即回归——窄幅写口见
 * {@code PUT /agent-tools/{toolName}}）。</p>
 *
 * <p><b>模型路由策略四件（#354；信号面 #426 修订）</b>：路由开关缺省 true＝开
 * （路由判定优先；关＝钉死位——人工档恒生效）；强模型档、文件面阈值与升档规模
 * 标签映射 null＝清空回落代码缺省。协同口径：路由判定优先、人工覆盖兜底——
 * 信号（重试失败/文件面/规模标签映射命中）升强档优先，无信号回落人工档
 * （modelId 覆盖 → 枚举缺省——覆盖即基础档）。仅 run 执行体（executor）消费，
 * 其余智能体行同列无消费。</p>
 *
 * <p><b>验证面两件（#424 片轻验）</b>：verify 验证子智能体档位与单场验证轮次
 * 预算——档位 null＝随执行体生效档（含路由升档，缺省同档不降＝#406 裁决「验证
 * 判错代价高于省的秒数」）；预算 null＝清空回落代码缺省 3（首轮＋两轮复验）。</p>
 *
 * @param systemPrompt      覆盖 systemPrompt（null＝清空回落枚举默认）
 * @param modelId           覆盖模型档位键（ADR-0031 语义键如 flash/pro；null＝清空
 *                          回落枚举默认；路由开时为兜底基础档——信号升档可越过）
 * @param webSearchEnabled  联网搜索开关（缺省 true；关即退出装配面）
 * @param fetchUrlEnabled   网页抓取开关（同上；缺省 true）
 * @param routingEnabled    模型路由开关（缺省 true＝开；false＝既有人工定档口径）
 * @param routingStrongModelId 强模型档键（语义键同 modelId；null/缺省/纯空白＝清空
 *                          回落代码缺省 pro；非空时即时校验同 modelId〔#367〕）
 * @param routingFileCountThreshold 文件面阈值（应用文件面——已跟踪文件数扣基座基数
 *                          ≥ 此值升强档；null＝清空回落代码缺省 30，#426 重校）
 * @param routingEscalatingSizeLabels 升档规模标签映射（#426：集内标签的片升强档，
 *                          缺省映射 [大]；token 须在小/中/大封闭集内——非法值
 *                          PRJ_079；null/缺省＝清空回落代码缺省；空数组＝空映射
 *                          ＝规模标签信号整体关停，合法运营选择）
 * @param verifyModelId     verify 子智能体档位键（#424；null/缺省/纯空白＝清空随
 *                          执行体生效档；非空时即时校验同 modelId〔#367〕）
 * @param verifyRoundBudget 单场验证轮次预算（#424 防无限复验：report_verification
 *                          登记达预算仍有未过项即停修复—复验；null＝清空回落代码
 *                          缺省 3；1-20 整数，越界 PRJ_078）
 * @param finalVerifyFixBudget 整轨终验修复预算（#427：终验 fail 自动起 fix run 修→
 *                          复走，耗尽版本照成＋已知缺陷如实呈现；null＝清空回落
 *                          代码缺省 2；0-10 整数——0＝不起修复 run 的运营选择，
 *                          越界 PRJ_080）
 * @param verificationPresenceGateEnabled 验证在场闸开关（#433，#432 Step 1 开关承载
 *                          面：缺省 true＝闸开——本 run 无 report_verification 登记
 *                          不准收口〔#424〕；false＝闸跳过。仅 run 执行体行消费）
 * @param proofOfOutputGateEnabled 空转产出证明闸开关（#433；缺省 true＝闸开——段起点
 *                          以来零 diff 且无「能力已存在」确认不收口〔#420〕；false＝
 *                          闸跳过。仅 run 执行体行消费）
 * @param finalVerificationGateEnabled 整轨终验拦「生成完成」开关（#433；缺省 true＝
 *                          闸开——轨道完成前以核心旅程为轴终验、fail 拦完成叙事
 *                          〔#427〕；false＝终验不起 run，完成口径回落 #427 前形态。
 *                          仅 run 执行体行消费）
 */
public record AgentConfigUpdateCommand(
        @Schema(description = "覆盖 systemPrompt 全文（PUT 全量语义：null/缺省/纯空白＝"
                + "清空覆盖，装配回落枚举默认——GET 回执的 defaultSystemPrompt 即回落落点）")
        String systemPrompt,
        @Schema(description = "覆盖模型档位键（如 pro；null/缺省/纯空白＝"
                + "清空覆盖回落枚举默认。即时校验（#367）：须在模型档位面有行"
                + "且启用（PRJ_074/075））",
                example = "pro")
        String modelId,
        @Schema(description = "增强工具开关：联网搜索（缺省 true＝开；#252 起生效装配——"
                + "关即退出槽位装配面、开即回归，窄幅写口 PUT /agent-tools/{toolName}）")
        Boolean webSearchEnabled,
        @Schema(description = "增强工具开关：网页抓取（缺省 true＝开；生效语义同 webSearchEnabled）")
        Boolean fetchUrlEnabled,
        @Schema(description = "模型路由开关（#354：缺省 true＝开——路由判定优先；"
                + "false＝既有人工定档口径。仅 run 执行体消费）")
        Boolean routingEnabled,
        @Schema(description = "强模型档语义键（#354 路由升档落点；null/缺省/纯空白＝清空"
                + "回落代码缺省 pro。即时校验同 modelId（#367）",
                example = "pro")
        String routingStrongModelId,
        @Schema(description = "文件面阈值（#354/#426：应用文件面——工作区已跟踪文件数扣"
                + "基座基数 ≥ 此值升强档；null＝清空回落代码缺省 30）", example = "30")
        Integer routingFileCountThreshold,
        @Schema(description = "升档规模标签映射（#426：集内标签的片升强档，缺省 [\"大\"]；"
                + "token 须在小/中/大封闭集内——非法值 PRJ_079；空数组＝空映射＝"
                + "规模标签信号整体关停；null/缺省＝清空回落代码缺省）",
                example = "[\"大\"]")
        List<String> routingEscalatingSizeLabels,
        @Schema(description = "verify 验证子智能体档位键（#424 片轻验：null/缺省/纯空白＝"
                + "清空随执行体生效档〔含路由升档，缺省同档不降〕。即时校验同 modelId"
                + "（#367：须在模型档位面有行且启用）",
                example = "pro")
        String verifyModelId,
        @Schema(description = "单场验证轮次预算（#424 防无限复验：登记达预算仍有未过项即停"
                + "修复—复验、未过项如实带入收口；null＝清空回落代码缺省 3；1-20 整数，"
                + "越界 PRJ_078）",
                example = "3")
        Integer verifyRoundBudget,
        @Schema(description = "整轨终验修复预算（#427：终验 fail 自动起 fix run 修→复走终验，"
                + "耗尽版本照成＋已知缺陷清单如实呈现；null＝清空回落代码缺省 2；0-10 整数"
                + "——0＝不起修复 run 的运营选择，越界 PRJ_080）",
                example = "2")
        Integer finalVerifyFixBudget,
        @Schema(description = "验证在场闸开关（#433：缺省 true＝闸开——本 run 无验证"
                + "登记不准收口；false＝闸跳过。仅 run 执行体行消费）")
        Boolean verificationPresenceGateEnabled,
        @Schema(description = "空转产出证明闸开关（#433：缺省 true＝闸开——段起点以来"
                + "零 diff 且无「能力已存在」确认不收口；false＝闸跳过。仅 run 执行体行消费）")
        Boolean proofOfOutputGateEnabled,
        @Schema(description = "整轨终验拦「生成完成」开关（#433：缺省 true＝闸开——轨道"
                + "完成前终验 fail 拦完成叙事；false＝终验不起 run。仅 run 执行体行消费）")
        Boolean finalVerificationGateEnabled) {
}
