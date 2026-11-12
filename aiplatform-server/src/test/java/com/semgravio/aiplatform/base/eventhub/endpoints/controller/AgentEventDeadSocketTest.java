package com.semgravio.aiplatform.base.eventhub.endpoints.controller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.infrastructure.sse.SseChannelHub;
import com.semgravio.aiplatform.business.identity.domain.model.AuthCookies;
import com.semgravio.aiplatform.business.identity.infrastructure.session.BffSession;
import com.semgravio.aiplatform.business.identity.endpoints.scheduler.BffSessionCleanupScheduler;
import com.semgravio.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.semgravio.aiplatform.business.identity.infrastructure.session.InMemoryBffSessionStore;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 死连接回归（真机日志噪音复刻）：真实 HTTP/SSE 订阅后以 RST 掀掉客户端 socket，
 * 撞「服务端 onError 尚未移除订阅」窗口内 broadcast——向死连接写事件失败会被
 * sendOrEvict catch（fire-and-forget 契约），但容器 async 机制把异常 dispatch
 * 回 SSE 端点：全局异常处理器打 ERROR（栈带原始业务事件，极易误读为业务 500）+
 * ApiResponse 写不进 text/event-stream 的二次 WARN。本回归守住两条：
 * 调用方不抛、断连不出 ERROR 噪音（SSE 端点本地 @ExceptionHandler 静默）。
 *
 * <p>与 {@link SseChannelHubTest} 的 RecordingSseSender 用例互补：单测里 emitter
 * 无 handler，complete() 是 no-op——正是「测试全绿、真机一用就错」的那一环。</p>
 */
