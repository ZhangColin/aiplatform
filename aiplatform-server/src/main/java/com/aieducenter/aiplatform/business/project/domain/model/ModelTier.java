package com.aieducenter.aiplatform.business.project.domain.model;

/**
 * 模型档位＝语义键（ADR-0031，{@code prj_model_tiers} 一行）：运营短名
 * （flash/pro/doubao-flagship）指向实际模型名＋所属模型供应商。模型版本升级
 * （方舟 Model ID 带日期后缀、季度级更新）＝改一处指向、全部引用自动跟上；
 * 被智能体运营配置（三座 model_id＋路由强档）与边缘调用点引用，引用值＝
 * {@link #tierKey()}。
 *
 * @param tierKey     语义键（运营短名，引用腿）
 * @param providerKey 所属通道键（模型串前缀腿）
 * @param modelName   实际模型名（如 deepseek-v4-flash-ga-260731）
 * @param enabled     启用态；停用＝引用它的装配明确报错
 */
public record ModelTier(String tierKey, String providerKey, String modelName, boolean enabled) {
}
