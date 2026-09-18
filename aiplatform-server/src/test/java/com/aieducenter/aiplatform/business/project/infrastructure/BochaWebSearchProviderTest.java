package com.aieducenter.aiplatform.business.project.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aieducenter.aiplatform.business.project.domain.port.SearchResult;

/**
 * 博查搜索供数器单测（#215 调研闭环，形制照 FastembedEmbeddingClientTest）：JDK
 * 内置 HttpServer 随机端口假服务，零新增测试依赖。请求契约（POST /v1/web-search、
 * Bearer key、JSON 体含 query/summary）与降级契约（非 200 / 坏响应 / 连接拒绝 →
 * 失败理由不抛错）。
 */
class BochaWebSearchProviderTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private BochaWebSearchProvider provider() {
        return new BochaWebSearchProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
    }

    // ---------- 正常结果 ----------

    @Test
    void given_results_when_search_then_posts_web_search_and_maps_hits() throws Exception {
        respond(200, """
                {"code":200,"data":{"webPages":{"value":[
                  {"name":"Lovable","url":"https://lovable.dev","snippet":"短摘要",
                   "summary":"Lovable 是 AI 建站平台的长摘要"},
                  {"name":"Replit","url":"https://replit.com","snippet":"","summary":""}
                ]}}}
                """);

        SearchResult.Results results = (SearchResult.Results) provider().search("AI 建站平台");

        // 请求契约：POST /v1/web-search，Bearer key，JSON 体含 query/summary/freshness
        // （Map 序列化键序不稳，逐字段断言不锁序）
        assertThat(requests).hasSize(1);
        String request = requests.get(0);
        assertThat(request).startsWith("POST /v1/web-search auth=Bearer test-key body=");
        JsonNode body = new ObjectMapper()
                .readTree(request.substring(request.indexOf("body=") + "body=".length()));
        assertThat(body.get("query").asText()).isEqualTo("AI 建站平台");
        assertThat(body.get("summary").asBoolean()).isTrue();
        assertThat(body.get("freshness").asText()).isEqualTo("noLimit");
        assertThat(results.hits()).hasSize(2);
        SearchResult.Hit first = results.hits().get(0);
        assertThat(first.title()).isEqualTo("Lovable");
        assertThat(first.url()).isEqualTo("https://lovable.dev");
        // 摘要取 summary、缺则回退 snippet（供数器无关的 title/url/snippet 契约面）
        assertThat(first.snippet()).isEqualTo("Lovable 是 AI 建站平台的长摘要");
        assertThat(results.hits().get(1).snippet()).isEmpty();
    }

    @Test
    void given_empty_value_when_search_then_empty_hits() {
        respond(200, "{\"code\":200,\"data\":{\"webPages\":{\"value\":[]}}}");

        assertThat(((SearchResult.Results) provider().search("无结果")).hits()).isEmpty();
    }

    // ---------- 降级契约（非 200 / 坏响应 / 连接拒绝 → 失败理由不抛错） ----------

    @Test
    void given_non_200_when_search_then_failed_with_status() {
        respond(401, "{\"code\":401,\"msg\":\"Invalid API KEY\"}");

        SearchResult result = provider().search("查询");

        assertThat(result).isInstanceOf(SearchResult.Failed.class);
        assertThat(((SearchResult.Failed) result).reason()).contains("401");
    }

    @Test
    void given_malformed_body_when_search_then_failed_parse() {
        respond(200, "not-json");

        SearchResult result = provider().search("查询");

        assertThat(result).isInstanceOf(SearchResult.Failed.class);
        assertThat(((SearchResult.Failed) result).reason()).contains("响应解析异常");
    }

    @Test
    void given_valid_json_missing_web_pages_when_search_then_failed_missing_field() {
        // 200 + 合法 JSON 但缺结果字段 = 坏响应，不是「真零结果」
        respond(200, "{\"code\":200,\"data\":{}}");

        SearchResult result = provider().search("查询");

        assertThat(result).isInstanceOf(SearchResult.Failed.class);
        assertThat(((SearchResult.Failed) result).reason()).contains("响应缺少结果字段");
    }

    @Test
    void given_connection_refused_when_search_then_failed_connection() {
        // 指向一个确定无监听的端口（先占后放）
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        BochaWebSearchProvider dead =
                new BochaWebSearchProvider("http://127.0.0.1:" + port, "test-key");

        SearchResult result = dead.search("查询");

        assertThat(result).isInstanceOf(SearchResult.Failed.class);
        assertThat(((SearchResult.Failed) result).reason()).contains("连接异常");
    }

    // ---------- fixture ----------

    private void respond(int status, String body) {
        server.createContext("/v1/web-search", exchange -> {
            requests.add(String.format("%s %s auth=%s body=%s", exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            write(exchange, status, body);
        });
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