@SpringBootTest(classes = AgentEventDeadSocketTest.NarrowApp.class,
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
class AgentEventDeadSocketTest {

    private static final String TEST_SESSION_ID = "agent-sse-dead-socket-test";

    @LocalServerPort
    private int port;

    @Autowired
    private EventsAppService appService;

    @Autowired
    private BffSessionStore sessionStore;

    @Test
    void given_dead_socket_when_broadcast_in_race_window_then_caller_unaffected()
            throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt"));

        AtomicBoolean anyPingReceived = new AtomicBoolean(false);
        // 多轮撞窗口：onError 移除是异步的，单次未必命中
        for (int i = 0; i < 8; i++) {
            RawSseClient sse = RawSseClient.connect(port,
                    AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
            try {
                assertThat(sse.awaitPing(Duration.ofSeconds(5))).isTrue();
                anyPingReceived.set(true);
            } finally {
                sse.kill();   // RST 断连：服务端 socket 已死但订阅移除有窗口
            }
            // 撞窗：断连后立即广播（真机 500 的时序）
            String runId = "run-dead-" + i;
            assertThatCode(() -> appService.publishAgentEvent("run-start",
                    Map.of("runId", runId, EventsAppService.OWNER_FIELD, "1"))).doesNotThrowAnyException();
            Thread.sleep(50);   // 给 onError 一点时间清理，进入下一轮
        }
        assertThat(anyPingReceived).isTrue();   // 循环本身有效（订阅确实建立过）
    }

    /**
     * 断连不出 ERROR 噪音：全局异常处理器（GlobalExceptionHandler）与本端点相关的
     * ERROR 日志必须为零——没有本地 @ExceptionHandler 静默时，async error dispatch
     * 每次断连都打一条带业务栈的「Unexpected error」（真机事故现场）。
     */
    @Test
    void given_dead_socket_when_async_error_dispatch_then_no_global_handler_error_noise()
            throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt"));

        List<ILoggingEvent> captured = captureDuring(
                List.of(loggerAt(GLOBAL_HANDLER_LOGGER)), () -> {
                    for (int i = 0; i < 8; i++) {
                        RawSseClient sse = RawSseClient.connect(port,
                                AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
                        assertThat(sse.awaitPing(Duration.ofSeconds(5))).isTrue();
                        sse.kill();
                        appService.publishAgentEvent("run-start",
                                Map.of("runId", "run-noise-" + i, EventsAppService.OWNER_FIELD, "1"));
                        Thread.sleep(50);   // async error dispatch 是异步的，给它时间跑
                    }
                });
        assertThat(captured.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList())
                .as("断连属 SSE 正常生命周期，全局异常处理器不得落 ERROR")
                .isEmpty();
    }

    /**
     * 心跳腿噪音回归（#479 生产路径，前 3 用例未覆盖的腿）：死连接由 15s 心跳
     * pingAll 逐出（生产实录），evict→complete() 内部 flush 撞「响应已错」状态抛
     * AsyncRequestNotUsableException——DefaultSseEmitterHandler 以 setErrorResult
     * 携异常入异步分发，dispatch 后异常解析落进全局兜底：ERROR「Unexpected error」
     * （栈指向 pingAll/evict）+ ApiResponse 写不进 text/event-stream 的二次 WARN，
     * 每 15s 一对。本回归断言两条腿全静默。等一个完整心跳周期（相位无关，确定性
     * 触发逐出）——broadcast 触发的断言窗口罩不住本腿（用例结束后的心跳噪音）。
     */
    @Timeout(60)
    @Test
    void given_dead_socket_when_heartbeat_evicts_then_no_error_warn_noise_pair()
            throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt"));

        List<ILoggingEvent> captured = captureDuring(List.of(
                loggerAt(GLOBAL_HANDLER_LOGGER),
                loggerAt(EXCEPTION_RESOLVER_LOGGER)), () -> {
                    for (int i = 0; i < 2; i++) {
                        RawSseClient sse = RawSseClient.connect(port,
                                AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
                        assertThat(sse.awaitPing(Duration.ofSeconds(5))).isTrue();
                        sse.kill();   // RST：此后无任何 broadcast，唯一逐出路径 = 心跳
                    }
                    // 一个完整心跳周期 + 余量：pingAll 必然撞上死连接并逐出（相位无关）
                    Thread.sleep(SseChannelHub.DEFAULT_HEARTBEAT_INTERVAL.toMillis() + 2000);
                });
        assertThat(captured.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList())
                .as("心跳逐出死连接属 SSE 正常生命周期，全局兜底不得落 ERROR")
                .isEmpty();
        // 不按文案匹配（框架文案一变即漏断言）：断言 resolver 窗口内 WARN 全零——
        // 兜底 handler 向已提交响应写 ApiResponse 的 HttpMessageNotWritable 二次
        // WARN 与 ERROR 同源，静默后同灭；窄窗口内该 logger 无无关 WARN。
        assertThat(captured.stream()
                .filter(event -> event.getLevel() == Level.WARN
                        && EXCEPTION_RESOLVER_LOGGER.equals(event.getLoggerName()))
                .toList())
                .as("二次 WARN 与兜底 ERROR 同源同灭")
                .isEmpty();
    }

    /**
     * 真机 500 拓扑复刻：前端双发 + 两条死连接。POST#1 的 broadcast 逐出死订阅 A
     * （complete() 触发 async dispatch，进行中），POST#2 几乎同时 broadcast 遍历到
     * 死订阅 B——并发 sendOrEvict/complete 竞态窗口即穿透现场。
     */
    @Test
    void given_two_dead_sockets_when_concurrent_broadcasts_then_caller_unaffected()
            throws Exception {
        sessionStore.put(TEST_SESSION_ID, new BffSession(1L, "sse-test", "idt", "at", "rt"));

        for (int i = 0; i < 8; i++) {
            RawSseClient first = RawSseClient.connect(port,
                    AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
            RawSseClient second = RawSseClient.connect(port,
                    AuthCookies.SESSION_COOKIE_NAME + "=" + TEST_SESSION_ID);
            assertThat(first.awaitPing(Duration.ofSeconds(5))).isTrue();
            assertThat(second.awaitPing(Duration.ofSeconds(5))).isTrue();
            first.kill();
            second.kill();

            // 并发双 publish（真机 POST#1/POST#2 双发时序）——两侧异常都不许有
            AtomicReference<Throwable> racerFailure = new AtomicReference<>();
            String runA = "run-a-" + i;
            String runB = "run-b-" + i;
            assertThatCode(() -> {
                Thread racer = new Thread(() -> {
                    try {
                        appService.publishAgentEvent("run-start",
                                Map.of("runId", runA, EventsAppService.OWNER_FIELD, "1"));
                    } catch (Throwable ex) {
                        racerFailure.set(ex);
                    }
                }, "publish-racer");
                racer.start();
                appService.publishAgentEvent("run-start",
                        Map.of("runId", runB, EventsAppService.OWNER_FIELD, "1"));
                racer.join(2000);
            }).doesNotThrowAnyException();
            assertThat(racerFailure.get()).as("并发 publish 线程亦不得抛").isNull();
            Thread.sleep(50);
        }
    }

    /** 噪音断言监听的 logger 名（同源单点，与断言的 logger 名过滤共用）。 */
    private static final String GLOBAL_HANDLER_LOGGER =
            "com.cartisan.web.exception.GlobalExceptionHandler";
    private static final String EXCEPTION_RESOLVER_LOGGER =
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver";

    private static Logger loggerAt(String name) {
        return (Logger) LoggerFactory.getLogger(name);
    }

    /** 携受检异常的 Runnable（captureDuring 的 body 语义）。 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 线程安全 ListAppender：异步 dispatch 线程 append 与测试线程读快照无竞态。 */
    private static final class ConcurrentListAppender extends ListAppender<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void append(ILoggingEvent event) {
            events.add(event);
        }
    }

    /**
     * 挂 appender 监听各 logger 跑 body，结束 detach 并返回期间捕获的事件（快照
     * 读安全；AppenderBase 未 start 时 doAppend 静默丢弃，故先 start）。
     */
    private static List<ILoggingEvent> captureDuring(List<Logger> loggers, ThrowingRunnable body)
            throws Exception {
        ConcurrentListAppender appender = new ConcurrentListAppender();
        appender.start();
        loggers.forEach(logger -> logger.addAppender(appender));
        try {
            body.run();
        } finally {
            loggers.forEach(logger -> logger.detachAppender(appender));
        }
        return appender.events;
    }

    /**
     * 原生 socket 的 SSE 客户端：完全掌控 TCP 层，setSoLinger(0)+close 制造 RST
     * 死连接（JDK HttpClient 不暴露 socket，做不了这个）。
     */
    private static final class RawSseClient {

        private final Socket socket;
        private final BufferedReader reader;

        private RawSseClient(Socket socket, BufferedReader reader) {
            this.socket = socket;
            this.reader = reader;
        }

        static RawSseClient connect(int port, String cookie) throws Exception {
            Socket socket = new Socket("localhost", port);
            socket.setTcpNoDelay(true);
            // BufferedReader 无超时——soTimeout 切片让 awaitPing 的截止时间可兑现
            socket.setSoTimeout(250);
            String request = "GET /api/events HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Accept: text/event-stream\r\n"
                    + "Cookie: " + cookie + "\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            return new RawSseClient(socket, reader);
        }

        /** 等到首条 :ping（订阅已建立并完成响应头冲刷）。 */
        boolean awaitPing(Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException sliceElapsed) {
                    continue;   // soTimeout 切片到期：回截止时间判断继续等
                }
                if (line == null) {
                    return false;
                }
                if (":ping".equals(line.trim())) {
                    return true;
                }
                // 响应头与空行一路放过
            }
            return false;
        }

        /** RST 断连（非优雅关闭）：服务端视角 socket 已死。 */
        void kill() {
            try {
                socket.setSoLinger(true, 0);
                socket.close();
            } catch (Exception ignored) {
                // 已关
            }
        }
    }

    /**
     * 窄上下文入口（同 EventsControllerSseTest 形态）：不依赖本机 PG。会话 store
     * 落 PG 后依赖 JPA（#329）——本上下文排除数据面，真 store 与清扫器换内存替身。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "com.semgravio.aiplatform.config",
            "com.semgravio.aiplatform.web",
            "com.semgravio.aiplatform.base.eventhub",
            "com.semgravio.aiplatform.business.identity.infrastructure.session"},
            excludeFilters = {
                    // 真 store/清扫器落 PG 依赖 JPA（#329）——窄上下文排除数据面，换内存替身
                    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                            classes = {BffSessionStore.class, BffSessionCleanupScheduler.class}),
                    // 同包另一测试类的窄入口也会被扫描捡起（其 @Bean 会与本类撞名）——
                    // 显式注册的入口类不经过滤器，只挡交叉捡拾
                    @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*NarrowApp")})
    static class NarrowApp {
        // 通道两件套与控制器随 eventhub 包扫描装配（无重复显式 @Bean）
        @org.springframework.context.annotation.Bean
        BffSessionStore sessionStore() {
            return new InMemoryBffSessionStore();
        }
    }
}
