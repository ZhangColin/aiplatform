package com.aieducenter.aiplatform.business.project.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import com.aieducenter.aiplatform.business.project.domain.port.FetchResult;

/**
 * 外部资料取数口单测（#213，形制照 FastembedEmbeddingClientTest）：JDK 内置
 * HttpServer 随机端口假服务，零新增测试依赖。抓取路径本身（成功/不可信标注/截断
 * /HTML 转文本/非 200）走「允许环回」替身判定——假服务跑在 127.0.0.1，恰好是
 * 生产判定拦截的目标；安全底线②（环回/内网/云元数据拒绝）单独以生产构造断言
 * （拒绝发生在连接前，无需服务器）。
 */
class HttpExternalContentFetcherTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    /** 生产构造（真实 SSRF 判定）：安全底线②拒绝发生在连接前，无需服务器。 */
    private final HttpExternalContentFetcher guarded = new HttpExternalContentFetcher(1024);

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

    /** 假服务在环回地址上：抓取路径测试注入「放行」判定。 */
    private HttpExternalContentFetcher permissive(int maxBytes) {
        return new HttpExternalContentFetcher(maxBytes, address -> false);
    }

    // ---------- 抓取路径（成功 / 标注 / 截断 / 转文本 / 非 200） ----------

    @Test
    void given_static_text_when_fetch_then_get_only_and_untrusted_marked_content() {
        respond(200, "text/plain; charset=utf-8", "hello 外部资料");

        FetchResult.Content content =
                (FetchResult.Content) permissive(1024).fetch(baseUrl() + "/file.txt");

        // 安全底线①：仅 GET
        assertThat(requests).containsExactly("GET /file.txt");
        // 安全底线④：不可信标注恒在正文首
        assertThat(content.text()).startsWith(HttpExternalContentFetcher.UNTRUSTED_MARKER);
        assertThat(content.text()).contains("hello 外部资料");
        assertThat(content.truncated()).isFalse();
        assertThat(content.initialHtmlOnly()).isFalse();
    }

    @Test
    void given_html_when_fetch_then_converts_to_text_and_marks_initial_html_only() {
        respond(200, "text/html; charset=utf-8",
                "<html><head><script>var x=1;</script><style>p{}</style></head>"
                        + "<body><h1>标题</h1><p>段落 &amp; 内容</p></body></html>");

        FetchResult.Content content =
                (FetchResult.Content) permissive(4096).fetch(baseUrl() + "/page");

        assertThat(content.initialHtmlOnly()).isTrue();
        assertThat(content.text()).contains("标题");
        assertThat(content.text()).contains("段落 & 内容");
        assertThat(content.text()).doesNotContain("<h1>", "<script>", "var x=1");
        // 动态页降级标志如实附上
        assertThat(content.text()).contains(HttpExternalContentFetcher.INITIAL_HTML_NOTE);
    }

    @Test
    void given_oversized_response_when_fetch_then_truncates_at_cap() {
        respond(200, "text/plain; charset=utf-8", "A".repeat(10_000));

        FetchResult.Content content =
                (FetchResult.Content) permissive(100).fetch(baseUrl() + "/big");

        assertThat(content.truncated()).isTrue();
        assertThat(content.text()).contains(HttpExternalContentFetcher.TRUNCATED_NOTE);
        // 正文截断到上限（标注/说明不计入上限）
        assertThat(content.text()).endsWith("A".repeat(100));
    }

    @Test
    void given_non_200_when_fetch_then_rejected_with_status_not_throw() {
        respond(404, "text/plain", "not found");

        FetchResult result = permissive(1024).fetch(baseUrl() + "/missing");

        assertThat(result).isInstanceOf(FetchResult.Rejected.class);
        assertThat(((FetchResult.Rejected) result).reason()).contains("404");
    }

    // ---------- 安全底线②（生产构造：真实判定，拒绝发生在连接前，无需服务器） ----------

    @Test
    void given_loopback_when_fetch_then_rejected() {
        assertRejected(guarded.fetch("http://127.0.0.1:9/"));
    }

    @Test
    void given_private_when_fetch_then_rejected() {
        assertRejected(guarded.fetch("http://192.168.1.1/"));
        assertRejected(guarded.fetch("http://10.0.0.1/"));
    }

    @Test
    void given_cloud_metadata_when_fetch_then_rejected() {
        assertRejected(guarded.fetch("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void given_non_http_scheme_when_fetch_then_rejected() {
        assertRejected(guarded.fetch("ftp://example.com/file"));
    }

    @Test
    void given_invalid_url_when_fetch_then_rejected() {
        assertRejected(guarded.fetch("not a url"));
    }

    @Test
    void given_redirect_when_fetch_then_follows_and_revalidates_each_hop() {
        // GitHub raw 形态：github.com → raw.githubusercontent.com 的 302 逐跳跟随取最终内容
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl() + "/final.txt");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        respond(200, "text/plain; charset=utf-8", "final content");

        FetchResult.Content content =
                (FetchResult.Content) permissive(1024).fetch(baseUrl() + "/redirect");

        assertThat(content.text()).contains("final content");
    }

    private static void assertRejected(FetchResult result) {
        assertThat(result).isInstanceOf(FetchResult.Rejected.class);
        assertThat(((FetchResult.Rejected) result).reason()).isNotBlank();
    }

    // ---------- fixture ----------

    private void respond(int status, String contentType, String body) {
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
