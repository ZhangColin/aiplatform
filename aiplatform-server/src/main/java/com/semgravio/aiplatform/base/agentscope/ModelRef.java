package com.semgravio.aiplatform.base.agentscope;

/**
 * 智能体模型引用：解析 {@code provider:modelId} 模型串（形状校验单点）。
 *
 * <p>ADR-0031 起前缀即协议路由——供应商通道（{@code prj_model_providers}）的
 * 键名即前缀（volcengine/deepseek…），开放词表：前缀<b>合法性</b>由装配链保障
 * （档位解析 {@code ModelTierAppService} 按通道行拼串、SDK {@code ModelRegistry}
 * 按前缀路由到平台注册工厂或内置 provider），本类只守形状（provider 与 modelId
 * 皆非空）。计量归属按前缀归因（{@code UsageEvent.provider} 同词表）。</p>
 */
public record ModelRef(String provider, String modelId) {

    public static ModelRef parse(String modelString) {
        if (modelString == null || modelString.isBlank()) {
            throw invalid(modelString);
        }
        String[] parts = modelString.trim().split(":");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw invalid(modelString);
        }
        return new ModelRef(parts[0], parts[1]);
    }

    private static IllegalArgumentException invalid(String modelString) {
        return new IllegalArgumentException(
                "非法模型串（应为 provider:model，前缀＝供应商通道键）: " + modelString);
    }

    /** 还原 {@code provider:modelId} 完整串 */
    public String toModelString() {
        return provider + ":" + modelId;
    }
}
