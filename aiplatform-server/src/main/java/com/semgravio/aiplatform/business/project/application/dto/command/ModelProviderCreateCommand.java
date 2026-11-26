package com.semgravio.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型供应商通道创建命令（#367，POST——键名随体，已存在即 409 PRJ_067）。
 * apiKey 收全量值（写面唯一明文入口）；null/空白＝暂不配置（通道行落空 key，
 * 装配按 PRJ_063 明确报错——备胎通道的合法形）。
 *
 * @param providerKey 键名＝协议路由前缀（如 deepseek/openai/未来新通道）
 * @param vendor      归一供应商（#559/T5 受控值：小写字母/数字/连字符——受控下拉
 *                    ＋可新建，防错字分裂统计与单价匹配；缺省＝providerKey 同值
 *                    ——V79 历史回填语义，同供应商多账号行显式同填如 deepseek）
 * @param baseUrl     通道 baseUrl（必填；创建上下文注入 SDK）
 * @param apiKey      凭证全量值（null/空白＝暂不配置）
 * @param enabled     启用态（缺省 true）
 */
public record ModelProviderCreateCommand(
        @Schema(description = "通道键名＝协议路由前缀（模型串「前缀:实际模型名」的前缀腿；"
                + "OpenAI 兼容通道统一接，SDK 内置前缀走原生 provider）", example = "deepseek")
        String providerKey,
        @Schema(description = "归一供应商（受控值：小写字母/数字/连字符 1-50 位；缺省＝"
                + "providerKey 同值。按模型统计与单价/峰谷匹配的维度腿——同供应商"
                + "多账号行显式同填，如 deepseek/deepseek-2 同填 deepseek）",
                example = "deepseek")
        String vendor,
        @Schema(description = "通道 baseUrl（创建上下文注入 SDK，覆盖依赖库默认地址）",
                example = "https://api.deepseek.com")
        String baseUrl,
        @Schema(description = "凭证全量值（写面唯一明文入口；null/空白＝暂不配置——装配按 "
                + "PRJ_063 明确报错不静默兜底）")
        String apiKey,
        @Schema(description = "启用态（缺省 true；停用＝引用该通道的装配明确报错）")
        Boolean enabled) {
}
