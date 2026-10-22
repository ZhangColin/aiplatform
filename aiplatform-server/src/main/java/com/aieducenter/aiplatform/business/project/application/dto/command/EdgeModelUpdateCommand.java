package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 边缘调用点档位命令（#366，PUT 全量语义）：请求体即该边缘点覆盖态终态——值面
 * 仅模型档位一件（边缘点无 systemPrompt/工具开关/路由策略语义，窄形拒绝误带）。
 * null／缺省／纯空白＝清空覆盖（装配回落 {@code AgentEdgePoint} 代码缺省档位键）。
 *
 * <p>档位键即时校验与三座同口径（#367）：非空须在模型档位面有行且启用
 * （PRJ_074/075——错值写不进去，不再靠留痕回滚兜底）。</p>
 *
 * @param modelId 覆盖模型档位键（ADR-0031 语义键如 flash/pro；null＝清空回落代码缺省）
 */
public record EdgeModelUpdateCommand(
        @Schema(description = "覆盖模型档位键（如 pro；null/缺省/纯空白＝清空覆盖，"
                + "装配回落代码缺省 flash。即时校验：须在模型档位面有行且启用"
                + "（PRJ_074/075））",
                example = "pro")
        String modelId) {
}
