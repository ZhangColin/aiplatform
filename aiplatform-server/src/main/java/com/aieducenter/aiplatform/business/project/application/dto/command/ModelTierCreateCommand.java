package com.aieducenter.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型档位创建命令（#367，POST——语义键随体，已存在即 409 PRJ_068；所属通道
 * 必须已有行，PRJ_071）。语义键即引用腿：智能体运营配置与路由强档绑定的就是它
 * ——建行后即可被绑定（即时校验放行）。
 *
 * @param tierKey     语义键（运营短名，如 flash/pro/doubao-flagship）
 * @param providerKey 所属通道键（必须已存在——先建通道行再挂档位）
 * @param modelName   实际模型名（如 deepseek-v4-flash-ga-260731）
 * @param displayName 运营显示名（可空＝回落语义键）
 * @param enabled     启用态（缺省 true）
 */
public record ModelTierCreateCommand(
        @Schema(description = "档位语义键（引用腿——三座/边缘点/路由强档绑定的值）",
                example = "doubao-flagship")
        String tierKey,
        @Schema(description = "所属通道键（必须已存在——先建通道行再挂档位）",
                example = "volcengine")
        String providerKey,
        @Schema(description = "实际模型名（通道侧 Model ID，方舟名带日期后缀——版本升级"
                + "改此处指向、全部引用自动跟上）", example = "doubao-seed-2-1-pro-260915")
        String modelName,
        @Schema(description = "运营显示名（后台绑定下拉的人话标签；可空＝回落语义键）",
                example = "豆包旗舰")
        String displayName,
        @Schema(description = "启用态（缺省 true；停用＝既有绑定装配报错、新绑定被即时校验拒绝）")
        Boolean enabled) {
}
