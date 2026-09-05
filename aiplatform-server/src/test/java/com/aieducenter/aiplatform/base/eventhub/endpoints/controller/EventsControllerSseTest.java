package com.aieducenter.aiplatform.base.eventhub.endpoints.controller;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import cn.hutool.core.collection.CollUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;

import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.business.project.application.ProjectEventTypes;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 事件端点真实验收（单端点单流，#82 双通道合并）：真实 HTTP/SSE 线格式——心跳注释行、
 * 统一信封 {type,payload,ts}、通知 id {projectId}:{seq} / 智能体事件 id {runId}:{seq}、
 * ?projectId= / ?runId= 过滤、fire-and-forget、<b>合并通道不合并语义</b>（智能体事件族
 * 新连接重放补发；通知族永不补发）、族投递规则（智能体事件不进未过滤订阅）、swagger
 * 端点描述嵌名册指引。窄上下文（{@link NarrowApp} 只扫 eventhub + 共享 web/config，
 * 排除数据面 autoconfig）不依赖本机 PG。
 */
@SpringBootTest(classes = EventsControllerSseTest.NarrowApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                + "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "com.cartisan.data.jpa.config.CartisanDataJpaAutoConfiguration"
})
class EventsControllerSseTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private EventsAppService appService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** /api/** 拦截面（A2）要求会话——窄上下文带上 identity 会话存储，测试自种一个 */
    @Autowired
    private BffSessionStore sessionStore;

    private static final String TEST_SESSION_ID = "sse-test-session";

    private final List<SseClient> clients = CollUtil.newArrayList();

    @AfterEach
    void tearDown() {
        clients.forEach(SseClient::close);
    }

    private SseClient connect(String query) throws Exception {
        return connect(query, null);
    }

    /** lastEventId 非空 = 浏览器断线重连姿态（自动携带 Last-Event-ID 请求头）。 */
    private SseClient connect(String query, String lastEventId) throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt",
                Instant.now().plusSeconds(600)));
        SseClient client = SseClient.connect(port, query,
                AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID, lastEventId);
        clients.add(client);
        return client;
    }

    // ---------- 通道基础（心跳 / 信封 / 过滤 / fire-and-forget） ----------

    @Test
    void given_connected_when_subscribe_then_initial_ping_and_headers_flushed() throws Exception {
        SseClient client = connect("");

        assertThat(client.contentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        // 连接建立即刻收到 :ping（响应头冲刷 + 即时存活信号），不进前端 listener
        assertThat(client.pollLine()).isEqualTo(":ping");
    }

    @Test
    void given_notification_when_subscribed_then_contract_envelope_and_id_on_the_wire() throws Exception {
        SseClient client = connect("?projectId=p-wire");

        appService.publishNotification("workspace-created", Map.of(
                "projectId", "p-wire",
                "projectName", "官网 demo",
                "container", "aiplatform-dev-p-wire",
                "projectType", "WEBSITE"));

        // 线格式：id → event → data（Spring 按设置顺序写出）
        assertThat(client.nextNonCommentLine()).isEqualTo("id:p-wire:1");
        assertThat(client.nextNonCommentLine()).isEqualTo("event:event");

        String dataLine = client.nextNonCommentLine();
        assertThat(dataLine).startsWith("data:");
        JsonNode envelope = objectMapper.readTree(dataLine.substring("data:".length()));

        assertThat(envelope.get("type").asText()).isEqualTo("workspace-created");
        assertThat(envelope.get("payload").get("projectId").asText()).isEqualTo("p-wire");
        assertThat(envelope.get("payload").get("projectType").asText()).isEqualTo("WEBSITE");
        assertThat(envelope.get("payload").has("type")).isFalse(); // payload 内禁 type 键名
        String ts = envelope.get("ts").asText();
        assertThat(ts).isNotBlank();
        Instant.parse(ts); // ISO-8601 可解析（非法即抛）
    }

    @Test
    void given_agent_event_when_subscribed_then_id_uses_run_id_as_stream_id() throws Exception {
        SseClient client = connect("?projectId=p-agent");

        appService.publishAgentEvent("run-start", Map.of(
                "projectId", "p-agent", "runId", "run-wire", "prompt", "写个落地页",
                "model", "deepseek-v4-pro", "engine", "agentscope"));

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-wire:1");
        JsonNode envelope = nextFrameEnvelope(client);
        assertThat(envelope.get("type").asText()).isEqualTo("run-start");
        assertThat(envelope.get("payload").get("runId").asText()).isEqualTo("run-wire");
        assertThat(envelope.get("payload").get("engine").asText()).isEqualTo("agentscope");
        assertThat(envelope.get("payload").has("type")).isFalse(); // payload 内禁 type 键名
        Instant.parse(envelope.get("ts").asText()); // ISO-8601 可解析（非法即抛）
    }

    @Test
    void given_both_families_when_project_subscribed_then_interleaved_on_one_stream() throws Exception {
        // 单端点单流：同一条连接上两族事件按发射序交错到达（id 各归各的 streamId 序）
        SseClient client = connect("?projectId=p-mix");

        appService.publishNotification("project-renamed", Map.of("projectId", "p-mix", "projectName", "名字"));
        appService.publishAgentEvent("run-start", Map.of("projectId", "p-mix", "runId", "run-mix", "prompt", "x"));
        appService.publishNotification("preview-ready", Map.of("projectId", "p-mix", "url", "http://localhost:30080"));

        assertThat(client.nextNonCommentLine()).isEqualTo("id:p-mix:1");
        client.skipEventBody();
        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-mix:1");
        client.skipEventBody();
        assertThat(client.nextNonCommentLine()).isEqualTo("id:p-mix:2");
        client.skipEventBody();
    }

    @Test
    void given_project_filter_when_publish_other_project_then_only_matching_received() throws Exception {
        SseClient client = connect("?projectId=p-filter");

        appService.publishNotification("project-renamed", Map.of("projectId", "p-other", "projectName", "别家名字"));
        appService.publishNotification("preview-ready",
                Map.of("projectId", "p-filter", "url", "http://localhost:30080"));

        // 下一事件即订阅项目的（p-other 被过滤挡掉），id 取订阅项目流
        assertThat(client.nextNonCommentLine()).isEqualTo("id:p-filter:1");
        assertThat(client.nextNonCommentLine()).isEqualTo("event:event");
        String dataLine = client.nextNonCommentLine();
        JsonNode envelope = objectMapper.readTree(dataLine.substring("data:".length()));
        assertThat(envelope.get("type").asText()).isEqualTo("preview-ready");
        assertThat(envelope.get("payload").get("projectId").asText()).isEqualTo("p-filter");
    }

    @Test
    void given_run_filter_when_publish_other_run_then_only_matching_received() throws Exception {
        SseClient client = connect("?runId=run-1");

        appService.publishAgentEvent("run-start", Map.of("runId", "run-2", "prompt", "x"));
        appService.publishAgentEvent("run-finish", Map.of("runId", "run-1", "finish", "end"));

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-1:1");
        client.skipEventBody();
        // run-2 事件被过滤挡掉：1.5s 内无新事件（心跳除外）
        assertThat(client.nextNonCommentLine(1500)).isNull();
    }

    @Test
    void given_unfiltered_connection_when_agent_events_published_then_not_delivered() throws Exception {
        // 族投递规则：站点级常开连接（无过滤）只收通知族——智能体过程细节是项目内
        // 事实，不进未过滤订阅（实时与重放同规则）
        appService.publishAgentEvent("run-start", Map.of(
                "projectId", "p-site", "runId", "run-site", "prompt", "x"));

        SseClient siteWide = connect("");

        appService.publishAgentEvent("part-text", Map.of(
                "projectId", "p-site", "runId", "run-site", "text", "正在准备。"));
        appService.publishNotification("project-renamed", Map.of("projectId", "p-site", "projectName", "名字"));

        assertThat(siteWide.nextNonCommentLine()).isEqualTo("id:p-site:1");   // 只有通知族
        siteWide.skipEventBody();
        assertThat(siteWide.nextNonCommentLine(1500)).isNull();
    }

    @Test
    void given_preview_updated_when_publish_then_wire_event_contract() throws Exception {
        // #49 逐修改刷新通知：最小载荷（projectId，不带 url——预览地址经 REST 取得
        // 且不变），信封与信址随通道统一契约（id {projectId}:{seq} / event:event /
        // payload 内禁 type 键 / ts ISO-8601）
        SseClient client = connect("?projectId=p-step");

        appService.publishNotification(ProjectEventTypes.PREVIEW_UPDATED,
                Map.of("projectId", "p-step"));

        assertThat(client.nextNonCommentLine()).isEqualTo("id:p-step:1");
        assertThat(client.nextNonCommentLine()).isEqualTo("event:event");
        String dataLine = client.nextNonCommentLine();
        assertThat(dataLine).startsWith("data:");
        JsonNode envelope = objectMapper.readTree(dataLine.substring("data:".length()));
        assertThat(envelope.get("type").asText()).isEqualTo("preview-updated");
        assertThat(envelope.get("payload").get("projectId").asText()).isEqualTo("p-step");
        assertThat(envelope.get("payload").has("type")).isFalse();
        Instant.parse(envelope.get("ts").asText()); // ISO-8601 可解析（非法即抛）
    }

    @Test
    void given_subscriber_disconnected_when_publish_then_caller_and_other_subscribers_unaffected() throws Exception {
        // fire-and-forget（真实断连）：坏连接不影响调用方，健康订阅照常收事件
        SseClient doomed = connect("?projectId=p-ff");
        SseClient healthy = connect("?projectId=p-ff");

        doomed.close(); // 模拟客户端断连（连接主动掐断，服务端下一次发送才发现）
        clients.remove(doomed);

        assertThatCode(() -> {
            appService.publishNotification("workspace-destroyed", Map.of("projectId", "p-ff"));
            Thread.sleep(200); // 留给服务端发现断连的时间
            appService.publishNotification("workspace-destroyed", Map.of("projectId", "p-ff"));
        }).doesNotThrowAnyException();

        assertThat(healthy.nextNonCommentLine()).isEqualTo("id:p-ff:1");
        healthy.skipEventBody(); // event:event + data:...
        assertThat(healthy.nextNonCommentLine()).isEqualTo("id:p-ff:2");
    }

    // ---------- 合并通道不合并语义：智能体族重放 / 通知族永不补发 ----------

    /**
     * 事故回归上线形态（#53/#56）：建项目后 BA 起跑即死，error 事件（带 projectId）
     * 发于零订阅——彼时浏览器还在导航/首编译；项目页就绪后以新连接（无
     * Last-Event-ID）按 ?projectId= 订阅，补发事件必须到达（原事件 id，非重发）。
     */
    @Test
    void given_agent_event_before_connect_when_subscribe_without_last_event_id_then_event_replayed()
            throws Exception {
        appService.publishAgentEvent("error", Map.of(
                "projectId", "7", "runId", "run-9",
                "message", "Failed to create model: DEEPSEEK_API_KEY is required"));

        SseClient client = connect("?projectId=7");

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-9:1");
        assertThat(client.nextNonCommentLine()).isEqualTo("event:event");
        JsonNode envelope = objectMapper.readTree(
                client.nextNonCommentLine().substring("data:".length()));
        assertThat(envelope.get("type").asText()).isEqualTo("error");
        assertThat(envelope.get("payload").get("projectId").asText()).isEqualTo("7");
    }

    /**
     * 断线补发（#23 生成环② → parts 契约）：编码 run 进行中用户刷新页面——部件事件
     * 在零订阅期间发射进缓冲，项目页就绪后以新连接（无 Last-Event-ID）按 ?projectId=
     * 订阅，当前 run 的部件事件按原序原 id 补达（续看进行中 run）；别项目事件不泄漏。
     */
    @Test
    void given_part_events_before_connect_when_subscribe_by_project_then_replayed_in_order()
            throws Exception {
        // 零订阅窗口内的当前 run 事件（部件 + 引擎透传真实形态）
        appService.publishAgentEvent("part-step", Map.of(
                "projectId", "23", "runId", "run-live", "sessionId", "coder-23",
                "engine", "agentscope", "step", 1));
        appService.publishAgentEvent("part-text", Map.of(
                "projectId", "23", "runId", "run-live", "sessionId", "coder-23",
                "engine", "agentscope", "text", "正在准备演示数据。"));
        appService.publishAgentEvent("part-action", Map.of(
                "projectId", "23", "runId", "run-live", "sessionId", "coder-23",
                "engine", "agentscope", "toolCallId", "tc-1", "toolName", "write_file",
                "state", "completed", "label", "编写【订单管理】"));
        appService.publishAgentEvent("part-text", Map.of(
                "projectId", "24", "runId", "run-other", "sessionId", "coder-24",
                "engine", "agentscope", "text", "别家项目的事件"));

        SseClient client = connect("?projectId=23");

        // 补发按发射序、id 取 runId 流（部件与透传同一 id 空间）；别项目被过滤
        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-live:1");
        JsonNode stepEvent = nextFrameEnvelope(client);
        assertThat(stepEvent.get("type").asText()).isEqualTo("part-step");
        assertThat(stepEvent.get("payload").get("step").asInt()).isEqualTo(1);

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-live:2");
        JsonNode narration = nextFrameEnvelope(client);
        assertThat(narration.get("type").asText()).isEqualTo("part-text");
        assertThat(narration.get("payload").get("text").asText()).isEqualTo("正在准备演示数据。");

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-live:3");
        JsonNode action = nextFrameEnvelope(client);
        assertThat(action.get("type").asText()).isEqualTo("part-action");
        assertThat(action.get("payload").get("label").asText()).isEqualTo("编写【订单管理】");

        // run-other（项目 24）不泄漏：随后无事件
        assertThat(client.nextNonCommentLine(1500)).isNull();
    }

    /**
     * 通知族「只作实时呈现，状态以查询为准」：零订阅窗口发出的通知不进缓冲——
     * 新连接（无 Last-Event-ID）不补发（与智能体事件族同一连接上语义分家）。
     */
    @Test
    void given_notification_before_connect_when_subscribe_without_last_event_id_then_not_replayed()
            throws Exception {
        appService.publishNotification("workspace-created", Map.of(
                "projectId", "7", "projectName", "官网 demo", "container", "c", "projectType", "WEBSITE"));

        SseClient client = connect("?projectId=7");

        assertThat(client.nextNonCommentLine(1500)).isNull();
    }

    /** 重连分野：带 Last-Event-ID = 浏览器自动重连姿态——不补发，维持 REST 重查兜底。 */
    @Test
    void given_agent_events_before_connect_when_subscribe_with_last_event_id_then_no_replay()
            throws Exception {
        appService.publishAgentEvent("run-start", Map.of("runId", "run-recon-9", "prompt", "x"));

        SseClient client = connect("", "run-earlier:5");

        assertThat(client.nextNonCommentLine(1500)).isNull();
    }

    /** 空串头视同无值（新连接）：空 Last-Event-ID 无信息量——按新连接补发，不吞错误卡。 */
    @Test
    void given_agent_events_before_connect_when_subscribe_with_blank_last_event_id_then_replayed()
            throws Exception {
        appService.publishAgentEvent("run-start", Map.of("runId", "run-blank-9", "prompt", "x"));

        SseClient client = connect("?runId=run-blank-9", "");

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-blank-9:1");
    }

    // ---------- 部件契约线格式 ----------

    /**
     * 消息部件线格式（parts 契约）：动作卡全生命周期（started → running →
     * completed）+ 步骤分组过线验收——统一信封、id {runId}:{seq}、payload 扁平
     * （内禁 type 键名、无 data 键）。
     */
    @Test
    void given_part_events_when_publish_then_lifecycle_on_the_wire() throws Exception {
        SseClient client = connect("?projectId=77");

        appService.publishAgentEvent("part-step", Map.of(
                "projectId", "77", "runId", "run-parts", "sessionId", "coder-77",
                "engine", "agentscope", "step", 1));
        String[][] lifecycle = {
                {"started", "编写【代码文件】"},
                {"running", "编写【订单管理】"},
                {"completed", "编写【订单管理】"},
        };
        for (String[] action : lifecycle) {
            appService.publishAgentEvent("part-action", Map.of(
                    "projectId", "77", "runId", "run-parts", "sessionId", "coder-77",
                    "engine", "agentscope", "toolCallId", "tc-1", "toolName", "write_file",
                    "state", action[0], "label", action[1]));
        }

        assertThat(client.nextNonCommentLine()).isEqualTo("id:run-parts:1");
        JsonNode step = nextFrameEnvelope(client);
        assertThat(step.get("type").asText()).isEqualTo("part-step");
        assertThat(step.get("payload").get("step").asInt()).isEqualTo(1);
        Instant.parse(step.get("ts").asText()); // ISO-8601 可解析（非法即抛）

        int seq = 1;
        for (String[] action : lifecycle) {
            assertThat(client.nextNonCommentLine()).isEqualTo("id:run-parts:" + ++seq);
            JsonNode envelope = nextFrameEnvelope(client);
            assertThat(envelope.get("type").asText()).isEqualTo("part-action");
            assertThat(envelope.get("payload").get("toolCallId").asText()).isEqualTo("tc-1");
            assertThat(envelope.get("payload").get("state").asText()).isEqualTo(action[0]);
            assertThat(envelope.get("payload").get("label").asText()).isEqualTo(action[1]);
            assertThat(envelope.get("payload").has("type")).isFalse(); // payload 内禁 type 键名
            assertThat(envelope.get("payload").has("data")).isFalse(); // 部件载荷扁平，无 data 键
        }
    }

    // ---------- swagger 名册指引 ----------

    @Test
    void given_swagger_group_when_fetch_api_docs_then_description_embeds_roster() {
        String apiDocs = restTemplate.getForObject("/v3/api-docs/eventhub", String.class);

        assertThat(apiDocs).contains("/api/events");
        assertThat(apiDocs).doesNotContain("/api/agent-events");   // 单端点：旧端点退役
        assertThat(apiDocs).contains("SSE事件清单");   // 名册正本指引
        assertThat(apiDocs).contains("workspace-created"); // 通知族名册精简表
        assertThat(apiDocs).contains("run-start");   // 智能体事件族名册精简表
        assertThat(apiDocs).contains("projectId");
    }

    /** 已读过 id 行后读完整事件（event 行 + data 行）并解析信封。 */
    private JsonNode nextFrameEnvelope(SseClient client) throws Exception {
        assertThat(client.nextNonCommentLine()).isEqualTo("event:event");
        return objectMapper.readTree(client.nextNonCommentLine().substring("data:".length()));
    }

    /**
     * 最小 SSE 消费端：JDK HttpClient 收 InputStream（自动解 chunked），后台线程逐行入队。
     */
    private static final class SseClient implements AutoCloseable {

        private final HttpClient httpClient;
        private final HttpResponse<InputStream> response;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final Thread readerThread;
        private volatile boolean closed;

        private SseClient(HttpClient httpClient, HttpResponse<InputStream> response) {
            this.httpClient = httpClient;
            this.response = response;
            this.readerThread = new Thread(this::drain, "sse-test-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        static SseClient connect(int port, String query, String cookie, String lastEventId)
                throws Exception {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/events" + query))
                    .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .header("Cookie", cookie)
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            if (lastEventId != null) {
                request.header("Last-Event-ID", lastEventId);
            }
            HttpResponse<InputStream> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("SSE 连接失败：HTTP " + response.statusCode());
            }
            return new SseClient(httpClient, response);
        }

        String contentType() {
            return response.headers().firstValue("Content-Type").orElse("");
        }

        /** 取下一行（含注释行与空行），超时返回 null。 */
        String pollLine() throws InterruptedException {
            return lines.poll(POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        String pollLine(long timeoutMillis) throws InterruptedException {
            return lines.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /** 取下一条非注释、非空行；超时返回 null。 */
        String nextNonCommentLine(long timeoutMillis) throws InterruptedException {
            String line = pollLine(timeoutMillis);
            while (line != null && (line.isEmpty() || line.startsWith(":"))) {
                line = pollLine(timeoutMillis);
            }
            return line;
        }

        String nextNonCommentLine() throws InterruptedException {
            String line = nextNonCommentLine(POLL_TIMEOUT.toMillis());
            if (line == null) {
                throw new AssertionError("5s 内未收到下一行 SSE 输出");
            }
            return line;
        }

        /** 跳过一条事件剩余的 event:/data: 行（已读过 id: 行之后）。 */
        void skipEventBody() throws InterruptedException {
            assertThat(nextNonCommentLine()).isEqualTo("event:event");
            assertThat(nextNonCommentLine()).startsWith("data:");
        }

        private void drain() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception ignored) {
                // 连接关闭属正常路径
            }
        }

        @Override
        public void close() {
            closed = true;
            readerThread.interrupt();
            try {
                response.body().close();
            } catch (Exception ignored) {
                // 已关闭
            }
            httpClient.close();
        }
    }

    /**
     * 窄上下文入口：只扫 eventhub 本包 + 共享 web/config（全局异常处理、SpringDoc
     * 分组、swagger 重定向、/api/** 鉴权拦截）+ identity 会话存储（A2 起 /api/events
     * 在拦截面内，测试自种会话），不扫业务/其他 BC——本测试只验 SSE 通道，不依赖数据面。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "com.aieducenter.aiplatform.config",
            "com.aieducenter.aiplatform.web",
            "com.aieducenter.aiplatform.base.eventhub",
            "com.aieducenter.aiplatform.business.identity.infrastructure.session"})
    static class NarrowApp {
    }
}
