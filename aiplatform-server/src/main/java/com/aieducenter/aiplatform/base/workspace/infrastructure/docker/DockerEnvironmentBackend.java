package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

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
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import lombok.extern.slf4j.Slf4j;

/**
 * 本地 Docker 后端（docker CLI 子进程 / ProcessBuilder，弱化实现起步——上云换
 * TKE 适配器，端口不动）。
 *
 * <p>一个工作区 = 一个单容器沙箱（ADR 0001 all-in-one，镜像 aiplatform/dev：node
 * 应用运行时 + pg/redis 中间件同容器，预览端口映射置备时落定——应用服务由编码
 * 智能体按约定自起（#44），平台不代起静态兜底（#45）；run 执行体经平台进程内
 * AgentScope 以 docker exec 驱动文件面，容器不装智能体 CLI）。{@code /workspace}
 * 是唯一持久卷：布局骨架与容器内 pg/redis 由镜像入口脚本 {@code init-workspace.sh}
 * 对既有卷幂等自愈（PGDATA 落 {@code data/pg}），容器无状态、销毁重建不丢数据。
 * 连接串写入
 * {@code /workspace/.env}（agent 从环境读，容器内回环形态）。容器命名消费确定性命名
 * （{@link WorkspaceNaming}，与记录同源派生），销毁级联因此可从句柄独立完成。
 * Phase A 仅实现 DEV 供给；TEST/PROD（打包产物形态）随后续切片落位。</p>
 */
@Component
@Adapter(PortType.CLIENT)
@Slf4j
public class DockerEnvironmentBackend implements EnvironmentBackend {

    private static final String DEV_IMAGE = "aiplatform/dev:0.7";

    private static final int PORT_MIN = 20000;
    private static final int PORT_MAX = 45000;
    private static final int PORT_ATTEMPTS = 10;
    private static final Duration RESOURCE_READY_TIMEOUT = Duration.ofSeconds(30);
    /** 预览探活短窗（#45）：未就绪快速抛 WSP_012（待期），等应用起服归调用方轮询。 */
    private static final Duration PREVIEW_PROBE_TIMEOUT = Duration.ofSeconds(2);

    /** 快照容器（#92）的容器本地落点：副本随容器可写层消失，不进卷。 */
    private static final String SNAPSHOT_ROOT = "/tmp/snap";
    /** 快照 pg 数据副本（PGDATA 复制到容器本地，清 pid 后起库——ADR 0007 解路二）。 */
    private static final String SNAPSHOT_DATA = "/tmp/snapdata";
    /** 容器内 pg 二进制目录（与 init-workspace.sh 同源——镜像 postgresql-15）。 */
    private static final String PG_BIN = "/usr/lib/postgresql/15/bin";

