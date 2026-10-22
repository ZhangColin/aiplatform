package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 边缘调用点档位命令（#366，PUT 全量语义）：请求体即该边缘点覆盖态终态——值面
 * 仅模型档位一件（边缘点无 systemPrompt/工具开关/路由策略语义，窄形拒绝误带）。
 * null／缺省／纯空白＝清空覆盖（装配回落 {@code AgentEdgePoint} 代码缺省档位键）。
 *
 * <p>档位键不做白名单校验——与三座全量写同口径（校验面即信任面；错值致调用失败
 * 经留痕写回可回滚；档位表即时校验属 #367 绑定校验）。</p>
 *
 * @param modelId 覆盖模型档位键（ADR-0031 语义键如 flash/pro；null＝清空回落代码缺省）
 */
public record EdgeModelUpdateCommand(
        @Schema(description = "覆盖模型档位键（如 pro；null/缺省/纯空白＝清空覆盖，"
                + "装配回落代码缺省 flash。不做白名单校验——错值致调用失败经留痕写回可回滚）",
                example = "pro")
        String modelId) {
}
