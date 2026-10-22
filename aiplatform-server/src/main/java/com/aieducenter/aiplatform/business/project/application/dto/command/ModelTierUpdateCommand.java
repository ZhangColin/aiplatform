package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型档位更新命令（#367，PUT 全量语义）：请求体即档位行终态——providerKey/
 * modelName/displayName 全量替换（displayName 可空＝清显示名回落语义键）；enabled
 * 缺省＝<b>保持现值</b>（终态字段不因缺省翻转——停用档换模型名/换通道不被意外
 * 复启，显式 true/false 才翻转；与 agent-configs 开关口径之别同通道面 javadoc）。
 * 换通道、升模型版本、停启用皆此一处改指向。
 *
 * @param providerKey 所属通道键（必填，必须已存在）
 * @param modelName   实际模型名（必填）
 * @param displayName 运营显示名（null/空白＝清空回落语义键）
 * @param enabled     缺省＝保持现值；显式 true/false 才翻转
 */
public record ModelTierUpdateCommand(
        @Schema(description = "所属通道键（终态全量替换，必填且须已存在）",
                example = "volcengine")
        String providerKey,
        @Schema(description = "实际模型名（终态全量替换，必填）",
                example = "deepseek-v4-flash-ga-261031")
        String modelName,
        @Schema(description = "运营显示名（null/空白＝清空，回落语义键展示）", example = "快模型档")
        String displayName,
        @Schema(description = "启用态（缺省＝保持现值，显式 true/false 才翻转；停用＝既有"
                + "绑定装配报错 PRJ_061、新绑定被即时校验拒绝 PRJ_075）")
        Boolean enabled) {
}
