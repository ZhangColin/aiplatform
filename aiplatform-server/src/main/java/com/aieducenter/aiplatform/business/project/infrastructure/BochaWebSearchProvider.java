package com.aieducenter.aiplatform.business.project.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.port.SearchResult;
import com.aieducenter.aiplatform.business.project.domain.port.WebSearchProvider;

/**
 * 博查（Bocha）搜索供数器（#215 调研闭环的首个适配器）：POST {@code /v1/web-search}，
 * Bearer key。请求带 {@code summary:true}（长文本摘要更利调研）与
 * {@code freshness:noLimit}（官方推荐，算法自优化时间范围）。响应兼容 Bing Search
 * API 形状 {@code data.webPages.value[]}，取 name/url/snippet/summary 映射为供数器
 * 无关的 {@link SearchResult.Hit}（title/url/snippet，摘要取 summary、缺则 snippet）
 * ——接口不渗博查 specifics。
 *
 * <p>经平台配置实例化：base-url/key 由 {@code @Value} 注入（key 只落环境变量
 * BOCHA_API_KEY，无硬编码凭证）。换供数方 = 加适配器 + 换 base-url/key 配置，主链路
 * （端口 + 工具）零改动；运行时按「供应商类型」选适配器的开关在第二家接入时再立
 * （备案+触发器，现仅一家）。降级契约：非 200 / 业务错误码 / 坏响应（含缺结果字段）
 * / 连接异常一律 {@link SearchResult.Failed} 带如实理由，不抛错不炸。</p>
 */
@Adapter(PortType.CLIENT)
public class BochaWebSearchProvider implements WebSearchProvider {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BochaWebSearchProvider(
            @Value("${app.web-search.base-url:https://api.bocha.cn}") String baseUrl,
            @Value("${app.web-search.api-key:}") String apiKey) {
        // 归一化：容忍配置带尾斜杠，避免拼出 //v1/web-search
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public SearchResult search(String query) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/web-search"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of(
                                    "query", query,
                                    "summary", true,
                                    "freshness", "noLimit"))))
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

    /** 响应解析：业务错误码 / 坏响应（非 JSON、缺结果字段）→ 失败理由；否则逐条映射
     *  webPages.value（空数组 = 真零结果，不算坏响应）。 */
    private SearchResult parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return failed("响应解析异常：" + e.getMessage());
        }
        JsonNode code = root.get("code");
        if (code != null && code.asInt() != 200) {
            String msg = root.path("msg").asText(null);
            return failed("错误码 " + code.asInt()
                    + (msg == null || msg.isBlank() ? "" : "：" + msg));
        }
        JsonNode value = root.path("data").path("webPages").path("value");
        if (!value.isArray()) {
            return failed("响应缺少结果字段（data.webPages.value）");
        }
        List<SearchResult.Hit> hits = new ArrayList<>();
        for (JsonNode node : value) {
            String summary = node.path("summary").asText("");
            String snippet = node.path("snippet").asText("");
            hits.add(new SearchResult.Hit(
                    node.path("name").asText(""),
                    node.path("url").asText(""),
                    summary.isBlank() ? snippet : summary));
        }
        return new SearchResult.Results(hits);
    }

    /** 统一失败理由包装（供数方无关的如实报错口径）。 */
    private static SearchResult.Failed failed(String reason) {
        return new SearchResult.Failed("搜索失败（" + reason + "）");
    }
}
