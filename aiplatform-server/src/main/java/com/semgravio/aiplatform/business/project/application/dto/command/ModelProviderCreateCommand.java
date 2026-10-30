package com.semgravio.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型供应商通道创建命令（#367，POST——键名随体，已存在即 409 PRJ_067）。
 * apiKey 收全量值（写面唯一明文入口）；null/空白＝暂不配置（通道行落空 key，
 * 装配按 PRJ_063 明确报错——备胎通道的合法形）。
 *
 * @param providerKey 键名＝协议路由前缀（如 volcengine/deepseek/未来新通道）
 * @param baseUrl     通道 baseUrl（必填；创建上下文注入 SDK）
 * @param apiKey      凭证全量值（null/空白＝暂不配置）
 * @param enabled     启用态（缺省 true）
 */
public record ModelProviderCreateCommand(
        @Schema(description = "通道键名＝协议路由前缀（模型串「前缀:实际模型名」的前缀腿；"
                + "OpenAI 兼容通道统一接，SDK 内置前缀走原生 provider）", example = "volcengine")
        String providerKey,
        @Schema(description = "通道 baseUrl（创建上下文注入 SDK，覆盖依赖库默认地址）",
                example = "https://ark.cn-beijing.volces.com/api/v3")
        String baseUrl,
        @Schema(description = "凭证全量值（写面唯一明文入口；null/空白＝暂不配置——装配按 "
                + "PRJ_063 明确报错不静默兜底）")
        String apiKey,
        @Schema(description = "启用态（缺省 true；停用＝引用该通道的装配明确报错）")
        Boolean enabled) {
}
