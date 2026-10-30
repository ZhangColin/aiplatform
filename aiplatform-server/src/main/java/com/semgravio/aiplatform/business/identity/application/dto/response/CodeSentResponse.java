package com.semgravio.aiplatform.business.identity.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 发码回执（#327 M01）：有效期回显——弹窗重发倒计时与「码多久内有效」提示的
 * 取数位（值来自配置，非硬编码）。
 *
 * @param expiresInSeconds 验证码有效期（秒）
 */
@Schema(description = "发码回执")
public record CodeSentResponse(
        @Schema(description = "验证码有效期（秒）", example = "300")
        long expiresInSeconds
) {
}
