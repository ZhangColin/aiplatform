package com.semgravio.aiplatform.business.project.application.dto.command;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 智能体配置覆盖态命令（#251，PUT 全量语义）：请求体即该智能体覆盖态终态——
 * 值面全量替换，与槽位指派「清单即终态」同款形制。覆盖两件 null／缺省／
 * 纯空白＝清空覆盖（装配回落枚举默认——GET 的 defaultSystemPrompt/defaultModelConfigKey
 * 即回落落点）；工具开关两件缺省 true＝开。
 *
 * <p>配置键即时校验（#367，ADR-0031）：modelConfigKey 与 routingStrongModelConfigKey 非空时
 * 必须在模型配置面（/model-configs）有行且启用——错值写不进去（PRJ_074/075，
 * 白名单从配置表来，不再靠留痕回滚兜底）；null＝清空回落缺省不校验。
 * 工具开关已生效装配（#252：关即退出槽位装配面、开即回归——窄幅写口见
 * {@code PUT /agent-tools/{toolName}}）。</p>
 *
 * <p><b>模型路由策略四件（#354；信号面 #426 修订）</b>：路由开关缺省 true＝开
 * （路由判定优先；关＝钉死位——人工档恒生效）；强模型档、文件面阈值与升档规模
 * 标签映射 null＝清空回落代码缺省。协同口径：路由判定优先、人工覆盖兜底——
 * 信号（重试失败/文件面/规模标签映射命中）升强档优先，无信号回落人工档
 * （modelConfigKey 覆盖 → 枚举缺省——覆盖即基础档）。仅 run 执行体（executor）消费，
 * 其余智能体行同列无消费。</p>
 *
 * <p><b>路由结构性信号开关两件（#454，ADR-0037——用户裁决）</b>：文件面信号
 * 开关与规模标签信号开关——缺省 false/null＝关＝对应信号不参与升档判定（观测值
 * 照留进判定快照）；true＝恢复对应判据（阈值/映射旋钮照旧生效）。失败重试信号
 * 不受两开关影响、常开。仅 run 执行体（executor）消费。</p>
 *
 * <p><b>审查与收口组配置面已随 #605 退役</b>：验证面两件（#424）、终验修复预算
 * （#427）、收口闸开关（#433）与基座 lint 阻断开关（#541）随验证子智能体／整轨
 * 终验／收口三开关／基座 lint 扫描整组拆除（ADR-0055），本命令只余仍在消费的
 * 覆盖态与工具/路由开关。</p>
 *
 * @param systemPrompt      覆盖 systemPrompt（null＝清空回落枚举默认）
 * @param modelConfigKey           覆盖模型配置键（ADR-0031 语义键如 flash/pro；null＝清空
 *                          回落枚举默认；路由开时为兜底基础档——信号升档可越过）
 * @param webSearchEnabled  联网搜索开关（缺省 true；关即退出装配面）
 * @param fetchUrlEnabled   网页抓取开关（同上；缺省 true）
 * @param routingEnabled    模型路由开关（缺省 true＝开；false＝既有人工定档口径）
 * @param routingStrongModelConfigKey 强模型档键（语义键同 modelConfigKey；null/缺省/纯空白＝清空
 *                          回落代码缺省 pro；非空时即时校验同 modelConfigKey〔#367〕）
 * @param routingFileCountThreshold 文件面阈值（应用文件面——已跟踪文件数扣基座基数
 *                          ≥ 此值升强档；null＝清空回落代码缺省 30，#426 重校）
 * @param routingEscalatingSizeLabels 升档规模标签映射（#426：集内标签的片升强档，
 *                          缺省映射 [大]；token 须在小/中/大封闭集内——非法值
 *                          PRJ_079；null/缺省＝清空回落代码缺省；空数组＝空映射
 *                          ＝规模标签信号整体关停，合法运营选择）
 * @param routingFileCountSignalEnabled 文件面信号开关（#454，ADR-0037；缺省 false/null
 *                          ＝关＝文件面不参与升档判定〔观测值照留〕；true＝恢复阈值
 *                          判据，阈值旋钮照旧。仅 run 执行体行消费）
 * @param routingSizeLabelSignalEnabled 规模标签信号开关（#454，ADR-0037；缺省 false/
 *                          null＝关＝规模标签不参与升档判定；true＝恢复标签映射判据，
 *                          映射旋钮照旧。仅 run 执行体行消费）
 */
public record AgentConfigUpdateCommand(
        @Schema(description = "覆盖 systemPrompt 全文（PUT 全量语义：null/缺省/纯空白＝"
                + "清空覆盖，装配回落枚举默认——GET 回执的 defaultSystemPrompt 即回落落点）")
        String systemPrompt,
        @Schema(description = "覆盖模型配置键（如 pro；null/缺省/纯空白＝"
                + "清空覆盖回落枚举默认。即时校验（#367）：须在模型配置面有行"
                + "且启用（PRJ_074/075））",
                example = "pro")
        String modelConfigKey,
        @Schema(description = "增强工具开关：联网搜索（缺省 true＝开；#252 起生效装配——"
                + "关即退出槽位装配面、开即回归，窄幅写口 PUT /agent-tools/{toolName}）")
        Boolean webSearchEnabled,
        @Schema(description = "增强工具开关：网页抓取（缺省 true＝开；生效语义同 webSearchEnabled）")
        Boolean fetchUrlEnabled,
        @Schema(description = "模型路由开关（#354：缺省 true＝开——路由判定优先；"
                + "false＝既有人工定档口径。仅 run 执行体消费）")
        Boolean routingEnabled,
        @Schema(description = "强模型档语义键（#354 路由升档落点；null/缺省/纯空白＝清空"
                + "回落代码缺省 pro。即时校验同 modelConfigKey（#367）",
                example = "pro")
        String routingStrongModelConfigKey,
        @Schema(description = "文件面阈值（#354/#426：应用文件面——工作区已跟踪文件数扣"
                + "基座基数 ≥ 此值升强档；null＝清空回落代码缺省 30）", example = "30")
        Integer routingFileCountThreshold,
        @Schema(description = "升档规模标签映射（#426：集内标签的片升强档，缺省 [\"大\"]；"
                + "token 须在小/中/大封闭集内——非法值 PRJ_079；空数组＝空映射＝"
                + "规模标签信号整体关停；null/缺省＝清空回落代码缺省）",
                example = "[\"大\"]")
        List<String> routingEscalatingSizeLabels,
        @Schema(description = "文件面信号开关（#454，ADR-0037：缺省 false/null＝关＝文件面"
                + "不参与升档判定〔观测值照留进判定快照〕；true＝恢复阈值判据——阈值旋钮"
                + "照旧生效。失败重试信号不受本开关影响、常开。仅 run 执行体行消费）")
        Boolean routingFileCountSignalEnabled,
        @Schema(description = "规模标签信号开关（#454，ADR-0037：缺省 false/null＝关＝规模"
                + "标签不参与升档判定；true＝恢复标签映射判据——映射旋钮照旧生效。"
                + "仅 run 执行体行消费）")
        Boolean routingSizeLabelSignalEnabled) {
}
