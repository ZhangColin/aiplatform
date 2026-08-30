package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.cartisan.core.exception.ApplicationException;


import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Docker 后端真实验收（B0 §5：副作用以真实状态为准，不以事件自述）——
 * 本机 docker daemon 在则跑全链：单容器沙箱（应用与 pg/redis 同容器）→ 布局落位 +
 * .env 注入 → exec → exposePort → 销毁级联；容器销毁重建数据不丢（ADR 0001）。
 * daemon 不在则跳过（CI 无 docker 时不红）。
 */
class DockerEnvironmentBackendTest {

    private static final int PROBE_TIMEOUT_SECONDS = 360;

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();

    private WorkspaceProvision provision;

    @AfterEach
    void tearDown() {
        // 断言失败也不留容器：按命名约定兜底级联清理
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_dev_workspace_when_create_then_single_container_with_layout_and_middleware_ready() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();

        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);

        WorkspaceHandle handle = provision.handle();
        String id = workspaceId.value();
        assertThat(handle.kind()).isEqualTo(EnvKind.DEV);
        assertThat(handle.containerName()).isEqualTo("ws-" + id + "-dev");
        // 单容器 all-in-one（ADR 0001）：dev 容器真实存活，pg/redis 容器与专属网络从未存在
        assertThat(docker("inspect", "-f", "{{.State.Running}}", handle.containerName())
                .stdout().trim()).isEqualTo("true");
        List.of("pg-" + id, "rd-" + id, "net-" + id).forEach(name ->
                assertThat(docker("inspect", name).exitCode()).as("不应存在 %s", name).isNotZero());

        // 布局定盘（WorkspaceLayout 常量表）物理落位：docs / data/pg / .platform 四目录
        WorkspaceLayout.SKELETON_DIRS.forEach(dir ->
                assertThat(execIn(handle, "test -d " + WorkspaceLayout.absolute(dir)).exitCode())
                        .as("布局目录 %s 应存在", dir).isZero());
        assertThat(execIn(handle, "test -f " + WorkspaceLayout.absolute(WorkspaceLayout.PG_DATA_DIR)
                + "/PG_VERSION").exitCode()).as("PGDATA 应落卷内 data/pg").isZero();

        // 两处归位修复经容器环境可见：PGDATA 进卷、引擎会话数据 XDG 重定向 .platform
        String containerEnv = docker("inspect", "-f",
                "{{range .Config.Env}}{{println .}}{{end}}", handle.containerName()).stdout();
        assertThat(containerEnv).contains("PGDATA=" + WorkspaceLayout.absolute(WorkspaceLayout.PG_DATA_DIR));
        assertThat(containerEnv).contains("XDG_DATA_HOME=" + WorkspaceLayout.absolute(WorkspaceLayout.XDG_DATA_HOME));

        // 中间件真实可服务（容器内回环，就绪等待生效非事件自述）：pg 建表可写、redis 应答
        String dbName = "ws" + id;
        assertThat(execIn(handle, "psql -h localhost -U " + dbName + " -d " + dbName
                + " -c 'CREATE TABLE probe(id int); INSERT INTO probe VALUES (1)'").exitCode())
                .as("应用库 %s 应已由自愈入口建出且可写", dbName).isZero();
        assertThat(execIn(handle, "psql -h localhost -U " + dbName + " -d " + dbName
                + " -tAc 'SELECT id FROM probe'").stdout().trim()).isEqualTo("1");
        assertThat(execIn(handle, "redis-cli -h localhost ping").stdout().trim()).isEqualTo("PONG");

