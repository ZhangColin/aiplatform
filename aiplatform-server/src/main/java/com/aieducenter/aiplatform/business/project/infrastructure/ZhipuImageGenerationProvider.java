package com.aieducenter.aiplatform.business.project.infrastructure;

import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.JsonNode;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.model.ImageTransfers;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;

/**
 * 智谱（BigModel）文生图适配器（#288 四家之一）：{@code POST /api/paas/v4/images/
 * generations}，Bearer key 直传（无需 JWT——官方 HTTP 调用页「API Key 鉴权」路线）。
 * 单次调用固定一张（无 n 参数）；回图走 {@code data[0].url}（URL 时效 30 天，
 * 平台即时转存）；审核拦截＝HTTP 400＋错误码 1301（响应体不再同步生成结果，
 * 被拦截请求是否计费官方无明文——#288 留档未证实，真 key 冒烟时实测核对）。
 * 模型缺省 {@code glm-image}（0.10 元/张，中文文字第一梯队；cogview-4-250304
 * 0.06 元/张为低价档）。key 落环境变量 ZHIPU_API_KEY（.env.local，不进 git）。
 */
@Adapter(PortType.CLIENT)
public class ZhipuImageGenerationProvider extends OpenAiShapedImageProviderBase {

    public ZhipuImageGenerationProvider(
            @Value("${app.image-generation.zhipu.base-url:https://open.bigmodel.cn}") String baseUrl,
            @Value("${app.image-generation.zhipu.api-key:}") String apiKey,
            @Value("${app.image-generation.zhipu.model:glm-image}") String model) {
        super(baseUrl, apiKey, model);
    }

    @Override
    public String providerKey() {
        return "zhipu";
    }

    @Override
    String path() {
        return "/api/paas/v4/images/generations";
    }

    @Override
    ImageProviderResult.Image imageOf(JsonNode root, JsonNode dataItem) {
        String url = dataItem.path("url").asText("");
        return new ImageProviderResult.Image(url, null, ImageTransfers.extensionFromUrl(url));
    }
}
