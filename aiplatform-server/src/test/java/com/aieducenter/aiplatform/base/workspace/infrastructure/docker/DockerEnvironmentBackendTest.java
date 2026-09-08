package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.SnapshotHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.business.project.domain.model.WorkspaceVersions;
import com.cartisan.core.exception.ApplicationException;


import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(handle.containerName()).isEqualTo("ws-" + id);
        // 单容器 all-in-one（ADR 0001）：dev 容器真实存活，pg/redis 容器从未存在
        assertThat(docker("inspect", "-f", "{{.State.Running}}", handle.containerName())
                .stdout().trim()).isEqualTo("true");
        List.of("pg-" + id, "rd-" + id).forEach(name ->
                assertThat(docker("inspect", name).exitCode()).as("不应存在 %s", name).isNotZero());
        // #128 网关化：容器进共享预览网络、无随机宿主端口映射（网关按容器名 DNS 路由）
        assertThat(docker("inspect", "-f", "{{.HostConfig.NetworkMode}}", handle.containerName())
                .stdout().trim()).isEqualTo(WorkspaceNaming.PREVIEW_NETWORK);
        assertThat(docker("port", handle.containerName()).stdout().trim())
                .as("工作区容器不应有宿主端口映射").isEmpty();

        // 布局定盘（WorkspaceLayout 常量表）物理落位：docs / data/pg / .platform 三目录
        WorkspaceLayout.SKELETON_DIRS.forEach(dir ->
                assertThat(execIn(handle, "test -d " + WorkspaceLayout.absolute(dir)).exitCode())
                        .as("布局目录 %s 应存在", dir).isZero());
        assertThat(execIn(handle, "test -f " + WorkspaceLayout.absolute(WorkspaceLayout.PG_DATA_DIR)
                + "/PG_VERSION").exitCode()).as("PGDATA 应落卷内 data/pg").isZero();

        // 归位修复经容器环境可见：PGDATA 进卷（容器内回环中间件，无其他对外端口）
        String containerEnv = docker("inspect", "-f",
                "{{range .Config.Env}}{{println .}}{{end}}", handle.containerName()).stdout();
        assertThat(containerEnv).contains("PGDATA=" + WorkspaceLayout.absolute(WorkspaceLayout.PG_DATA_DIR));

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
    void given_created_workspace_when_baseline_seeded_then_skeleton_page_serves_on_8081() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 基座模板就位（#113 只断外部行为，不探镜像内部层结构）：开箱即有基座工程
        // package.json + 预装依赖 node_modules——阶段 0 免选型/免 install 的前提
        assertThat(execIn(provision.handle(), "test -f /workspace/package.json").exitCode())
                .as("基座工程 package.json 应已就位").isZero();
        assertThat(execIn(provision.handle(), "test -d /workspace/node_modules").exitCode())
                .as("基座依赖应已预装（node_modules 就位）").isZero();

        // 照 run 执行体约定起服（pnpm dev 后台常驻 8081）；next dev 首次编译慢，轮询
        assertThat(execIn(provision.handle(),
                "cd /workspace && nohup pnpm dev >/tmp/app.log 2>&1 & echo started").exitCode())
                .as("基座应用应可自起").isZero();
        awaitServing(provision.handle().containerName());

        // 白底骨架页（阶段 0 收口即此形态）：curl 8081 有响应且是基座首页
        ExecResult served = curl(provision.handle().containerName());
        assertThat(served.exitCode()).as("8081 应可访问").isZero();
        assertThat(served.stdout()).as("骨架页应含基座首页文案").contains("系统");
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
    void given_no_app_serving_when_expose_port_then_not_serving_pending_not_failure() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // #45：平台不代起静态兜底服务——应用未起服是待期（503 WSP_012，前端轮询
        // 续探），不是 500 故障；用户不会经此看到工作区文件列表的中间态
        assertThatThrownBy(() -> backend.exposePort(provision.handle(),
                        EnvironmentBackend.DEV_APP_CONTAINER_PORT))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(WorkspaceMessage.PREVIEW_NOT_SERVING);
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_app_serving_when_expose_port_then_preview_url_responds_on_mapped_port() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        // 模拟 run 执行体已按约定在 8081 起服（#44 尽早起服）：容器内自起监听进程
        assertThat(backend.exec(provision.handle(),
                        "nohup node /opt/serve.js /workspace "
                                + EnvironmentBackend.DEV_APP_CONTAINER_PORT
                                + " >/dev/null 2>&1 &").exitCode())
                .as("容器内应用进程应可自起")
                .isZero();

        URI url = backend.exposePort(provision.handle(), EnvironmentBackend.DEV_APP_CONTAINER_PORT);

        // #128：URL 是子域（网关按 Host 路由，非宿主端口映射）；探活走容器内 curl 通过才返回
        assertThat(url.toString())
                .isEqualTo("http://" + provision.handle().workspaceId().value() + ".localhost/");
        // 应用真实可访问（容器内回环 8081——exposePort 的探活口径，不经过宿主端口）
        assertThat(curl(provision.handle().containerName()).exitCode())
                .as("容器内 8081 应可访问").isZero();
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_app_rejects_http2_upgrade_when_expose_port_then_probe_still_succeeds_on_http11() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        // 复现真实应用（next dev）行为：见到 Upgrade: h2c 直接断连、不回 HTTP/1.1
        // 响应——若探活走 HTTP/2 会误判未就绪。探活走容器内 curl（HTTP/1.1、不发
        // upgrade 头）后应通过。
        assertThat(execIn(provision.handle(), "cat > /workspace/server.js <<'PROBE_EOF'\n"
                + "const http=require('http');\n"
                + "http.createServer((req,res)=>{\n"
                + "  if(req.headers.upgrade){req.socket.destroy();return;}\n"
                + "  res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});res.end('ok');\n"
                + "}).listen(8081,'0.0.0.0');\n"
                + "PROBE_EOF").exitCode())
                .as("复现 next dev 断连行为的探针服务器应可落位")
                .isZero();
        assertThat(execIn(provision.handle(),
                "cd /workspace && nohup node server.js >/dev/null 2>&1 & echo started").exitCode())
                .isZero();

        // 探活通过即返回 URL（未就绪会抛 WSP_012）——本用例的回归断言就是「不抛」
        URI url = backend.exposePort(provision.handle(), EnvironmentBackend.DEV_APP_CONTAINER_PORT);

        assertThat(url.toString())
                .isEqualTo("http://" + provision.handle().workspaceId().value() + ".localhost/");
        // 真实可访问（curl 走 HTTP/1.1，不触发 upgrade 断连）
        assertThat(curl(provision.handle().containerName()).stdout().trim()).isEqualTo("ok");
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
                        + " && echo platform > /workspace/.platform/logs/run.log");

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

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_committed_versions_when_start_snapshot_then_serves_that_version_with_current_data() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);
        String db = "ws" + workspaceId.value();

        // 摆可跑小系统（server.js 读自身目录 index.html）→ 成版 v1
        execIn(provision.handle(), "cat > /workspace/server.js <<'SNAP_EOF'\n"
                + "const http=require('http');const fs=require('fs');const path=require('path');\n"
                + "http.createServer((req,res)=>{res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});"
                + "res.end(fs.readFileSync(path.join(__dirname,'index.html')));}).listen(8081,'0.0.0.0');\n"
                + "SNAP_EOF");
        execIn(provision.handle(), "printf '<html>v1</html>' > /workspace/index.html");
        String hash1 = commit(provision.handle(), "首次生成了系统", "111");

        // 改到 v2 → 成版 v2（主容器现态是 v2）
        execIn(provision.handle(), "printf '<html>v2</html>' > /workspace/index.html");
        String hash2 = commit(provision.handle(), "更新了系统", "222");
        assertThat(hash2).isNotEqualTo(hash1);

        // 主容器起服 v2（照 run 执行体约定：8081 后台常驻）
        execIn(provision.handle(),
                "cd /workspace && nohup node server.js >/dev/null 2>&1 & echo started");

        // 现在数据：主库写标记行（快照应带现在数据）
        assertThat(psql(provision.handle().containerName(), db,
                "CREATE TABLE IF NOT EXISTS snap_marker(v int); INSERT INTO snap_marker VALUES (42);")
                .exitCode()).isZero();

        // 起 v1 快照
        SnapshotHandle snap = backend.startSnapshot(provision.handle(), "view-1", hash1);
        try {
            // 当时系统可操作：快照内 8081 服务 v1，主容器仍服务 v2
            assertThat(curl(snap.containerName()).stdout().trim())
                    .as("快照应服务当时代码 v1").isEqualTo("<html>v1</html>");
            assertThat(curl(provision.handle().containerName()).stdout().trim())
                    .as("主容器不受快照影响仍服务 v2").isEqualTo("<html>v2</html>");
            // 数据是现在数据：快照 pg 能读到主库刚写入的标记行
            assertThat(psql(snap.containerName(), db, "SELECT count(*) FROM snap_marker")
                    .stdout().trim()).as("快照 pg 应带现在数据").isEqualTo("1");
            // 主容器零扰动：主 pg 仍健在（同卷双 PG 不炸）
            assertThat(docker("exec", provision.handle().containerName(),
                    "pg_isready", "-h", "localhost", "-U", "postgres").exitCode())
                    .as("主容器 pg 不受快照影响").isZero();
        } finally {
            backend.stopSnapshot(snap);
        }
        // 用完即销毁：快照容器已不在、主容器仍在（卷内无孤儿快照）
        assertThat(docker("inspect", snap.containerName()).exitCode())
                .as("快照容器应已销毁").isNotZero();
        assertThat(docker("inspect", provision.handle().containerName()).exitCode())
                .as("主容器仍健在").isZero();
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_active_snapshot_when_workspace_destroyed_then_snapshot_cascade_removed() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);
        execIn(provision.handle(), "printf '<html>v1</html>' > /workspace/index.html");
        String hash1 = commit(provision.handle(), "首次生成了系统", "111");

        SnapshotHandle snap = backend.startSnapshot(provision.handle(), "view-1", hash1);
        assertThat(docker("inspect", snap.containerName()).exitCode())
                .as("快照容器应已起").isZero();

        // 主容器销毁 → 快照容器随级联销毁（同卷 ro 挂载，卷删除前必清）
        backend.destroyWorkspace(provision.handle());
        provision = null;
        assertThat(docker("inspect", snap.containerName()).exitCode())
                .as("快照容器应随主容器销毁级联清除").isNotZero();
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

    /** 容器内 8081 应用正文（快照/主容器通吃）。 */
    private static ExecResult curl(String containerName) {
        return docker("exec", containerName, "sh", "-c", "curl -s http://localhost:8081");
    }

    /** 轮询容器内 8081 直至有 HTTP 响应（next dev 首次编译慢，最长约 2 分钟）。 */
    private static void awaitServing(String containerName) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (curl(containerName).exitCode() == 0) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待应用起服被中断", e);
            }
        }
        throw new AssertionError("基座应用未在期限内于 8081 起服");
    }

    /** 容器内 psql（trust 认证、回环连接，root 直连 postgres 角色）。 */
    private static ExecResult psql(String containerName, String db, String sql) {
        return docker("exec", containerName, "sh", "-c",
                "psql -h localhost -U postgres -d " + db + " -tAc '" + sql + "'");
    }

    /** 幂等 init + 成版提交（照 WorkspaceVersions 纯函数——exclude 挡住 data/pg 重物）。 */
    private static String commit(WorkspaceHandle handle, String summary, String runId) {
        ExecResult ensured = execIn(handle, WorkspaceVersions.ensureRepoCommand());
        assertThat(ensured.exitCode()).as("仓库初始化应成功：%s", ensured.stderr()).isZero();
        ExecResult committed = execIn(handle, WorkspaceVersions.commitCommand(summary, runId));
        assertThat(committed.exitCode()).as("成版提交应成功：%s", committed.stderr()).isZero();
        return committed.stdout().trim();
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
        List.of("ws-" + id, "vol-ws-" + id).forEach(name -> {
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
