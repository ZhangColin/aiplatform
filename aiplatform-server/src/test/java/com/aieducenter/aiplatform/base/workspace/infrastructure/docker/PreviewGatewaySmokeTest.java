package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Comparator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预览网关真实容器 smoke（#128）：起一个工作区容器（应用 8081 起服）+ nginx 网关
 * （同 previewnet 网、挂网关配置与标注脚本资产），断言 {@code {id}.localhost} 能路由
 * 到应用、HTML 响应含网关注入的标注脚本引用、标注脚本资产由网关自持可访问（内容是
 * 平台 annotation.js——postMessage 协议成立）。圈注 postMessage 的真实回传由前端
 * {@code annotation.contract.test.ts}（装载本脚本单源的契约测试）作回归保障
 * （双侧契约同源，见 ADR-0014）。
 * daemon 不在则跳过（CI 无 docker 时不红）。
 */
class PreviewGatewaySmokeTest {

    private static final int PROBE_TIMEOUT_SECONDS = 360;
    private static final String GATEWAY_IMAGE = "nginx:stable-alpine";

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();
    private final SecureRandom random = new SecureRandom();

    private WorkspaceProvision provision;
    private String gatewayContainer;

    @AfterEach
    void tearDown() {
        if (gatewayContainer != null) {
            docker("rm", "-f", gatewayContainer);
        }
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_workspace_serving_when_gateway_routes_then_html_injected_and_asset_served() throws Exception {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        WorkspaceHandle handle = provision.handle();
        String id = handle.workspaceId().value();

        // 摆一个不含任何注入的静态页 + 极简 node 服务（8081 起服）——响应里的注入只能来自网关。
        // upgrade 处理回裸 101：供网关 WebSocket 透传断言（升级头到达上游才有 101）
        execIn(handle, "printf '<html><head><title>probe</title></head><body>hello gateway</body></html>'"
                + " > /workspace/index.html");
        execIn(handle, "cat > /workspace/server.js <<'GW_EOF'\n"
                + "const http=require('http');const fs=require('fs');\n"
                + "const server=http.createServer((req,res)=>{res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});"
                + "res.end(fs.readFileSync('/workspace/index.html'));});\n"
                + "server.on('upgrade',(req,socket)=>{socket.write('HTTP/1.1 101 Switching Protocols\\r\\n"
                + "Upgrade: websocket\\r\\nConnection: Upgrade\\r\\n\\r\\n');});\n"
                + "server.listen(8081,'0.0.0.0');\n"
                + "GW_EOF");
        execIn(handle, "cd /workspace && nohup node server.js >/dev/null 2>&1 & echo started");

        int gatewayPort = 20000 + random.nextInt(20000);
        Path dir = Files.createTempDirectory("aiplatform-gateway");
        try {
            copyResource("docker/gateway/nginx.conf", dir.resolve("nginx.conf"));
            copyResource("docker/gateway/gateway-route.conf", dir.resolve("gateway-route.conf"));
            copyResource("docker/workspace/annotation.js", dir.resolve("annotation.js"));
            gatewayContainer = "aiplatform-gw-" + id;
            docker("rm", "-f", gatewayContainer);
            // 网关与工作区同 previewnet 网（按容器名 DNS 解析 ws-{id}），宿主端口映射供测试探活
            ExecResult started = docker("run", "-d", "--name", gatewayContainer,
                    "--network", WorkspaceNaming.PREVIEW_NETWORK,
                    "-p", gatewayPort + ":80",
                    "-v", dir.resolve("nginx.conf") + ":/etc/nginx/nginx.conf:ro",
                    "-v", dir.resolve("gateway-route.conf") + ":/etc/nginx/gateway-route.conf:ro",
                    "-v", dir.resolve("annotation.js") + ":/etc/nginx/annotation.js:ro",
                    GATEWAY_IMAGE);
            assertThat(started.exitCode()).as("网关应可启动：%s", started.stderr()).isZero();

            // 路由：{id}.localhost → ws-{id}:8081；HTML 含网关注入的标注脚本引用
            ExecResult routed = awaitGatewayServing(gatewayPort, id);
            assertThat(routed.exitCode()).as("网关应路由到工作区应用").isZero();
            assertThat(routed.stdout())
                    .contains("hello gateway")
                    .contains("data-aiplatform=\"annotation\"");

            // 标注脚本资产由网关自持、可访问，内容是平台 annotation.js（postMessage 协议）
            ExecResult script = curlHost(gatewayPort, id + ".localhost", "/.aiplatform/annotation.js");
            assertThat(script.exitCode()).isZero();
            assertThat(script.stdout()).contains("__aiplatform__").contains("postMessage");

            // WebSocket 升级透传（next dev 的 /_next/hmr）：nginx 默认剥 hop-by-hop 头
            // 且以 HTTP/1.0 转发，升级头到不了上游——浏览器侧 HMR 连接 failed 无限重试。
            // 断言升级请求经网关原样到达上游（上游回 101；头被剥则沦为普通 GET 回 200）
            ExecResult ws = curlWsUpgrade(gatewayPort, id + ".localhost", "/_next/hmr");
            assertThat(ws.stdout()).as("网关应透传 WebSocket 升级头（101，exit=%s）", ws.exitCode())
                    .contains("101");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** 轮询网关直至路由有响应（容器起 nginx 需片刻）。 */
    private static ExecResult awaitGatewayServing(int port, String id) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ExecResult result = curlHost(port, id + ".localhost", "/");
            if (result.exitCode() == 0) {
                return result;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待网关就绪被中断", e);
            }
        }
        return curlHost(port, id + ".localhost", "/");
    }

    // ---------- 直连 docker CLI / curl 的验证工具（真实状态为准） ----------

    private static ExecResult curlHost(int port, String host, String path) {
        return run("curl", "-s", "-H", "Host: " + host, "http://127.0.0.1:" + port + path);
    }

    /** 以 WebSocket 升级头探网关（只断状态行，101 后连接悬住由 --max-time 收口，退出码 28 属预期）。 */
    private static ExecResult curlWsUpgrade(int port, String host, String path) {
        return run("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "3",
                "--http1.1",
                "-H", "Host: " + host,
                "-H", "Connection: Upgrade",
                "-H", "Upgrade: websocket",
                "-H", "Sec-WebSocket-Version: 13",
                "-H", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
                "http://127.0.0.1:" + port + path);
    }

    private static ExecResult execIn(WorkspaceHandle handle, String command) {
        return docker("exec", handle.containerName(), "sh", "-c", command);
    }

    private static ExecResult docker(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return run(cmd);
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

    private static void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = PreviewGatewaySmokeTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 " + resource);
            }
            Files.copy(in, target);
        }
    }

    private static boolean dockerAvailable() {
        return run(new String[] { "docker", "version", "--format", "{{.Server.Version}}" }).ok();
    }

    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实链路");
    }
}
