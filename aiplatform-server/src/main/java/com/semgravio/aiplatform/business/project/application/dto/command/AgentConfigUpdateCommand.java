package com.semgravio.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 智能体配置覆盖态命令（#251，PUT 全量语义）：请求体即该智能体覆盖态终态——
 * 值面全量替换，与槽位指派「清单即终态」同款形制。覆盖两件 null／缺省／
 * 纯空白＝清空覆盖（装配回落枚举默认——GET 的 defaultSystemPrompt/defaultModelConfigKey
 * 即回落落点）；工具开关两件缺省 true＝开。
 *
 * <p>配置键即时校验（#367，ADR-0031）：modelConfigKey 非空时必须在模型配置面
 * （/model-configs）有行且启用——错值写不进去（PRJ_074/075，白名单从配置表来，
 * 不再靠留痕回滚兜底）；null＝清空回落缺省不校验。工具开关已生效装配（#252：
 * 关即退出槽位装配面、开即回归——窄幅写口见 {@code PUT /agent-tools/{toolName}}）。</p>
 *
 * <p><b>审查与收口组配置面已随 #605 退役</b>：验证面两件（#424）、终验修复预算
 * （#427）、收口闸开关（#433）与基座 lint 阻断开关（#541）随验证子智能体／整轨
 * 终验／收口三开关／基座 lint 扫描整组拆除（ADR-0055）。<b>模型路由策略与结构性
 * 信号开关已随 #606 退役</b>（ADR-0055 #583 死旋钮；V88 删列）——本命令只余仍
 * 在消费的覆盖态与工具开关。</p>
 *
 * @param systemPrompt      覆盖 systemPrompt（null＝清空回落枚举默认）
 * @param modelConfigKey           覆盖模型配置键（ADR-0031 语义键如 flash/pro；null＝清空
 *                          回落枚举默认）
 * @param webSearchEnabled  联网搜索开关（缺省 true；关即退出装配面）
 * @param fetchUrlEnabled   网页抓取开关（同上；缺省 true）
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
        Boolean fetchUrlEnabled) {
}
