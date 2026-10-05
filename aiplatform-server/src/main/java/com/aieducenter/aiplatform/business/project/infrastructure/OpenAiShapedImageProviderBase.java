package com.aieducenter.aiplatform.business.project.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationProvider;
import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationRequest;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;

/**
 * OpenAI images 同形端点的出图适配器骨架（#288）：四家供应商（智谱／火山方舟／
 * OpenAI／阿里 DashScope 兼容模式）的文生图端点均为 {@code POST .../images/
 * generations}＋Bearer key＋{@code data[0]} 回图的同形协议（差异与证据见
 * docs/research/2026-10-03-image-gen-mechanism-facts.md §4 与 #288 随接核实），
 * 字面相同的部分收拢于此——HTTP 骨架（POST＋超时＋Bearer）、请求体（model/prompt/
 * size 三件，可空 size 不上送＝供应商缺省）、降级契约（非 200／坏响应／连接异常
 * 一律 Failed 带如实理由，不抛错不炸——对齐 {@link BochaWebSearchProvider}）。
 * 供数方 specifics 留抽象位：路径、响应项→图（url/b64 两形各家不同）、读超时。
 * 不为「将来统一的图片 API 客户端」预铸结构——各家分岔（水印/质量/批量参数）
 * 时子类自带，骨架不扩。
 */
abstract class OpenAiShapedImageProviderBase implements ImageGenerationProvider {

    /** 出图读超时上限（秒）：文生图生成段远慢于对话（智谱 hd ~20s、高质量档分钟内），
     *  120s 是单次调用的兜底上限；超时走调用方有限重试。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    protected OpenAiShapedImageProviderBase(String baseUrl, String apiKey, String model) {
        // 归一化：容忍配置带尾斜杠，避免拼出 //api/...
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** 端点路径（含前导斜杠，拼在 base-url 后，如 {@code /api/paas/v4/images/generations}）。 */
    abstract String path();

    /** 响应 → 一张图：{@code data[0]} 取图（url 形回 URL、b64 形回 base64），
     *  扩展名提示各家自推（URL 路径段／响应顶层出图格式字段）。 */
    abstract ImageProviderResult.Image imageOf(JsonNode root, JsonNode dataItem);

    /** 本适配器上报的模型标识（计量匹配键——配置什么模型就计什么）。 */
    protected final String model() {
        return model;
    }

    @Override
    public ImageProviderResult generate(ImageGenerationRequest request) {
        Map<String, String> body = new HashMap<>();
        body.put("model", model);
        body.put("prompt", request.prompt());
        if (request.size() != null && !request.size().isBlank()) {
            body.put("size", request.size());
        }
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + path()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)))
                    .build();
        }
        catch (Exception e) {
            return failed("请求构造异常：" + e.getMessage());
        }
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e) {
            return failed("连接异常：" + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("被中断");
        }
        if (response.statusCode() != 200) {
            return failed("HTTP " + response.statusCode() + "：" + errorMessage(response.body()));
        }
        return parse(response.body());
    }

    /** 响应解析：坏响应（非 JSON／缺 data）→ 失败理由；{@code data[0]} 空（生成零张，
     *  含审核拦截形）→ 失败理由如实带响应线索。 */
    private ImageProviderResult parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        }
        catch (IOException e) {
            return failed("响应解析异常：" + e.getMessage());
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return failed("响应未含生成图（data 空）"
                    + errorMessage(body));
        }
        return new ImageProviderResult.Generated(model, imageOf(root, data.get(0)));
    }

    /** 错误消息提取（各家错误体同形 {@code error.message}，缺则原样截断回显）。 */
    private String errorMessage(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            if (!error.isMissingNode()) {
                return error.path("message").asText("") + "（" + error.path("code").asText("") + "）";
            }
        }
        catch (IOException ignored) {
            // 非 JSON 错误体：原样截断回显
        }
        return body == null || body.isBlank() ? "无响应体" : body.substring(0, Math.min(body.length(), 200));
    }

    /** 统一失败理由包装（供数方无关的如实报错口径，对齐博查）。 */
    static ImageProviderResult.Failed failed(String reason) {
        return new ImageProviderResult.Failed("出图失败（" + reason + "）");
    }
}
