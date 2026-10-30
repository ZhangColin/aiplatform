package com.semgravio.aiplatform.business.identity.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 验码登录命令（#327 M01）：目标与发码同一输入位（同一归一形态寻址在途码）；
 * 验过即登录（不存在即静默建账户）。目标与码任一不符统一 IDN_008（不区分错环节）。
 *
 * @param target 收码目标（与发码同输入）
 * @param code   六位数字验证码
 */
@Schema(description = "验码登录命令")
public record VerifyCodeLoginCommand(
        @Schema(description = "收码目标（手机号/邮箱）", example = "user@example.com")
        String target,

        @Schema(description = "验证码（六位数字）", example = "123456")
        String code
) {
}
