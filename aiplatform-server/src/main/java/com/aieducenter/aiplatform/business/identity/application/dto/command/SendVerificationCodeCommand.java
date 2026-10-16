package com.aieducenter.aiplatform.business.identity.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 发码命令（#327 M01）：手机号/邮箱单输入——通道按形态判定（含 @＝邮箱、纯数字
 * 可带前缀加号＝手机号），归一与合法性由 VerificationTarget 裁决（IDN_005）。
 *
 * @param target 收码目标（手机号/邮箱原样输入）
 */
@Schema(description = "发码命令")
public record SendVerificationCodeCommand(
        @Schema(description = "收码目标（手机号/邮箱）", example = "user@example.com")
        String target
) {
}
