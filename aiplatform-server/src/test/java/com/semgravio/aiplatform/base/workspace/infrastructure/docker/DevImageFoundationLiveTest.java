package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;

/**
 * dev 镜像底座事实活体验收（#633，阶段五-1）：在真 docker 的 0.19 镜像上钉住
 * 模板与镜像的一次性修复——端口/进程工具（{@code fuser}／{@code lsof}／
 * {@code ss}）、东八区时区（{@code date +%z} 与 Node 偏移、新建卷 PostgreSQL
 * {@code SHOW timezone}）、模板 {@code pnpm lint} 退出 0、起服后根页面与 icon
 * 路由可访问，以及存量卷在容器重启时被自愈脚本幂等对齐时区。
 *
 * <p>判据一律取容器内可观察结果（命令退出码/输出、HTTP 状态码），不以事件或
 * 镜像自述为准。daemon 不在则跳过（Assumptions）。</p>
 */
@Tag("live")
class DevImageFoundationLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 360;

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();

    private WorkspaceProvision provision;

    @AfterEach
    void tearDown() {
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_dev_image_container_when_probed_then_port_tools_and_timezone_present() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        WorkspaceHandle handle = provision.handle();

        // 0.18 实测三者皆无；模型查端口/找进程只能读 /proc/net/tcp
        assertThat(execIn(handle, "command -v fuser").exitCode())
                .as("镜像应提供 fuser（psmisc）").isZero();
        assertThat(execIn(handle, "command -v lsof").exitCode())
                .as("镜像应提供 lsof").isZero();
        assertThat(execIn(handle, "command -v ss").exitCode())
                .as("镜像应提供 ss（iproute2）").isZero();

        // ENV TZ=Asia/Shanghai：系统时区与 Node 进程一致为东八区
        assertThat(execIn(handle, "date +%z").stdout().trim())
                .as("容器系统时区应为 +0800").isEqualTo("+0800");
        assertThat(execIn(handle, "node -e 'console.log(new Date().getTimezoneOffset())'")
                .stdout().trim())
                .as("Node 进程时区偏移应为 -480（UTC+8）").isEqualTo("-480");

        // 新建卷：自愈脚本在 PG 就绪后幂等对齐会话时区（pg 就绪与对齐是相邻两步，轮询）
        awaitTimezone(handle, "Asia/Shanghai");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_baseline_workspace_when_serving_then_lint_passes_and_icon_route_accessible() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        WorkspaceHandle handle = provision.handle();

        // 模板 pnpm lint 退出 0（flat config 在场且不吃模板自身文件）
        ExecResult lint = execIn(handle, "cd /workspace && pnpm lint");
        assertThat(lint.exitCode())
                .as("模板 pnpm lint 应退出 0；stderr=%s", lint.stderr())
                .isZero();

        // 照 run 执行体约定起服（pnpm dev 后台常驻 8081）
        assertThat(execIn(handle, "cd /workspace && nohup pnpm dev >/tmp/app.log 2>&1 & echo started")
                .exitCode()).isZero();
        awaitServing(handle);

        assertThat(curlStatus(handle, "/"))
                .as("起服后根页面应可访问").isEqualTo("200");
        assertThat(curlStatus(handle, "/icon.svg"))
                .as("起服后 icon 路由（Next App Router icon 约定）应可访问").isEqualTo("200");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_existing_volume_with_utc_timezone_when_container_restarts_then_realigned_to_shanghai() {
        // 存量卷带非东八区配置（模拟 0.18 起旧卷）：容器重启走自愈入口，应幂等对齐
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        WorkspaceHandle handle = provision.handle();

        assertThat(execIn(handle,
                "psql -h localhost -U postgres -d postgres -c 'ALTER SYSTEM SET timezone = $$Etc/UTC$$'"
                        + " -c 'SELECT pg_reload_conf()'").exitCode())
                .as("应可把存量卷时区改成 Etc/UTC（模拟旧卷）").isZero();
        awaitTimezone(handle, "Etc/UTC");

        docker("restart", handle.containerName());

        // 存量卷重启后走自愈入口：pg 就绪后幂等对齐回东八区
        awaitTimezone(handle, "Asia/Shanghai");
    }

    // ---------- 容器内/宿主侧验证工具（真实状态为准） ----------

    private ExecResult execIn(WorkspaceHandle handle, String command) {
        return backend.exec(handle, command);
    }

    /** 容器内 8081 单发状态码（不经网关）。 */
    private String curlStatus(WorkspaceHandle handle, String path) {
        return execIn(handle, "curl -s -o /dev/null -w '%{http_code}' "
                + ProbeShape.LOOPBACK_APP_URL + path).stdout().trim();
    }

    /** 轮询容器内 8081 直至有 HTTP 响应（next dev 首次编译慢）。 */
    private void awaitServing(WorkspaceHandle handle) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (execIn(handle, "curl -s " + ProbeShape.LOOPBACK_APP_URL).exitCode() == 0) {
                return;
            }
            sleep(2000);
        }
        throw new AssertionError("基座应用未在期限内于 8081 起服");
    }

    /** 轮询容器内 PostgreSQL 会话时区直至期望值（自愈对齐/reload 均为异步生效）。 */
    private void awaitTimezone(WorkspaceHandle handle, String expected) {
        long deadline = System.currentTimeMillis() + 60_000;
        String current = "";
        while (System.currentTimeMillis() < deadline) {
            ExecResult show = execIn(handle,
                    "psql -h localhost -U postgres -d postgres -tAc 'SHOW timezone'");
            current = show.stdout().trim();
            if (show.exitCode() == 0 && expected.equals(current)) {
                return;
            }
            sleep(1000);
        }
        throw new AssertionError("PostgreSQL timezone 未在期限内对齐为 " + expected
                + "（当前: " + current + "）");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", e);
        }
    }

    /** 宿主侧 docker CLI（无管道：读完输出再 waitFor，避免 Windows 匿名管道死锁）。 */
    private static ExecResult docker(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            return new ExecResult(out, "", code);
        } catch (Exception e) {
            return new ExecResult("", String.valueOf(e.getMessage()), 1);
        }
    }

    private static boolean dockerAvailable() {
        return docker("version", "--format", "{{.Server.Version}}").ok();
    }

    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实链路");
    }
}
