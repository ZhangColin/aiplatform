package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 智能体配置覆盖态命令（#251，PUT 全量语义）：请求体即该智能体覆盖态终态——
 * 值面八件全量替换，与槽位指派「清单即终态」同款形制。覆盖两件 null／缺省／
 * 纯空白＝清空覆盖（装配回落枚举默认——GET 的 defaultSystemPrompt/defaultModelId
 * 即回落落点）；工具开关两件缺省 true＝开。
 *
 * <p>档位名不做白名单校验（换模型不发版是本面目的——校验面即信任面：签名面
 * 担保调用应用，操作者头明示信任；错值致新会话失败时经留痕写回即可回滚）。
 * 工具开关已生效装配（#252：关即退出槽位装配面、开即回归——窄幅写口见
 * {@code PUT /agent-tools/{toolName}}）。</p>
 *
 * <p><b>模型路由策略四件（#354）</b>：路由开关缺省 true＝开（路由判定优先；
 * 关＝钉死位——人工档恒生效）；强模型档与两阈值 null＝清空回落代码缺省。协同
 * 口径：路由判定优先、人工覆盖兜底——信号（重试失败/文件面/片复杂度）升强档
 * 优先，无信号回落人工档（modelId 覆盖 → 枚举缺省——覆盖即基础档）。
 * 仅 run 执行体（executor）消费，其余智能体行同列无消费。</p>
 *
 * @param systemPrompt      覆盖 systemPrompt（null＝清空回落枚举默认）
 * @param modelId           覆盖模型档位裸名（null＝清空回落枚举默认；路由开时
 *                          为兜底基础档——信号升档可越过）
 * @param webSearchEnabled  联网搜索开关（缺省 true；关即退出装配面）
 * @param fetchUrlEnabled   网页抓取开关（同上；缺省 true）
 * @param routingEnabled    模型路由开关（缺省 true＝开；false＝既有人工定档口径）
 * @param routingStrongModelId 强模型档裸名（null/缺省/纯空白＝清空回落代码缺省
 *                          deepseek-v4-pro；不做白名单校验同 modelId）
 * @param routingFileCountThreshold 文件面阈值（工作区已跟踪文件数 ≥ 此值升强档；
 *                          null＝清空回落代码缺省 60）
 * @param routingSliceLengthThreshold 片复杂度阈值（切片描述字数 ≥ 此值升强档；
 *                          null＝清空回落代码缺省 20）
 */
public record AgentConfigUpdateCommand(
        @Schema(description = "覆盖 systemPrompt 全文（PUT 全量语义：null/缺省/纯空白＝"
                + "清空覆盖，装配回落枚举默认——GET 回执的 defaultSystemPrompt 即回落落点）")
        String systemPrompt,
        @Schema(description = "覆盖模型档位裸名（如 deepseek-v4-pro；null/缺省/纯空白＝"
                + "清空覆盖回落枚举默认。不做白名单校验——错值致新会话失败经留痕写回可回滚）",
                example = "deepseek-v4-pro")
        String modelId,
        @Schema(description = "增强工具开关：联网搜索（缺省 true＝开；#252 起生效装配——"
                + "关即退出槽位装配面、开即回归，窄幅写口 PUT /agent-tools/{toolName}）")
        Boolean webSearchEnabled,
        @Schema(description = "增强工具开关：网页抓取（缺省 true＝开；生效语义同 webSearchEnabled）")
        Boolean fetchUrlEnabled,
        @Schema(description = "模型路由开关（#354：缺省 true＝开——路由判定优先；"
                + "false＝既有人工定档口径。仅 run 执行体消费）")
        Boolean routingEnabled,
        @Schema(description = "强模型档裸名（#354 路由升档落点；null/缺省/纯空白＝清空"
                + "回落代码缺省 deepseek-v4-pro。不做白名单校验同 modelId）",
                example = "deepseek-v4-pro")
        String routingStrongModelId,
        @Schema(description = "文件面阈值（#354：工作区已跟踪文件数 ≥ 此值升强档；"
                + "null＝清空回落代码缺省 60）", example = "60")
        Integer routingFileCountThreshold,
        @Schema(description = "片复杂度阈值（#354：切片描述字数 ≥ 此值升强档；"
                + "null＝清空回落代码缺省 20）", example = "20")
        Integer routingSliceLengthThreshold) {
}
