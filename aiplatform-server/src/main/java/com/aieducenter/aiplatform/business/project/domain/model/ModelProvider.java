package com.aieducenter.aiplatform.business.project.domain.model;

/**
 * 模型供应商＝接入通道（ADR-0031，{@code prj_model_providers} 一行）：键名、
 * baseUrl、apiKey 与启用态。键名同时是协议路由前缀——对话轨道模型串
 * 「前缀:实际模型名」的前缀即 {@link #providerKey()}（OpenAI 兼容通道由平台
 * 代码注册模型工厂，SDK 内置前缀走原生 provider）。
 *
 * <p>apiKey 落库为<b>唯一正本</b>（无环境变量回落——空即装配明确报错）；换通道、
 * 换 key、换 baseUrl 皆不发版不重启，下一轮命令构建即生效（进行中 run 不定格）。
 * 接口回显一律掩码（后台维护面 #367）。</p>
 *
 * @param providerKey 键名＝协议路由前缀（volcengine/deepseek）
 * @param baseUrl     通道 baseUrl（创建上下文注入 SDK，覆盖依赖库默认地址）
 * @param apiKey      凭证唯一正本；null/空＝未配置
 * @param enabled     启用态；停用＝引用该通道的装配明确报错（不静默换道）
 */
public record ModelProvider(String providerKey, String baseUrl, String apiKey, boolean enabled) {
}
