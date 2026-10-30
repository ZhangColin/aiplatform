package com.semgravio.aiplatform.business.project.infrastructure;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.semgravio.aiplatform.business.project.domain.port.ModelCatalog;

/**
 * OpenAI 兼容模型清单拉取器单测（#368，形制照 {@code BochaWebSearchProviderTest}）：
 * JDK 内置 HttpServer 随机端口假服务，零新增测试依赖。请求契约（GET /models、
 * Bearer key、无请求体）与降级契约（非 200 / 坏响应 / 连接拒绝 → 失败理由不抛错）；
 * 响应形状以方舟端点 2026-10-23 实测为准（data[] 条目：id 必有，name/status/domain
 * 可缺——活跃模型无 status 字段）。
 */
class OpenAiCompatibleModelCatalogFetcherTest {

    private HttpServer server;
    private String lastRequestLine;
    private String lastAuthorization;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OpenAiCompatibleModelCatalogFetcher fetcher() {
        return new OpenAiCompatibleModelCatalogFetcher();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ---------- 请求契约＋正常解析 ----------

    @Test
    void given_models_endpoint_when_fetch_then_gets_models_with_bearer_and_maps_entries() {
        respond(200, """
                {"data":[
                  {"id":"deepseek-v4-flash-ga-260731","name":"deepseek-v4-flash-ga",
                   "object":"model","domain":"LLM"},
                  {"id":"doubao-lite-4k-240328","name":"doubao-lite-4k","object":"model",
                   "status":"Shutdown","domain":"LLM"},
                  {"id":"doubao-embedding-text-240515","object":"model",
                   "status":"Retiring","domain":"Embedding"}
                ]}
                """);

        ModelCatalogResult result = fetchAndRecord();

        // 请求契约：GET /models、Bearer key、无请求体
        assertThat(lastRequestLine).isEqualTo("GET /models");
        assertThat(lastAuthorization).isEqualTo("Bearer test-key");

        // 解析形状：id 必有；name/status/domain 如实转述（活跃模型无 status → null）
        assertThat(result.models()).containsExactly(
                new ModelCatalog.RemoteModel("deepseek-v4-flash-ga-260731",
                        "deepseek-v4-flash-ga", null, "LLM"),
                new ModelCatalog.RemoteModel("doubao-lite-4k-240328", "doubao-lite-4k",
                        "Shutdown", "LLM"),
                new ModelCatalog.RemoteModel("doubao-embedding-text-240515", null,
                        "Retiring", "Embedding"));
    }

    @Test
    void given_trailing_slash_base_url_when_fetch_then_single_slash_path() {
        respond(200, "{\"data\":[]}");

        fetcher().fetch(baseUrl() + "/", "test-key");

        assertThat(lastRequestLine).isEqualTo("GET /models");
    }

    @Test
    void given_empty_data_when_fetch_then_empty_models_not_failure() {
        respond(200, "{\"data\":[]}");

        ModelCatalogResult result = fetchAndRecord();

        // 空数组＝远端真零模型，不算坏响应
        assertThat(result.models()).isEmpty();
    }

    // ---------- 降级契约（非 200 / 坏响应 / 连接拒绝 → 失败理由不抛错） ----------

    @Test
    void given_non_200_when_fetch_then_failed_with_status() {
        respond(401, "{\"error\":{\"message\":\"Invalid API key\"}}");

        ModelCatalog catalog = fetcher().fetch(baseUrl(), "test-key");

        assertThat(catalog).isInstanceOf(ModelCatalog.Failed.class);
        assertThat(((ModelCatalog.Failed) catalog).reason()).contains("401");
    }

    @Test
    void given_malformed_body_when_fetch_then_failed_parse() {
        respond(200, "not-json");

        ModelCatalog catalog = fetcher().fetch(baseUrl(), "test-key");

        assertThat(((ModelCatalog.Failed) catalog).reason()).contains("响应解析异常");
    }

    @Test
    void given_json_without_data_array_when_fetch_then_failed_missing_field() {
        // 200 + 合法 JSON 但缺 data 数组＝坏响应，不是「真零模型」
        respond(200, "{\"object\":\"list\"}");

        ModelCatalog catalog = fetcher().fetch(baseUrl(), "test-key");

        assertThat(((ModelCatalog.Failed) catalog).reason()).contains("data");
    }

    @Test
    void given_entry_without_id_when_fetch_then_failed_bad_entry() {
        // 条目缺 id＝坏响应（明确报错不静默）——放行成 null-id 条目会在对照/排序面
        // 炸 500（评审发现的断层，票面承诺形状是错误信封）
        respond(200, """
                {"data":[{"name":"no-id-model","object":"model"},
                          {"id":"ok-model-2601","object":"model"}]}
                """);

        ModelCatalog catalog = fetcher().fetch(baseUrl(), "test-key");

        assertThat(catalog).isInstanceOf(ModelCatalog.Failed.class);
        assertThat(((ModelCatalog.Failed) catalog).reason()).contains("id");
    }

    @Test
    void given_connection_refused_when_fetch_then_failed_connection() {
        // 指向一个确定无监听的端口（先占后放）
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        ModelCatalog catalog = fetcher().fetch("http://127.0.0.1:" + port, "test-key");

        assertThat(((ModelCatalog.Failed) catalog).reason()).contains("连接异常");
    }

    // ---------- fixture ----------

    /** 拉取成功形的窄化断言夹具（失败形各例自行窄化——不在此预先假设成功）。 */
    private record ModelCatalogResult(List<ModelCatalog.RemoteModel> models) {
    }

    private ModelCatalogResult fetchAndRecord() {
        ModelCatalog catalog = fetcher().fetch(baseUrl(), "test-key");
        assertThat(catalog).isInstanceOf(ModelCatalog.Fetched.class);
        return new ModelCatalogResult(((ModelCatalog.Fetched) catalog).models());
    }

    private void respond(int status, String body) {
        server.createContext("/models", exchange -> {
            lastRequestLine = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            write(exchange, status, body);
        });
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