        // 资源清单与 .env 注入（容器内回环连接串，agent 读的就是它）
        assertThat(provision.resources()).extracting(ProvisionedResource::kind)
                .containsExactlyInAnyOrder(MiddlewareKind.POSTGRESQL, MiddlewareKind.REDIS);
        provision.resources().forEach(resource -> {
            assertThat(resource.containerName()).isEqualTo(handle.containerName());
            assertThat(resource.hostPort()).isZero();
        });
        ExecResult envFile = execIn(handle, "cat /workspace/.env");
        assertThat(envFile.exitCode()).isZero();
        assertThat(envFile.stdout()).contains("DATABASE_URL=postgresql://" + dbName + "@localhost:5432/" + dbName);
        assertThat(envFile.stdout()).contains("REDIS_URL=redis://localhost:6379");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_created_workspace_when_container_destroyed_and_rebuilt_then_data_survives() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);
        String dbName = "ws" + workspaceId.value();

        // 卷内落两类持久物：文件（PRD 形态）与 pg 数据
        backend.exec(provision.handle(), "mkdir -p /workspace/docs && echo '需求正文' > /workspace/docs/PRD.md");
        backend.exec(provision.handle(), "psql -h localhost -U " + dbName + " -d " + dbName
                + " -c 'CREATE TABLE survive(id int); INSERT INTO survive VALUES (7)'");

        // 销毁容器（卷保留）→ 原地重建：置备幂等预清只删容器，入口脚本对既有卷自愈
        docker("rm", "-f", provision.handle().containerName());
        WorkspaceProvision rebuilt = backend.createWorkspace(workspaceId, EnvKind.DEV);

        assertThat(execIn(rebuilt.handle(), "cat /workspace/docs/PRD.md").stdout().trim())
                .as("重建后文件不丢").isEqualTo("需求正文");
        assertThat(execIn(rebuilt.handle(), "psql -h localhost -U " + dbName + " -d " + dbName
                + " -tAc 'SELECT id FROM survive'").stdout().trim())
                .as("重建后 pg 数据不丢（PGDATA 在卷内）").isEqualTo("7");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_created_workspace_when_exec_then_command_output_captured() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        ExecResult result = backend.exec(provision.handle(), "echo hello-workspace");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().trim()).isEqualTo("hello-workspace");
        // 非 0 退出码是命令失败，原样归一化不抛
        ExecResult failing = backend.exec(provision.handle(), "exit 3");
        assertThat(failing.exitCode()).isEqualTo(3);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_created_workspace_when_expose_port_then_preview_url_responds_on_mapped_port() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        URI url = backend.exposePort(provision.handle(), EnvironmentBackend.DEV_PREVIEW_CONTAINER_PORT);

        assertThat(url.toString()).isEqualTo("http://localhost:" + provision.handle().previewPort() + "/");
        // 真实可访问（任何状态码都算已监听；空 workspace 挂 index 缺失返回 404）
        assertThatCode(() -> HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(url).GET().build(),
                        HttpResponse.BodyHandlers.discarding()))
                .doesNotThrowAnyException();
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_workspace_with_source_when_pack_source_then_real_tarball_without_secrets_or_data() throws Exception {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        // 工作区摆上源码事实 + 平台机密（.env）+ 可重建重物（node_modules）+ 数据与平台产物
        backend.exec(provision.handle(),
                "echo '<html>demo</html>' > /workspace/index.html"
                        + " && mkdir -p /workspace/node_modules/leftpad"
                        + " && echo junk > /workspace/node_modules/leftpad/index.js"
                        + " && echo pgdata > /workspace/data/pg/base.fakedb"
                        + " && echo session > /workspace/.platform/sessions/opencode.db");

        byte[] tarball = backend.packSource(provision.handle());

        // gzip 魔数（真实 tar.gz，非空壳）
        assertThat(tarball.length).isGreaterThan(2);
        assertThat(tarball[0]).isEqualTo((byte) 0x1f);
        assertThat(tarball[1]).isEqualTo((byte) 0x8b);
        // 宿主侧解包清单（真实状态为准）：源码在、机密/重物/数据/平台产物不在
        Path tar = Files.createTempFile("aiplatform-source", ".tar.gz");
        String listing;
        try {
            Files.write(tar, tarball);
            listing = hostTarListing(tar);
        } finally {
            Files.deleteIfExists(tar);
        }
        assertThat(listing).contains("index.html");
        assertThat(listing).doesNotContain(".env");
        assertThat(listing).doesNotContain("node_modules");
        assertThat(listing).doesNotContain("data");
        assertThat(listing).doesNotContain(".platform");
    }

    @Test
    void given_non_dev_kind_when_create_then_rejected_before_any_side_effect() {
        // Phase A 仅 DEV（WSP_007）；拒绝发生在任何 docker 交互之前
        assertThatThrownBy(() -> backend.createWorkspace(WorkspaceId.generate(), EnvKind.TEST))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("暂不支持的环境类型");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_created_workspace_when_destroy_then_cascade_cleaned() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);
        backend.destroyWorkspace(provision.handle());
        provision = null; // 已清理，tearDown 不再兜底

        // inspect/volume inspect 失败（非 0 退出码）= 已不存在
        assertResourcesGone(workspaceId);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_env_write_fails_when_create_then_provisioned_resources_rolled_back() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        DockerEnvironmentBackend failing = new FailingEnvWriteBackend();

        assertThatThrownBy(() -> failing.createWorkspace(workspaceId, EnvKind.DEV))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("环境后端操作失败");

        // 置备中途失败已级联回滚：dev 容器/卷无残留（#57 泄漏验收）
        assertResourcesGone(workspaceId);
    }

    // ---------- 直连 docker CLI 的验证工具（真实状态为准） ----------

    /** 宿主侧 tar 清单（macOS/Linux 自带 tar）：解开包内容做事实核对。 */
    private static String hostTarListing(Path tarFile) throws Exception {
        Process p = new ProcessBuilder("tar", "tzf", tarFile.toString()).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }

    private static ExecResult docker(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
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

    /** 容器内 shell 执行（与后端 execIn 同形，断言用）。 */
    private static ExecResult execIn(WorkspaceHandle handle, String command) {
        return docker("exec", handle.containerName(), "sh", "-c", command);
    }

    private static boolean dockerAvailable() {
        return docker("version", "--format", "{{.Server.Version}}").ok();
    }

    /** 纯逻辑用例（如 kind 拒绝）不依赖 daemon，不进此门。 */
    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实链路");
    }

    /** 断言按命名约定派生的容器/卷均已不存在（inspect 非 0 = 已清）。 */
    private static void assertResourcesGone(WorkspaceId workspaceId) {
        String id = workspaceId.value();
        List.of("ws-" + id + "-dev", "vol-ws-" + id + "-dev").forEach(name -> {
            if (name.startsWith("vol-")) {
                assertThat(docker("volume", "inspect", name).exitCode()).isNotZero();
            } else {
                assertThat(docker("inspect", name).exitCode()).isNotZero();
            }
        });
    }

    /** 注入 .env 写入失败的后端（覆写 runCapture 单条命令失败，余命令走真实 docker）。 */
    private static class FailingEnvWriteBackend extends DockerEnvironmentBackend {

        @Override
        protected ExecResult runCapture(String... cmd) {
            if (cmd.length >= 6 && "docker".equals(cmd[0]) && "exec".equals(cmd[1])
                    && cmd[cmd.length - 1].endsWith(WorkspaceLayout.absolute(WorkspaceLayout.ENV_FILE))) {
                return new ExecResult("", "simulated failure", 1);
            }
            return super.runCapture(cmd);
        }
    }
}
