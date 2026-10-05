package com.aieducenter.aiplatform.business.project.infrastructure;

import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.JsonNode;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.model.ImageTransfers;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;

/**
 * 阿里百炼（DashScope）qwen-image 文生图适配器（#288 四家之一）：走 OpenAI 兼容
 * 同步端点 {@code POST /compatible-mode/v1/images/generations}（qwen-image-3.0 起
 * 可用——官方标注仅同步非流式；经典 qwen-image 系不支持兼容模式，故本适配器
 * 面＝3.0/2.1-pro 系模型配置），Bearer key。回图 {@code data[0].url}（24h 时效，
 * 平台即时转存；response_format=b64_json 被忽略仍回 URL——官方文档原文）；
 * 审核拦截＝HTTP 400＋{@code code=DataInspectionFailed}（<b>请求失败不计费</b>
 * ——百炼价格页原文）。模型缺省 {@code qwen-image-3.0}（输出 0.18 元/张、中文
 * 文字第一梯队；size 兼容模式用字母 x 分隔，与 DashScope 原生协议的星号不同——
 * 透传不翻译）。key 落环境变量 DASHSCOPE_API_KEY（.env.local，不进 git）。
 */
@Adapter(PortType.CLIENT)
public class DashScopeImageGenerationProvider extends OpenAiShapedImageProviderBase {

    public DashScopeImageGenerationProvider(
            @Value("${app.image-generation.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${app.image-generation.dashscope.api-key:}") String apiKey,
            @Value("${app.image-generation.dashscope.model:qwen-image-3.0}") String model) {
        super(baseUrl, apiKey, model);
    }

    @Override
    public String providerKey() {
        return "dashscope";
    }

    @Override
    String path() {
        return "/compatible-mode/v1/images/generations";
    }

    @Override
    ImageProviderResult.Image imageOf(JsonNode root, JsonNode dataItem) {
        String url = dataItem.path("url").asText("");
        return new ImageProviderResult.Image(url, null, ImageTransfers.extensionFromUrl(url));
    }
}
