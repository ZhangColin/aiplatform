package com.semgravio.aiplatform.business.project.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.semgravio.aiplatform.business.project.domain.port.FetchResult;

/**
 * 外部资料取数口单测（#213，形制照 FastembedEmbeddingClientTest）：JDK 内置
 * HttpServer 随机端口假服务，零新增测试依赖。抓取路径本身（成功/不可信标注/截断
 * /HTML 转文本/非 200/超时文案/代理出站）走「允许环回」替身判定——假服务跑在
 * 127.0.0.1，恰好是生产判定拦截的目标；安全底线②（环回/内网/云元数据拒绝）单独
 * 以生产构造断言（拒绝发生在连接前，无需服务器）。代理解析口径（#372）以
 * {@link HttpExternalContentFetcher#resolveProxy} 直测：env 经注入函数给出，
 * 不碰真环境变量（测试机残留 HTTPS_PROXY 会让结果漂移）。
 */
class HttpExternalContentFetcherTest {

    private HttpServer server;
    private HttpServer proxyServer;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> proxyRequests = new CopyOnWriteArrayList<>();

    /** 生产构造（真实 SSRF 判定）：安全底线②拒绝发生在连接前，无需服务器。 */
    private final HttpExternalContentFetcher guarded =
            new HttpExternalContentFetcher(1024,
                    HttpExternalContentFetcher.DEFAULT_REQUEST_TIMEOUT_SECONDS, "");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        if (proxyServer != null) {
            proxyServer.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 假服务在环回地址上：抓取路径测试注入「放行」判定（缺省直连＋生产超时）。 */
    private HttpExternalContentFetcher permissive(int maxBytes) {
        return permissive(maxBytes,
                Duration.ofSeconds(HttpExternalContentFetcher.DEFAULT_REQUEST_TIMEOUT_SECONDS), null);
    }

    private HttpExternalContentFetcher permissive(int maxBytes, Duration timeout, ProxySelector proxy) {
        return new HttpExternalContentFetcher(maxBytes, timeout, proxy, address -> false);
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

    // ---------- #372 · 请求超时（可配＋「源响应过慢」文案与连接异常区分） ----------

    @Test
    void given_slow_source_when_fetch_with_short_timeout_then_rejected_as_slow_source() {
        server.createContext("/slow", exchange -> {
            sleep(1500);
            respondOn(exchange, 200, "text/plain", "late");
        });

        // 注入 300ms 短超时：响应头 1.5s 后才来，须 300ms 即拒（不再钉死 15s）
        FetchResult result = permissive(1024, Duration.ofMillis(300), null)
                .fetch(baseUrl() + "/slow");

        assertThat(result).isInstanceOf(FetchResult.Rejected.class);
        // 模型可见文案带「源响应过慢」语义——提示该源慢而非平台错，减少盲目重试
        assertThat(((FetchResult.Rejected) result).reason()).contains("源响应过慢", "超时");
    }

    @Test
    void given_connection_refused_when_fetch_then_rejected_as_connection_error_not_slow() {
        // 127.0.0.1:1 无监听（放行判定下）连接立即被拒——连接异常文案不得混入「过慢」
        FetchResult result = permissive(1024).fetch("http://127.0.0.1:1/");

        assertThat(result).isInstanceOf(FetchResult.Rejected.class);
        assertThat(((FetchResult.Rejected) result).reason()).contains("连接异常");
        assertThat(((FetchResult.Rejected) result).reason()).doesNotContain("过慢");
    }

    // ---------- #372 · 出站代理（配置项经代理出站；环境变量回落） ----------

    @Test
    void given_configured_proxy_when_fetch_then_request_goes_through_proxy() throws IOException {
        startProxy();
        // 源服务器不注册任何 handler：直连只能得 404——拿回内容即证明走了代理
        HttpExternalContentFetcher fetcher = permissive(1024,
                Duration.ofSeconds(HttpExternalContentFetcher.DEFAULT_REQUEST_TIMEOUT_SECONDS),
                ProxySelector.of(proxyAddress()));

        FetchResult.Content content = (FetchResult.Content) fetcher.fetch(baseUrl() + "/file.txt");

        assertThat(content.text()).contains("via proxy");
        assertThat(proxyRequests).hasSize(1);
        // HTTP 代理收到的是绝对形式 URI（http://host:port/path），代理侧才做真正的 DNS 解析
        assertThat(proxyRequests.get(0)).startsWith("GET http://127.0.0.1:").endsWith("/file.txt");
        assertThat(requests).isEmpty();
    }

    @Test
    void given_https_proxy_env_and_no_config_when_fetch_then_request_goes_through_proxy()
            throws IOException {
        startProxy();
        int proxyPort = proxyServer.getAddress().getPort();
        // 无配置项时回落标准环境变量（env 经注入函数给出，不碰真环境）
        ProxySelector proxy = HttpExternalContentFetcher.resolveProxy(
                "", k -> "HTTPS_PROXY".equals(k) ? "127.0.0.1:" + proxyPort : null);

        FetchResult.Content content = (FetchResult.Content)
                permissive(1024, Duration.ofSeconds(10), proxy).fetch(baseUrl() + "/env.txt");

        assertThat(content.text()).contains("via proxy");
        assertThat(proxyRequests).hasSize(1);
    }

    // ---------- #372 · resolveProxy 解析缝（优先序／宽容口径／fail-fast） ----------

    @Test
    void given_config_and_env_both_when_resolve_proxy_then_config_wins() {
        ProxySelector proxy = HttpExternalContentFetcher.resolveProxy(
                "127.0.0.1:1", k -> "127.0.0.1:2");

        assertThat(addressOf(proxy)).isEqualTo(new InetSocketAddress("127.0.0.1", 1));
    }

    @Test
    void given_both_proxy_env_vars_when_resolve_proxy_then_https_first() {
        ProxySelector proxy = HttpExternalContentFetcher.resolveProxy("", envOf(
                "HTTPS_PROXY", "127.0.0.1:1", "HTTP_PROXY", "127.0.0.1:2"));

        assertThat(addressOf(proxy)).isEqualTo(new InetSocketAddress("127.0.0.1", 1));
    }

    @Test
    void given_scheme_prefixed_proxy_when_resolve_proxy_then_parsed() {
        // 环境变量常见带 http:// 前缀形态（如 http://127.0.0.1:7897）——宽容剥前缀
        ProxySelector proxy = HttpExternalContentFetcher.resolveProxy("http://127.0.0.1:7890", k -> null);

        assertThat(addressOf(proxy)).isEqualTo(new InetSocketAddress("127.0.0.1", 7890));
    }

    @Test
    void given_no_config_and_clean_env_when_resolve_proxy_then_null_direct() {
        assertThat(HttpExternalContentFetcher.resolveProxy(null, k -> null)).isNull();
        assertThat(HttpExternalContentFetcher.resolveProxy("", k -> null)).isNull();
        assertThat(HttpExternalContentFetcher.resolveProxy("  ", k -> null)).isNull();
    }

    @Test
    void given_malformed_env_value_when_resolve_proxy_then_ignored_direct() {
        // 环境可能残留脏代理值（#372 现场实锤）：宽容忽略回落直连，不炸应用
        assertThat(HttpExternalContentFetcher.resolveProxy("", k -> "garbage-no-port")).isNull();
    }

    @Test
    void given_malformed_config_when_resolve_proxy_then_fail_fast() {
        // 配置项是运维显式配置：写错即抛（启动失败），不得静默直连
        assertThatThrownBy(() -> HttpExternalContentFetcher.resolveProxy("no-port", k -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpExternalContentFetcher.resolveProxy("host:notaport", k -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpExternalContentFetcher.resolveProxy("host:99999", k -> null))
                .isInstanceOf(IllegalArgumentException.class);
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
            respondOn(exchange, status, contentType, body);
        });
    }

    private static void respondOn(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 假 HTTP 代理：记录收到的请求行（绝对形式 URI），直接回固定内容（不做真转发）——
     *  拿回该内容即证明请求经代理出站。 */
    private void startProxy() throws IOException {
        proxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxyServer.createContext("/", exchange -> {
            proxyRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respondOn(exchange, 200, "text/plain; charset=utf-8", "via proxy");
        });
        proxyServer.start();
    }

    private InetSocketAddress proxyAddress() {
        return new InetSocketAddress("127.0.0.1", proxyServer.getAddress().getPort());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Function<String, String> envOf(String... pairs) {
        return name -> {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (pairs[i].equals(name)) {
                    return pairs[i + 1];
                }
            }
            return null;
        };
    }

    /** selector 对任一 http(s) 目标的选定代理地址（单一代理口径，不分 scheme）。 */
    private static InetSocketAddress addressOf(ProxySelector proxy) {
        return (InetSocketAddress) proxy.select(URI.create("https://example.com/")).get(0).address();
    }
}
