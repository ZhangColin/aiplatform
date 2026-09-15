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
import com.aieducenter.aiplatform.base.workspace.domain.model.SnapshotHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.business.project.domain.model.WorkspaceVersions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预览网关真实容器 smoke（#128）：起一个工作区容器（应用 8081 起服）+ nginx 网关
 * （同 previewnet 网、挂网关配置与标注脚本资产），断言 {@code {id}.localhost} 能路由
 * 到应用、HTML 响应含网关注入的标注脚本引用、标注脚本资产由网关自持可访问（内容是
 * 平台 annotation.js——postMessage 协议成立）。圈注 postMessage 的真实回传由前端
 * {@code annotation.contract.test.ts}（装载本脚本单源的契约测试）作回归保障
 * （双侧契约同源，见 ADR-0014）。
 * #138 注入单源口径收口：加验镜像不携带标注脚本副本、serve.js 纯静态透传（容器内
 * 真实起服，HTML 与磁盘逐字节一致）、网关注入恰一次（无双注入——防重入 guard 已随
 * 单源删除）。
 * daemon 不在则跳过（CI 无 docker 时不红）。
 * #141 快照网关化：加验 {@code snap-{viewId}.localhost} 经网关路由到快照容器
 * （别名 DNS）——当时代码可逛、且 HTML <b>不含</b>注入标签（「只逛不换」ADR 0007
 * 口径的网关侧收口：快照 server 块不 include 注入块）、句柄 previewUrl 与路由同源。
 * #180 A 片自恢复页：上游不可达（死子域/杀起服）时网关回平台风格提示页（文案
 * 命中、no-store、自恢复脚本随页下发、状态码仍 5xx），主预览与快照子域同口径；
 * 上游起服恢复后同一子域经网关 200（恢复条件在网关缝可观测）；应用自身回的
 * 5xx 原样透传不被提示页替换（不开响应拦截）。自恢复脚本的浏览器轮询→重载
 * 行为不在自动化射程，走 #184 亲手联调。
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
        // upgrade 处理回裸 101：供网关 WebSocket 透传断言（升级头到达上游才有 101）。
        // /__app-boom 回应用自身 503：供「应用错误不被提示页替换」断言（#180 不开响应拦截）
        execIn(handle, "printf '<html><head><title>probe</title></head><body>hello gateway</body></html>'"
                + " > /workspace/index.html");
        execIn(handle, "cat > /workspace/server.js <<'GW_EOF'\n"
                + "const http=require('http');const fs=require('fs');\n"
                + "const server=http.createServer((req,res)=>{"
                + "if(req.url==='/__app-boom'){res.writeHead(503,{'Content-Type':'text/plain'});"
                + "res.end('app-level failure');return;}\n"
                + "res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});"
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
            copyResource("docker/gateway/gateway-proxy.conf", dir.resolve("gateway-proxy.conf"));
            copyResource("docker/gateway/gateway-inject.conf", dir.resolve("gateway-inject.conf"));
            copyResource("docker/gateway/unavailable.html", dir.resolve("unavailable.html"));
            copyResource("docker/workspace/annotation.js", dir.resolve("annotation.js"));
            gatewayContainer = "aiplatform-gw-" + id;
            docker("rm", "-f", gatewayContainer);
            // 网关与工作区同 previewnet 网（按容器名/别名 DNS 解析），宿主端口映射供测试探活
            ExecResult started = docker("run", "-d", "--name", gatewayContainer,
                    "--network", WorkspaceNaming.PREVIEW_NETWORK,
                    "-p", gatewayPort + ":80",
                    "-v", dir.resolve("nginx.conf") + ":/etc/nginx/nginx.conf:ro",
                    "-v", dir.resolve("gateway-proxy.conf") + ":/etc/nginx/gateway-proxy.conf:ro",
                    "-v", dir.resolve("gateway-inject.conf") + ":/etc/nginx/gateway-inject.conf:ro",
                    "-v", dir.resolve("unavailable.html") + ":/etc/nginx/unavailable.html:ro",
                    "-v", dir.resolve("annotation.js") + ":/etc/nginx/annotation.js:ro",
                    GATEWAY_IMAGE);
            assertThat(started.exitCode()).as("网关应可启动：%s", started.stderr()).isZero();

            // 路由：{id}.localhost → ws-{id}:8081；HTML 含网关注入的标注脚本引用，
            // 且恰一次（#138 单源口径：旧 serve.js 内联注入路径已删，无双注入向量）
            ExecResult routed = awaitGatewayServing(gatewayPort, id);
            assertThat(routed.exitCode()).as("网关应路由到工作区应用").isZero();
            assertThat(routed.stdout())
                    .contains("hello gateway")
                    .contains("data-aiplatform=\"annotation\"");
            assertThat(countOccurrences(routed.stdout(), "data-aiplatform=\"annotation\""))
                    .as("网关注入应恰一次（无双注入）").isEqualTo(1);

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

            // #180 A 片：死子域（无对应容器，DNS 解析失败）→ 网关回自恢复提示页而非
            // 裸 502——状态码仍 5xx、文案命中、no-store、自恢复脚本随页下发；快照子域
            // 同口径（提示页在共享的代理块，主预览/快照/两 profile 一并生效）
            assertUnavailablePage(curlFull(gatewayPort, "987654321012.localhost", "/"), "死主预览子域");
            assertUnavailablePage(curlFull(gatewayPort, "snap-987654321012.localhost", "/"), "死快照子域");

            // #180 A 片：应用自身的 5xx 原样透传、不被提示页替换（网关不开响应拦截）
            ExecResult appBoom = curlFull(gatewayPort, id + ".localhost", "/__app-boom");
            assertThat(statusCodeOf(appBoom.stdout())).as("应用自身 503 应原样透传").isEqualTo(503);
            assertThat(appBoom.stdout())
                    .as("应用错误响应不被提示页替换")
                    .contains("app-level failure")
                    .doesNotContain("系统暂不可用");

            // #138 注入单源：镜像不携带标注脚本副本（旧 serve.js 内联注入的资产位）
            assertThat(execIn(handle, "test ! -f /opt/annotation.js && echo CLEAN").stdout())
                    .as("镜像内不应再携带标注脚本副本").contains("CLEAN");

            // #138 serve.js 纯静态职责：容器内真实起服，HTML 响应与磁盘逐字节一致
            // （旧路径在此插入 data-aiplatform 注入标签；快照「查看当时」兜底同路径）
            String pureHtml = "<html><head><title>pure</title></head><body>static only</body></html>";
            execIn(handle, "mkdir -p /tmp/pure && printf '%s' '" + pureHtml + "' > /tmp/pure/index.html"
                    + " && nohup node /opt/serve.js /tmp/pure 8099 >/dev/null 2>&1 & echo started");
            ExecResult pure = execIn(handle,
                    "curl -s --retry 10 --retry-delay 1 --retry-connrefused http://localhost:8099/");
            assertThat(pure.exitCode()).as("serve.js 应起服可探（exit=%s）", pure.exitCode()).isZero();
            assertThat(pure.stdout()).as("serve.js 应逐字节透传（无注入）").isEqualTo(pureHtml);

            // #141 快照路由：成版当前态 → 起快照（真实 startSnapshot，容器进 previewnet
            // 挂别名）→ snap-{viewId}.localhost 经网关路由到快照容器——只逛不换：HTML
            // 不含注入标签（快照 server 块不 include 注入块），句柄 URL 与路由同源
            String viewId = "483920104737";
            String hash = commit(handle, "首次生成了系统", "111");
            SnapshotHandle snap = backend.startSnapshot(handle, viewId, hash);
            try {
                assertThat(snap.previewUrl().toString())
                        .isEqualTo("http://snap-" + viewId + ".localhost/");
                ExecResult snapRouted = curlHost(gatewayPort, "snap-" + viewId + ".localhost", "/");
                assertThat(snapRouted.exitCode())
                        .as("网关应路由快照子域（exit=%s）%s", snapRouted.exitCode(), snapRouted.stderr())
                        .isZero();
                assertThat(snapRouted.stdout())
                        .as("快照应服务当时代码且不注入圈注（只逛不换）")
                        .contains("hello gateway")
                        .doesNotContain("data-aiplatform");
            } finally {
                backend.stopSnapshot(snap);
            }

            // #180 A 片恢复弧：杀上游起服 → 同一活子域经网关回 5xx＋提示页
            // （连接拒绝也走 error_page）；重启起服 → 同子域恢复 200——恢复条件
            // 在网关缝可观测（自恢复脚本等的正是这个由 5xx 翻非 5xx 的时刻）
            execIn(handle, "pkill -f 'node server.js' || true");
            ExecResult down = await5xx(gatewayPort, id + ".localhost", "/");
            assertUnavailablePage(down, "杀起服后的活子域");
            execIn(handle, "cd /workspace && nohup node server.js >/dev/null 2>&1 & echo started");
            ExecResult recovered = awaitBodyContains(gatewayPort, id + ".localhost", "/", "hello gateway");
            assertThat(recovered.stdout()).as("上游恢复后同子域应经网关回真页面").contains("hello gateway");
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

    /**
     * #180 A 片：断言响应是自恢复提示页——状态码仍 5xx、双行文案命中、no-store、
     * 自恢复脚本随页下发（{@code curlFull} 的原始输出：状态行＋头＋正文一并验）。
     */
    private static void assertUnavailablePage(ExecResult raw, String what) {
        assertThat(raw.exitCode()).as("%s：curl 应完成（exit=%s）", what, raw.exitCode()).isZero();
        String response = raw.stdout();
        assertThat(statusCodeOf(response)).as("%s：状态码应仍为 5xx", what).isBetween(500, 599);
        assertThat(response)
                .as("%s：应回平台风格自恢复提示页", what)
                .contains("系统暂不可用，可能正在启动或休眠中")
                .contains("请稍后重试，或联系项目所有者")
                .contains("data-aiplatform=\"recovery\"")
                // 提示页是平台页面、不是用户系统，不注入圈注脚本（nginx 自产错误页
                // 与上游 HTML 同过 text/html 过滤链，代理块内已覆写 sub_filter 防注入）
                .doesNotContain("data-aiplatform=\"annotation\"");
        assertThat(response.toLowerCase())
                .as("%s：提示页应带 no-store", what)
                .contains("cache-control: no-store");
    }

    /** {@code curl -i} 原始输出的状态行（{@code HTTP/1.1 502 Bad Gateway}）→ 状态码。 */
    private static int statusCodeOf(String rawResponse) {
        return Integer.parseInt(rawResponse.trim().split("\\s+")[1]);
    }

    /** 轮询直至状态码 5xx（杀起服 → 连接拒绝 → 502 的转换留 5s 余量防抖动）。 */
    private static ExecResult await5xx(int port, String host, String path) {
        long deadline = System.currentTimeMillis() + 5_000;
        ExecResult last = curlFull(port, host, path);
        while (System.currentTimeMillis() < deadline
                && (last.exitCode() != 0 || statusCodeOf(last.stdout()) < 500)) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待状态码被中断", e);
            }
            last = curlFull(port, host, path);
        }
        return last;
    }

    /** 轮询直至响应体含期望片段（恢复后起服有窗口期，单发会假红）。 */
    private static ExecResult awaitBodyContains(int port, String host, String path, String needle) {
        long deadline = System.currentTimeMillis() + 30_000;
        ExecResult last = curlHost(port, host, path);
        while (System.currentTimeMillis() < deadline
                && (last.exitCode() != 0 || !last.stdout().contains(needle))) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待恢复被中断", e);
            }
            last = curlHost(port, host, path);
        }
        return last;
    }

    // ---------- 直连 docker CLI / curl 的验证工具（真实状态为准） ----------

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i != -1; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    private static ExecResult curlHost(int port, String host, String path) {
        return run("curl", "-s", "-H", "Host: " + host, "http://127.0.0.1:" + port + path);
    }

    /** 同 {@link #curlHost}，但带 {@code -i}：状态行/响应头/正文一并落 stdout（错误页断言要读头与状态码）。 */
    private static ExecResult curlFull(int port, String host, String path) {
        return run("curl", "-s", "-i", "-H", "Host: " + host, "http://127.0.0.1:" + port + path);
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

    /** 幂等 init + 成版提交（照 WorkspaceVersions 纯函数——快照检出的 ref 来源）。 */
    private static String commit(WorkspaceHandle handle, String summary, String runId) {
        ExecResult ensured = execIn(handle, WorkspaceVersions.ensureRepoCommand());
        assertThat(ensured.exitCode()).as("仓库初始化应成功：%s", ensured.stderr()).isZero();
        ExecResult committed = execIn(handle, WorkspaceVersions.commitCommand(summary, runId));
        assertThat(committed.exitCode()).as("成版提交应成功：%s", committed.stderr()).isZero();
        return committed.stdout().trim();
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