    private final SecureRandom random = new SecureRandom();
    // 探活必须锁定 HTTP/1.1：默认 HTTP/2 会对明文 HTTP 发 `Upgrade: h2c`，而
    // 工作区真实应用（next dev 等）见到 upgrade 头直接断连不回 HTTP/1.1 响应，
    // 探活方收到「header parser received no bytes」误判未就绪（预览恒 503）。
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public WorkspaceProvision createWorkspace(WorkspaceId workspaceId, EnvKind kind) {
        if (kind != EnvKind.DEV) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_KIND_NOT_SUPPORTED);
        }
        String containerName = WorkspaceNaming.containerName(workspaceId, EnvKind.DEV);
        // 幂等预清：移除同名残留容器（卷保留——重建即自愈，卷内数据原样续用）
        runSilently("docker", "rm", "-f", containerName);
        try {
            runSilently("docker", "volume", "create", volumeOf(containerName));
            ensureDevImage();
            int previewPort = startDevContainer(workspaceId, containerName);
            List<ProvisionedResource> resources = settleMiddleware(workspaceId, containerName);
            return WorkspaceProvision.of(
                    WorkspaceHandle.dev(workspaceId, containerName, WorkspaceNaming.networkName(workspaceId),
                            previewPort),
                    resources.toArray(new ProvisionedResource[0]));
        } catch (RuntimeException e) {
            // 置备中途失败：已落定的容器/卷无人回收即泄漏（#57），按命名约定级联回滚
            log.error("[workspace] {} 置备失败，级联回滚已落定资源", workspaceId.value(), e);
            cascadeCleanup(workspaceId, containerName);
            throw e;
        }
    }

    @Override
    public void destroyWorkspace(WorkspaceHandle handle) {
        cascadeCleanup(handle.workspaceId(), handle.containerName());
    }

    /** 级联清理：容器 → 快照容器 → 卷（pg 数据在卷内，随卷走）；全部尽力而为。 */
    private void cascadeCleanup(WorkspaceId workspaceId, String containerName) {
        runSilently("docker", "rm", "-f", containerName);
        // 快照容器级联（#92）：主容器销毁/重建时在途查看会话随之销毁——同卷 ro 挂载
        // 必在卷删除前清，否则 docker volume rm 因「卷仍被使用」失败留孤儿
        removeSnapshotContainers(workspaceId);
        runSilently("docker", "volume", "rm", volumeOf(containerName));
    }

    /** 按命名前缀扫清快照容器（幂等；无在途会话时 no-op）。 */
    private void removeSnapshotContainers(WorkspaceId workspaceId) {
        removeContainersByNameFilter(WorkspaceNaming.snapshotContainerPrefix(workspaceId));
    }

    /** 按命名子串扫清容器（快照容器清理的单点：级联与启动自愈共用）。 */
    private void removeContainersByNameFilter(String nameFilter) {
        ExecResult listed = runCapture("docker", "ps", "-a", "--filter",
                "name=" + nameFilter, "--format", "{{.Names}}");
        for (String name : listed.stdout().lines().map(String::trim).filter(n -> !n.isEmpty()).toList()) {
            runSilently("docker", "rm", "-f", name);
        }
    }

    @Override
    public ExecResult exec(WorkspaceHandle handle, String command) {
        return runCapture("docker", "exec", handle.containerName(), "sh", "-c", command);
    }

    @Override
    public byte[] packSource(WorkspaceHandle handle) {
        // 容器内 tar 流式写 stdout（GNU tar，dev 镜像 bookworm 基座自带）：
        // 相对路径归档（解包得到 ./index.html 而非 /workspace/index.html）；
        // 排除清单单一事实 = WorkspaceLayout（机密 .env + 非交付目录名单，
        // 与平台文件树只读端点同源——#27）
        String excludes = Stream.concat(Stream.of(WorkspaceLayout.ENV_FILE),
                        WorkspaceLayout.NON_DELIVERABLE_DIRS.stream())
                .map(name -> "--exclude=./" + name)
                .collect(Collectors.joining(" "));
        String command = "tar czf - " + excludes + " -C " + WorkspaceLayout.ROOT + " .";
        try {
            Process p = new ProcessBuilder("docker", "exec", handle.containerName(),
                    "sh", "-c", command).start();
            // stderr 异步读：tar 输出静默但异常时可能写满管道缓冲，同步顺序读会死锁
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getErrorStream().readAllBytes());
                } catch (Exception readFailure) {
                    return String.valueOf(readFailure.getMessage());
                }
            });
            byte[] stdout = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) {
                throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                        "源码打包失败: " + stderr.join());
            }
            return stdout;
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "源码打包执行失败: " + e.getMessage());
        }
    }

    @Override
    public URI exposePort(WorkspaceHandle handle, int containerPort) {
        // 渐进预览（#45）：端口映射在置备时已落定，URL 确定；这里只做探活——
        // run 执行体按约定自己把应用跑在容器端口（#44 尽早起服），平台不再代起
        // 静态兜底服务（用户会看到工作区文件列表的中间态，已出局）。探活通过才
        // 返回 URL（调用方以此作「应用可访问」判据）；短窗未就绪抛 WSP_012（待期，
        // 前端轮询续探），不做长阻塞等待。
        URI url = URI.create("http://localhost:" + handle.previewPort() + "/");
        waitForAppServing(url, "工作区应用端口 " + handle.containerName(),
                PREVIEW_PROBE_TIMEOUT, WorkspaceMessage.PREVIEW_NOT_SERVING);
        return url;
    }

    @Override
    public SnapshotHandle startSnapshot(WorkspaceHandle mainHandle, String viewId, String ref) {
        String snapName = WorkspaceNaming.snapshotContainerName(mainHandle.workspaceId(), viewId);
        String dbName = WorkspaceNaming.databaseName(mainHandle.workspaceId());
        String databaseUrl = "postgresql://" + dbName + "@localhost:5432/" + dbName;
        int previewPort = startSnapshotContainer(mainHandle, snapName);
        try {
            // 确定性启动序列（ADR 0007 配方）：复制 PGDATA → 清 pid → 起 pg → 起 redis
            // → 检出当时代码 → 起应用。全部容器本地落点，卷只读、主容器零扰动。
            runIn(snapName, snapshotDataCommand());
            runIn(snapName, snapshotCheckoutCommand(ref, databaseUrl));
            runIn(snapName, snapshotAppStartCommand(databaseUrl));
            URI url = URI.create("http://localhost:" + previewPort + "/");
            waitForAppServing(url, "快照应用 " + snapName, RESOURCE_READY_TIMEOUT,
                    WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
            log.info("[snapshot] {} 快照就绪（ref={}，预览 {}）", snapName, ref, url);
            return new SnapshotHandle(snapName, previewPort);
        } catch (RuntimeException e) {
            // 快照起服失败：销毁半启动容器，不留孤儿（副本随容器可写层消失）
            runSilently("docker", "rm", "-f", snapName);
            throw e;
        }
    }

    @Override
    public void stopSnapshot(SnapshotHandle snapshot) {
        runSilently("docker", "rm", "-f", snapshot.containerName());
    }

    @Override
    public void sweepSnapshotContainers() {
        // 启动自愈（#92）：快照容器是临时视图，平台重启后注册表丢账——按命名扫清全部
        // ws-*-snap-* 孤儿（含跨工作区），不留常驻孤儿
        removeContainersByNameFilter("-snap-");
    }

    // ---------- dev 环境内部 ----------

    /**
     * 落定单容器中间件（ADR 0001 all-in-one）：pg/redis 由容器入口脚本在容器内起
     * （PGDATA 落 {@code data/pg} 进卷、redis 回环纯缓存），本方法只做就绪等待 +
     * {@code .env} 注入 + 资源清单（入库 wsp_resources）。连接串无凭据——pg 为
     * trust 认证且只监听容器内回环、无对外端口，客户端只有同容器进程。容器销毁
     * 重建后同一入口脚本对既有卷幂等自愈，数据不丢。
     */
    private List<ProvisionedResource> settleMiddleware(WorkspaceId workspaceId, String containerName) {
        String dbName = WorkspaceNaming.databaseName(workspaceId);
        waitForReady(() -> execIn(containerName, "pg_isready -h localhost -U postgres"),
                "容器内 PostgreSQL " + containerName);
        waitForReady(() -> execIn(containerName, "redis-cli -h localhost ping"),
                "容器内 Redis " + containerName);

        String databaseUrl = "postgresql://" + dbName + "@localhost:5432/" + dbName;
        String redisUrl = "redis://localhost:6379";
        writeEnvFile(containerName, "DATABASE_URL=" + databaseUrl + "\nREDIS_URL=" + redisUrl + "\n");

        log.info("[workspace] {} 单容器中间件就绪：{}（pg/redis 容器内回环，数据落卷）",
                workspaceId.value(), containerName);
        return List.of(
                new ProvisionedResource(MiddlewareKind.POSTGRESQL, containerName, 0, databaseUrl),
                new ProvisionedResource(MiddlewareKind.REDIS, containerName, 0, redisUrl));
    }

    /** 容器内 shell 执行（就绪探针等容器内短命令）。 */
    private ExecResult execIn(String containerName, String command) {
        return runCapture("docker", "exec", containerName, "sh", "-c", command);
    }

    /** 连接串写入 /workspace/.env（布局约定三：唯一注入通道；内容为平台生成的 URL，无用户可控片段）。 */
    private void writeEnvFile(String containerName, String env) {
        run("docker", "exec", containerName, "sh", "-c",
                "printf '%s' '" + env + "' > " + WorkspaceLayout.absolute(WorkspaceLayout.ENV_FILE));
    }

    private int startDevContainer(WorkspaceId workspaceId, String containerName) {
        for (int attempt = 0; attempt < PORT_ATTEMPTS; attempt++) {
            int previewPort = randomPort();
            ExecResult r = runCapture("docker", "run", "-d", "--name", containerName,
                    "-p", previewPort + ":" + EnvironmentBackend.DEV_APP_CONTAINER_PORT,
                    "-v", volumeOf(containerName) + ":" + WorkspaceLayout.ROOT,
                    "-w", WorkspaceLayout.ROOT,
                    // 归位修复（ADR 0001）：pg 数据进卷（容器内回环，无其他对外端口）
                    "-e", "PGDATA=" + WorkspaceLayout.absolute(WorkspaceLayout.PG_DATA_DIR),
                    "-e", "WORKSPACE_DB=" + WorkspaceNaming.databaseName(workspaceId),
                    DEV_IMAGE, "sleep", "infinity");
            if (r.exitCode() == 0) {
                return previewPort;
            }
            // 端口被占等失败：清掉半启动容器再试
            runSilently("docker", "rm", "-f", containerName);
        }
        throw new ApplicationException(WorkspaceMessage.PORT_ALLOCATION_FAILED);
    }

    /** 轮询命令探针直至成功或超时（中间件/服务就绪等待）。 */
    private void waitForReady(Supplier<ExecResult> probe, String target) {
        long deadline = System.currentTimeMillis() + RESOURCE_READY_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (probe.get().ok()) {
                return;
            }
            sleep();
        }
        throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                "等待 " + target + " 就绪超时");
    }

    /** 轮询 URL 直至有 HTTP 响应（任何状态码都算已监听）或超时——应用真实可访问才
     *  返回；超时口径由 {@code onTimeout} 定（预览短窗 WSP_012 待期、快照长窗环境
     *  故障）。 */
    private void waitForAppServing(URI url, String target, Duration timeout,
            WorkspaceMessage onTimeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(url)
                        .timeout(Duration.ofSeconds(2))
                        .GET().build();
                http.send(request, HttpResponse.BodyHandlers.discarding());
                return;
            } catch (Exception ignored) {
                // 未就绪，继续等
            }
            sleep();
        }
        throw new ApplicationException(onTimeout, "等待 " + target + " 就绪超时");
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "等待资源就绪被中断");
        }
    }

    /** dev 镜像缺失时从 classpath 资源现场构建（首次约 1 分钟，之后走缓存）。 */
    private void ensureDevImage() {
        if (runCapture("docker", "image", "inspect", DEV_IMAGE).exitCode() == 0) {
            return;
        }
        try {
            Path dir = Files.createTempDirectory("aiplatform-ws-image");
            copyResource("docker/workspace/dev-image.Dockerfile", dir.resolve("Dockerfile"));
            copyResource("docker/workspace/init-workspace.sh", dir.resolve("init-workspace.sh"));
            copyResource("docker/workspace/serve.js", dir.resolve("serve.js"));
            copyResource("docker/workspace/annotation.js", dir.resolve("annotation.js"));
            run("docker", "build", "-t", DEV_IMAGE, dir.toString());
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "构建 dev 镜像失败: " + e.getMessage());
        }
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 " + resource);
            }
            Files.copy(in, target);
        }
    }

    // ---------- 快照容器内部（#92 ADR 0007 解路二） ----------

    /** 起快照容器：同镜像 + 同工作区卷挂 :ro + 入口脚本旁路（--entrypoint sleep）
     *  + 独立随机预览端口。主容器 pg 在活体卷上照跑，快照侧永不直接对共享 PGDATA
     *  起库（E0 教训）。 */
    private int startSnapshotContainer(WorkspaceHandle mainHandle, String snapName) {
        for (int attempt = 0; attempt < PORT_ATTEMPTS; attempt++) {
            int previewPort = randomPort();
            ExecResult r = runCapture("docker", "run", "-d", "--name", snapName,
                    "--entrypoint", "sleep",
                    "-p", previewPort + ":" + EnvironmentBackend.DEV_APP_CONTAINER_PORT,
                    "-v", volumeOf(mainHandle.containerName()) + ":" + WorkspaceLayout.ROOT + ":ro",
                    "-w", WorkspaceLayout.ROOT,
                    DEV_IMAGE, "infinity");
            if (r.exitCode() == 0) {
                return previewPort;
            }
            runSilently("docker", "rm", "-f", snapName);
        }
        throw new ApplicationException(WorkspaceMessage.PORT_ALLOCATION_FAILED);
    }

    /** 快照容器内 shell 执行（启动序列各步）；退出码非 0 即环境故障，如实上抛。 */
    private void runIn(String containerName, String command) {
        ExecResult result = runCapture("docker", "exec", containerName, "sh", "-c", command);
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "快照容器命令失败: " + command + "\n" + result.stderr());
        }
    }

    /** 数据副本 + 起库 + redis（原子一步）：PGDATA 复制到容器本地 → 清 pid → 起 pg
     *  → 起 redis。副本是崩溃一致快照（WAL 恢复起库），只读卷、主库零扰动。 */
    private String snapshotDataCommand() {
        String pgData = WorkspaceLayout.absolute(WorkspaceLayout.PG_DATA_DIR);
        return "rm -rf " + SNAPSHOT_DATA + " " + SNAPSHOT_ROOT
                + " && cp -a " + pgData + " " + SNAPSHOT_DATA
                + " && rm -f " + SNAPSHOT_DATA + "/postmaster.pid"
                + " && su postgres -c '" + PG_BIN + "/pg_ctl -D " + SNAPSHOT_DATA
                + " -l " + SNAPSHOT_DATA + "/startup.log -w start'"
                + " && redis-server --daemonize yes --bind 127.0.0.1 --port 6379"
                + " --save '' --appendonly no";
    }

    /** 检出当时代码 + 依赖复用 + 连接串：{@code git archive} 读 .git（ro 卷）把该
     *  commit 树解到容器本地（不碰 .git 索引、不互踩主工作树）；node_modules 复用
     *  主容器（ro 卷 symlink——历史代码的依赖不入版，v1 复用当前依赖）；.env 写
     *  副本本地（DATABASE_URL 指容器内 localhost，应用侧零适配）。 */
    private String snapshotCheckoutCommand(String ref, String databaseUrl) {
        String nodeModules = WorkspaceLayout.absolute("node_modules");
        return "mkdir -p " + SNAPSHOT_ROOT
                + " && git -C " + WorkspaceLayout.ROOT + " archive '" + ref
                + "' | tar -x -C " + SNAPSHOT_ROOT
                + " && if test -d " + nodeModules + " && ! test -e " + SNAPSHOT_ROOT
                + "/node_modules; then ln -s " + nodeModules + " " + SNAPSHOT_ROOT
                + "/node_modules; fi"
                + " && printf 'DATABASE_URL=" + databaseUrl
                + "\\nREDIS_URL=redis://localhost:6379\\n' > " + SNAPSHOT_ROOT + "/.env";
    }

    /** 起应用（后台常驻，8081）：server.js → node server.js；package.json → npm start
     *  （生成应用约定带 start 脚本）；否则静态 serve.js 兜底——仅无可用起服入口的
     *  历史版本（纯静态/仅文档轮）降级呈现，非主路径（#92「查看当时」不代起主预览
     *  已退休的静态兜底）。DATABASE_URL/REDIS_URL 注入进程环境（与 .env 同形双保险）。 */
    private String snapshotAppStartCommand(String databaseUrl) {
        return "cd " + SNAPSHOT_ROOT
                + " && if test -f server.js; then APP='node server.js';"
                + " elif test -f package.json; then APP='npm start';"
                + " else APP='node /opt/serve.js " + SNAPSHOT_ROOT + " 8081'; fi"
                + " && DATABASE_URL='" + databaseUrl + "' REDIS_URL='redis://localhost:6379'"
                + " nohup sh -c \"$APP\" > app.log 2>&1 & echo started";
    }

    // ---------- 命名约定（销毁级联与幂等重建的根基） ----------
    // container 名消费 {@link WorkspaceNaming}（与记录同源）；卷名是后端内部子资源
    // 命名（不入库），仅销毁级联用。

    private String volumeOf(String containerName) {
        return "vol-" + containerName;
    }

    // ---------- CLI 工具 ----------

    private int randomPort() {
        return PORT_MIN + random.nextInt(PORT_MAX - PORT_MIN);
    }

    private void run(String... cmd) {
        ExecResult r = runCapture(cmd);
        if (r.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "命令失败: " + String.join(" ", cmd) + "\n" + r.stderr());
        }
    }

    private void runSilently(String... cmd) {
        runCapture(cmd);
    }

    /** 测试子类覆写点：注入单条命令失败以验收回滚路径（#57 验收口径）。 */
    protected ExecResult runCapture(String... cmd) {
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
}
