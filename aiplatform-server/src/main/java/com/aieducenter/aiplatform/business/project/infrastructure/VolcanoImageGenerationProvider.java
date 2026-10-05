package com.aieducenter.aiplatform.business.project.infrastructure;

import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.JsonNode;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.model.ImageTransfers;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;

/**
 * 火山方舟（Ark）Seedream 文生图适配器（#288 四家之一）：{@code POST /api/v3/
 * images/generations}，Bearer API Key——端点与响应同 OpenAI images 形（#288 随接
 * 核实：同形成立，差异在 size 语义（档位串 "2K"/"4K" 或任意 WxH，透传不翻译）与
 * 水印参数）。<b>水印保持供应商缺省（true）</b>：火山条款明文不得去除「AI 生成」
 * 标识（ADR-0026 审核与滥用口径），不传 {@code watermark:false}。单次一张
 * （sequential_image_generation 缺省 disabled）；回图 {@code data[0].url}（24h
 * 时效，即时转存），出图格式可由响应 {@code data[0].output_format} 补扩展名
 * （URL 无扩展名时）；审核拦截＝Input/Output 前缀的敏感内容错误码族，
 * <b>未成功输出的图片不计费</b>（模型价格页原文）。模型缺省
 * {@code doubao-seedream-5-0-260128}（Seedream 5.0，0.22 元/张；4.5 已标即将下线）。
 * key 落环境变量 ARK_API_KEY（.env.local，不进 git）。
 */
@Adapter(PortType.CLIENT)
public class VolcanoImageGenerationProvider extends OpenAiShapedImageProviderBase {

    public VolcanoImageGenerationProvider(
            @Value("${app.image-generation.volcano.base-url:https://ark.cn-beijing.volces.com}") String baseUrl,
            @Value("${app.image-generation.volcano.api-key:}") String apiKey,
            @Value("${app.image-generation.volcano.model:doubao-seedream-5-0-260128}") String model) {
        super(baseUrl, apiKey, model);
    }

    @Override
    public String providerKey() {
        return "volcano";
    }

    @Override
    String path() {
        return "/api/v3/images/generations";
    }

    @Override
    ImageProviderResult.Image imageOf(JsonNode root, JsonNode dataItem) {
        String url = dataItem.path("url").asText("");
        // URL 带真扩展名用之；无扩展名回落响应出图格式字段（volcano 缺省 jpeg），再缺＝png
        String extension = ImageTransfers.extensionFromUrl(url);
        if ("png".equals(extension) && !url.toLowerCase().endsWith(".png")) {
            extension = ImageTransfers.extensionOf(dataItem.path("output_format").asText(null));
        }
        return new ImageProviderResult.Image(url, null, extension);
    }
}
