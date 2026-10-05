package com.aieducenter.aiplatform.business.project.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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

import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationRequest;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;

/**
 * 出图供数适配器单测（#288，形制照 {@link BochaWebSearchProviderTest}）：JDK 内置
 * HttpServer 随机端口假服务，逐家钉契约——端点路径（各家 {@code images/generations}
 * 形）、鉴权头（Bearer key 直传）、请求体（model/prompt/size 三件，可空 size 不
 * 上送）、回图映射（url 形三家／b64 形 OpenAI＋顶层出图格式）与降级契约（非 200
 * ／坏响应／data 空／连接拒绝 → Failed 如实理由不抛错）。
 */
class ImageGenerationProvidersTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 记录请求（首行＋头＋体）并按编程回执响应。 */
    private void respond(int status, String body) {
        server.createContext("/", (HttpExchange exchange) -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + "\nAuthorization: " + exchange.getRequestHeaders().getFirst("Authorization")
                    + "\n" + new String(exchange.getRequestBody().readAllBytes()));
            byte[] payload = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
    }

    private ZhipuImageGenerationProvider zhipu() {
        return new ZhipuImageGenerationProvider(baseUrl(), "zhipu-key", "glm-image");
    }

    private VolcanoImageGenerationProvider volcano() {
        return new VolcanoImageGenerationProvider(baseUrl(), "ark-key", "doubao-seedream-5-0-260128");
    }

    private OpenAiImageGenerationProvider openai() {
        return new OpenAiImageGenerationProvider(baseUrl(), "oa-key", "gpt-image-2.5-sunburst");
    }

    private DashScopeImageGenerationProvider dashscope() {
        return new DashScopeImageGenerationProvider(baseUrl(), "ds-key", "qwen-image-3.0");
    }

    /** 上送过的最近请求体（JSON 形读回）。 */
    private JsonNode lastRequestBody() throws IOException {
        String raw = requests.get(requests.size() - 1);
        return mapper.readTree(raw.substring(raw.indexOf('\n', raw.indexOf('\n') + 1) + 1));
    }

    // ---------- 智谱：url 形＋路径/鉴权/请求体契约 ----------

    @Test
    void given_zhipu_success_when_generate_then_posts_contract_and_maps_url() throws Exception {
        respond(200, """
                {"created":1760000000,"data":[{"url":"https://cdn.zhipu.example/img/abc.png"}]}
                """);

        ImageProviderResult result = zhipu().generate(
                new ImageGenerationRequest("极简运动品牌 logo，黑金配色", null));

        assertThat(zhipu().providerKey()).isEqualTo("zhipu");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).startsWith("POST /api/paas/v4/images/generations");
        assertThat(requests.get(0)).contains("Authorization: Bearer zhipu-key");
        JsonNode body = lastRequestBody();
        assertThat(body.path("model").asText()).isEqualTo("glm-image");
        assertThat(body.path("prompt").asText()).isEqualTo("极简运动品牌 logo，黑金配色");
        assertThat(body.has("size")).isFalse(); // 空 size 不上送＝供应商缺省
        assertThat(result).isInstanceOf(ImageProviderResult.Generated.class);
        ImageProviderResult.Generated generated = (ImageProviderResult.Generated) result;
        assertThat(generated.model()).isEqualTo("glm-image");
        assertThat(generated.image().url()).isEqualTo("https://cdn.zhipu.example/img/abc.png");
        assertThat(generated.image().extension()).isEqualTo("png");
    }

    // ---------- 火山：url 形＋size 档位透传＋出图格式补扩展名 ----------

    @Test
    void given_volcano_success_with_extensionless_url_when_generate_then_output_format_ext() {
        respond(200, """
                {"model":"doubao-seedream-5-0-260128","created":1760000000,
                 "data":[{"url":"https://ark.example/output/abc","size":"2048x2048",
                          "output_format":"jpeg"}],
                 "usage":{"generated_images":1,"output_tokens":16384,"total_tokens":16384}}
                """);

        ImageProviderResult result = volcano().generate(
                new ImageGenerationRequest("夏日饮品海报", "2K"));

        assertThat(volcano().providerKey()).isEqualTo("volcano");
        assertThat(requests.get(0)).startsWith("POST /api/v3/images/generations");
        assertThat(requests.get(0)).contains("Authorization: Bearer ark-key");
        // URL 无扩展名 → 响应出图格式字段补位（volcano 出图缺省 jpeg，落盘不能误标 png）
        assertThat(result).isInstanceOf(ImageProviderResult.Generated.class);
        assertThat(((ImageProviderResult.Generated) result).image().extension()).isEqualTo("jpeg");
    }

    @Test
    void given_volcano_url_with_extension_when_generate_then_url_extension_wins() {
        respond(200, """
                {"data":[{"url":"https://ark.example/output/abc.png","output_format":"jpeg"}]}
                """);

        ImageProviderResult result = volcano().generate(new ImageGenerationRequest("p", null));

        assertThat(((ImageProviderResult.Generated) result).image().extension()).isEqualTo("png");
    }

    // ---------- OpenAI：b64 形＋顶层出图格式 ----------

    @Test
    void given_openai_success_when_generate_then_maps_b64_with_top_level_format() throws Exception {
        respond(200, """
                {"created":1760000000,"data":[{"b64_json":"aVBobQ=="}],
                 "output_format":"png","size":"1024x1024"}
                """);

        ImageProviderResult result = openai().generate(
                new ImageGenerationRequest("mascot illustration", "1024x1024"));

        assertThat(openai().providerKey()).isEqualTo("openai");
        assertThat(requests.get(0)).startsWith("POST /v1/images/generations");
        assertThat(requests.get(0)).contains("Authorization: Bearer oa-key");
        JsonNode body = lastRequestBody();
        assertThat(body.path("size").asText()).isEqualTo("1024x1024");
        assertThat(result).isInstanceOf(ImageProviderResult.Generated.class);
        ImageProviderResult.Image image = ((ImageProviderResult.Generated) result).image();
        assertThat(image.base64()).isEqualTo("aVBobQ==");
        assertThat(image.url()).isNull();
        assertThat(image.extension()).isEqualTo("png");
        assertThat(image.isUrlForm()).isFalse();
    }

    // ---------- 阿里：兼容模式路径＋url 形 ----------

    @Test
    void given_dashscope_success_when_generate_then_compatible_mode_path() {
        respond(200, """
                {"created":1760000000,"data":[{"url":"https://dashscope.example/out/xyz.png"}]}
                """);

        ImageProviderResult result = dashscope().generate(new ImageGenerationRequest("p", null));

        assertThat(dashscope().providerKey()).isEqualTo("dashscope");
        assertThat(requests.get(0)).startsWith("POST /compatible-mode/v1/images/generations");
        assertThat(requests.get(0)).contains("Authorization: Bearer ds-key");
        assertThat(((ImageProviderResult.Generated) result).image().url())
                .isEqualTo("https://dashscope.example/out/xyz.png");
    }

    // ---------- 降级契约（骨架共用，经智谱面钉） ----------

    @Test
    void given_error_status_when_generate_then_failed_with_error_message() {
        // 审核拦截形（智谱 1301）：HTTP 400 + error.code/message → Failed 如实理由
        respond(400, """
                {"error":{"code":"1301","message":"包含敏感内容，已拦截"}}
                """);

        ImageProviderResult result = zhipu().generate(new ImageGenerationRequest("p", null));

        assertThat(result).isInstanceOf(ImageProviderResult.Failed.class);
        assertThat(((ImageProviderResult.Failed) result).reason())
                .contains("HTTP 400").contains("包含敏感内容，已拦截").contains("1301");
    }

    @Test
    void given_bad_body_when_generate_then_failed_without_throw() {
        respond(200, "not-json");

        assertThat(zhipu().generate(new ImageGenerationRequest("p", null)))
                .isInstanceOf(ImageProviderResult.Failed.class);
    }

    @Test
    void given_empty_data_when_generate_then_failed_honestly() {
        respond(200, """
                {"created":1760000000,"data":[]}
                """);

        ImageProviderResult result = zhipu().generate(new ImageGenerationRequest("p", null));

        assertThat(result).isInstanceOf(ImageProviderResult.Failed.class);
        assertThat(((ImageProviderResult.Failed) result).reason()).contains("data 空");
    }

    @Test
    void given_connection_refused_when_generate_then_failed_without_throw() {
        server.stop(0); // 端口已死：连接异常如实失败不炸

        assertThat(zhipu().generate(new ImageGenerationRequest("p", null)))
                .isInstanceOf(ImageProviderResult.Failed.class);
    }
}
