package com.semgravio.aiplatform.base.agentscope;

import static io.agentscope.core.model.ModelProviderSupport.firstNonBlank;
import static io.agentscope.extensions.model.openai.OpenAIModelProviderSupport.applyAdvancedOptions;

import org.springframework.stereotype.Component;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Map;

/**
 * volcengine 前缀模型工厂注册（ADR-0031 通道语义：前缀即协议路由）——平台代码
 * 注册（SDK 公开 API {@link ModelRegistry#registerFactory}，用户工厂优先于内置
 * SPI provider、不改依赖库）；火山方舟是 OpenAI 兼容协议，工厂直构
 * {@link OpenAIChatModel}（默认 formatter——无需特化兼容层）。
 *
 * <p>凭证与地址全由<b>创建上下文</b>带入（装配链：档位行 → 通道行 →
 * {@link ModelChannel} → {@code AgentscopeHarnessAgentFactory#creationContextOf}）
 * ——baseUrl/apiKey 无本类缺省值、无环境变量回落：key 空＝明确报错（库为唯一
 * 正本，故障一眼可查）。enableThinking 非空 → 请求体 {@code thinking} 参数
 * （方舟 DeepSeek V4 系均标深度思考、不传参默认开/关未确证——装配层显式关，
 * 终值冒烟实测后定，#369）。</p>
 *
 * <p>注册幂等性：{@code registerFactory} newest-first、同行为重复注册无副作用
 * （Spring 上下文重建/测试复注册皆可）；SDK 前缀（deepseek 等）不经本类——走
 * 内置 provider，baseUrl/key 同由创建上下文注入覆盖默认。</p>
 */
@Component
public class VolcengineModelRegistrar {

    /** 供应商通道键＝模型串前缀（prj_model_providers 种子行）。 */
    public static final String PROVIDER_KEY = "volcengine";

    VolcengineModelRegistrar() {
        register();
    }

    /** 注册平台工厂（幂等；测试直调——Spring 上下文外手工注册同款）。 */
    public static void register() {
        ModelRegistry.registerFactory(PROVIDER_KEY + ":.+", VolcengineModelRegistrar::create);
    }

    static Model create(String modelId, ModelCreationContext context) {
        String modelName = modelId.substring(PROVIDER_KEY.length() + 1);
        String apiKey = context.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型供应商通道 " + PROVIDER_KEY
                    + " 未配置 apiKey（库为唯一正本，不回落环境变量）: " + modelId);
        }
        String baseUrl = firstNonBlank(context.getBaseUrl(), "");
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("模型供应商通道 " + PROVIDER_KEY
                    + " 缺 baseUrl（创建上下文未携带）: " + modelId);
        }
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .stream(context.getStream() != null ? context.getStream() : true)
                .baseUrl(baseUrl)
                .endpointPath(context.getEndpointPath())
                // 结构化输出走平台工具面（与 DeepSeekModelProvider 同口径：兼容
                // 通道不开原生 structured output）
                .nativeStructuredOutput(false)
                .contextWindowSize(ModelContextWindows.lookup(
                        modelName, ModelContextWindows.DEEPSEEK));
        GenerateOptions userOptions = context.component(GenerateOptions.class);
        GenerateOptions channelDefaults = thinkingOptions(context.getEnableThinking());
        applyAdvancedOptions(builder, context,
                GenerateOptions.mergeOptions(userOptions, channelDefaults));
        return builder.build();
    }

    /** thinking 开关 → 请求体参数（null＝装配层未表态，不注入——由上层保证显式值）。 */
    private static GenerateOptions thinkingOptions(Boolean enableThinking) {
        if (enableThinking == null) {
            return null;
        }
        return GenerateOptions.builder()
                .additionalBodyParam(
                        "thinking", Map.of("type", enableThinking ? "enabled" : "disabled"))
                .build();
    }
}
