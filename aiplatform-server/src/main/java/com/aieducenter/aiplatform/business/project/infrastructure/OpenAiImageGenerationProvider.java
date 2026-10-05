package com.aieducenter.aiplatform.business.project.infrastructure;

import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.JsonNode;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.model.ImageTransfers;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;

/**
 * OpenAI GPT Image 文生图适配器（#288 四家之一，国际质量档——key 用户自备、区域
 * 合规责任归用户侧运营，ADR-0026）：{@code POST /v1/images/generations}，Bearer
 * key。GPT image 系<b>只回 base64</b>（{@code data[0].b64_json}，无 URL 形态——
 * response_format 已弃用），出图格式取响应 {@code output_format}（缺省 png）；
 * moderation 拦截＝HTTP 400＋{@code error.code=content_policy_violation}（拦截请求
 * 是否计费官方无明文——#288 留档未证实）。模型缺省 {@code gpt-image-2.5-sunburst}
 * （现役最强档；flare 为快档同价，gpt-image-1 已定 2026-10-23 关停勿再接）。
 * key 落环境变量 OPENAI_IMAGE_API_KEY（.env.local，不进 git——与平台对话模型栈
 * 无涉，独立键不复用）。
 */
@Adapter(PortType.CLIENT)
public class OpenAiImageGenerationProvider extends OpenAiShapedImageProviderBase {

    public OpenAiImageGenerationProvider(
            @Value("${app.image-generation.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${app.image-generation.openai.api-key:}") String apiKey,
            @Value("${app.image-generation.openai.model:gpt-image-2.5-sunburst}") String model) {
        super(baseUrl, apiKey, model);
    }

    @Override
    public String providerKey() {
        return "openai";
    }

    @Override
    String path() {
        return "/v1/images/generations";
    }

    @Override
    ImageProviderResult.Image imageOf(JsonNode root, JsonNode dataItem) {
        // b64 形无 URL 可推扩展名：取响应顶层出图格式字段（GPT image 必回，缺省 png）
        return new ImageProviderResult.Image(null, dataItem.path("b64_json").asText(),
                ImageTransfers.extensionOf(root.path("output_format").asText(null)));
    }
}
