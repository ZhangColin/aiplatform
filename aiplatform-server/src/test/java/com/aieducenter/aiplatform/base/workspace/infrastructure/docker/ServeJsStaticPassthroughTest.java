package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;

import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * serve.js 纯静态职责回归（#138）：真实起服（node 进程 + curl 探针），断言 HTML
 * 响应与磁盘文件**逐字节一致**——旧路径（#97 圈注 B 档）对含 {@code </body>} 的
 * HTML 内联注入标注脚本，本测试钉死该路径已删；预览注入唯一源 = 网关
 * sub_filter（ADR-0014，见 {@link PreviewGatewaySmokeTest}）。快照「查看当时」
 * 的静态兜底起服与此同路径，透传即其无注入口径。
 * node 不在则跳过（与 docker 缺席同口径，CI 无 node 时不红）。
 */
class ServeJsStaticPassthroughTest {

    private Process server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.destroyForcibly();
        }
    }

    @Test
    @Timeout(60)
    void given_html_with_body_close_when_served_then_response_byte_identical_no_injection() throws Exception {
        requireNode();
        Path dir = Files.createTempDirectory("aiplatform-servejs");
        try {
            String html = "<html><head><title>snap</title></head><body>查看当时</body></html>";
            Files.writeString(dir.resolve("index.html"), html);

            int port = freePort();
            server = startServer(dir, port);
            awaitServing(port);

            // 逐字节透传：响应体 == 磁盘文件（旧路径在此插入 data-aiplatform 注入标签）
            ExecResult res = run("curl", "-s", "http://127.0.0.1:" + port + "/");
            assertThat(res.exitCode()).as("curl 应成功：%s", res.stderr()).isZero();
            assertThat(res.stdout()).isEqualTo(html);

            // 404 兜底不回归（agent 未写出文件时的原样口径）
            ExecResult missing = run("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                    "http://127.0.0.1:" + port + "/nope.html");
            assertThat(missing.stdout()).isEqualTo("404");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** 起真实 serve.js（classpath 资源单源，与镜像内 /opt/serve.js 同文件）。 */
    private Process startServer(Path root, int port) throws Exception {
        Path script = Files.createTempFile("serve", ".js");
        try (InputStream in = ServeJsStaticPassthroughTest.class.getClassLoader()
                .getResourceAsStream("docker/workspace/serve.js")) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 docker/workspace/serve.js");
            }
            Files.copy(in, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        script.toFile().deleteOnExit();
        return new ProcessBuilder("node", script.toString(), root.toString(), String.valueOf(port))
                .redirectErrorStream(true)
                .start();
    }

    /** 内核分配的空闲端口（先占后放，与后端 randomPort 同打法）。 */
    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitServing(int port) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (run("curl", "-s", "-o", "/dev/null", "http://127.0.0.1:" + port + "/").exitCode() == 0) {
                return;
            }
            sleep();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待 serve.js 就绪被中断", e);
        }
    }

    private static ExecResult run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            int code = p.waitFor();
            return new ExecResult(out, err, code);
        } catch (Exception e) {
            return new ExecResult("", String.valueOf(e.getMessage()), 1);
        }
    }

    private static void requireNode() {
        Assumptions.assumeTrue(run("node", "--version").ok(), "本机 node 不在，跳过真实链路");
    }
}
