package com.semgravio.aiplatform.business.project.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.semgravio.aiplatform.business.project.domain.port.ModelCatalog;
import com.semgravio.aiplatform.business.project.domain.port.ModelCatalogFetcher;

/**
 * OpenAI 兼容模型清单拉取器（#368 手动同步的唯一实现）：{@code GET {baseUrl}/models}，
 * Bearer key。响应形状以方舟端点 2026-10-23 实测为准（OpenAI 兼容 {@code data[]}
 * 条目：id 必有，name/status/domain 可缺——方舟活跃模型无 status 字段、Shutdown/
 * Retiring＝下架/退场中）。形制照 {@code BochaWebSearchProvider}：降级契约＝
 * 非 200 / 坏响应（非 JSON、缺 data）/ 连接异常一律 {@link ModelCatalog.Failed}
 * 带如实理由，不抛错不炸——调用方（后台拉取用例）转明确错误信封，不静默。
 */
@Adapter(PortType.CLIENT)
public class OpenAiCompatibleModelCatalogFetcher implements ModelCatalogFetcher {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ModelCatalog fetch(String baseUrl, String apiKey) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(stripTrailingSlash(baseUrl) + "/models"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
        } catch (Exception e) {
            return failed("请求构造异常：" + e.getMessage());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return failed("连接异常：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("被中断");
        }
        if (response.statusCode() != 200) {
            return failed("HTTP 状态码 " + response.statusCode());
        }
        return parse(response.body());
    }

    /** 响应解析：非 JSON / 缺 data 数组 → 失败理由；否则逐条映射（字段可缺＝null，
     *  如实转述——对照入库的判断归调用方）。 */
    private ModelCatalog parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return failed("响应解析异常：" + e.getMessage());
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return failed("响应缺少结果字段（data）");
        }
        List<ModelCatalog.RemoteModel> models = new ArrayList<>();
        for (JsonNode node : data) {
            JsonNode id = node.path("id");
            // id 是入库对照的唯一键（缺 id 的条目无从对照）——缺失/空白＝坏响应，
            // 明确报错不静默（放行成 null-id 条目会在对照排序面炸 500）
            if (!id.isTextual() || id.asText().isBlank()) {
                return failed("响应缺少条目必填字段（data[].id）");
            }
            models.add(new ModelCatalog.RemoteModel(
                    id.asText(),
                    nullableText(node, "name"),
                    nullableText(node, "status"),
                    nullableText(node, "domain")));
        }
        return new ModelCatalog.Fetched(models);
    }

    /** 字段可缺转述：缺失/null JSON 值 → null（非空串占位）。 */
    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    /** 统一失败理由透传（如实报错口径——上游不可用不静默；语境前缀由调用方错误信封给）。 */
    private static ModelCatalog.Failed failed(String reason) {
        return new ModelCatalog.Failed(reason);
    }

    /** 尾斜杠归一（容忍配置形，避免拼出 //models）。 */
    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }
}
