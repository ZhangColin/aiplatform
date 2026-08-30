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

import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.ProvisionedResource;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import lombok.extern.slf4j.Slf4j;

/**
 * 本地 Docker 后端（B0 蓝图 §2 片1：docker CLI 子进程 / ProcessBuilder，弱化实现
 * 起步——上云换 TKE 适配器，端口不动，B0 蓝图 §3）。
 *
 * <p>一个工作区 = 一个单容器沙箱（ADR 0001 all-in-one，镜像 aiplatform/dev：node +
 * coding agent 运行时 + pg/redis 中间件 + 静态预览服务器同容器）。{@code /workspace}
 * 是唯一持久卷：布局骨架与容器内 pg/redis 由镜像入口脚本 {@code init-workspace.sh}
 * 对既有卷幂等自愈（PGDATA 落 {@code data/pg}、引擎会话数据经 XDG_DATA_HOME 重定向
 * {@code .platform}），容器无状态、销毁重建不丢数据。连接串写入
 * {@code /workspace/.env}（agent 从环境读，容器内回环形态）。容器命名消费确定性命名
 * （{@link WorkspaceNaming}，与记录同源派生），销毁级联因此可从句柄独立完成。
 * Phase A 仅实现 DEV 供给；TEST/PROD（打包产物形态）随后续切片落位。</p>
 */
@Component
@Adapter(PortType.CLIENT)
@Slf4j
public class DockerEnvironmentBackend implements EnvironmentBackend {

    private static final String DEV_IMAGE = "aiplatform/dev:0.5";

    private static final int PORT_MIN = 20000;
    private static final int PORT_MAX = 45000;
    private static final int PORT_ATTEMPTS = 10;
    private static final Duration RESOURCE_READY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PREVIEW_READY_TIMEOUT = Duration.ofSeconds(10);

    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder()
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
            int[] ports = startDevContainer(workspaceId, containerName);
            List<ProvisionedResource> resources = settleMiddleware(workspaceId, containerName);
            return WorkspaceProvision.of(
                    WorkspaceHandle.dev(workspaceId, containerName, WorkspaceNaming.networkName(workspaceId),
                            ports[0], ports[1]),
                    resources.toArray(new ProvisionedResource[0]));
        } catch (RuntimeException e) {
            // 置备中途失败：已落定的容器/卷无人回收即泄漏（#57），按命名约定级联回滚
            log.error("[workspace] {} 置备失败，级联回滚已落定资源", workspaceId.value(), e);
            cascadeCleanup(containerName);
            throw e;
        }
    }

    @Override
    public void destroyWorkspace(WorkspaceHandle handle) {
        cascadeCleanup(handle.containerName());
    }

    /** 级联清理：容器 → 卷（pg 数据与引擎会话都在卷内，随卷走）；全部尽力而为。 */
    private void cascadeCleanup(String containerName) {
        runSilently("docker", "rm", "-f", containerName);
        runSilently("docker", "volume", "rm", volumeOf(containerName));
    }

    @Override
    public ExecResult exec(WorkspaceHandle handle, String command) {
        return runCapture("docker", "exec", handle.containerName(), "sh", "-c", command);
    }

    @Override
    public byte[] packSource(WorkspaceHandle handle) {
        // 容器内 tar 流式写 stdout（GNU tar，dev 镜像 bookworm 基座自带）：
        // 相对路径归档（解包得到 ./index.html 而非 /workspace/index.html）；
        // .env 是平台生成的连接串机密、node_modules 可重建、data/ 与 .platform/
        // 是数据与平台产物而非源码——都不进包
        String command = "tar czf - --exclude=./.env --exclude=./node_modules"
                + " --exclude=./data --exclude=./.platform -C /workspace .";
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
        // 示意级预览：容器内起静态服务器挂出 /workspace，宿主机经预览端口映射访问；
        // 真实平台 = 构建产物 + 多进程 + 域名（B0 蓝图演化路径）
        ExecResult started = runCapture("docker", "exec", "-d", handle.containerName(),
                "node", "/opt/serve.js", "/workspace", String.valueOf(containerPort));
        if (!started.ok()) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "预览服务器启动失败: " + started.stderr());
        }
        URI url = URI.create("http://localhost:" + handle.previewPort() + "/");
        waitForHttpReady(url, "预览服务器 " + handle.containerName());
        return url;
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

    private int[] startDevContainer(WorkspaceId workspaceId, String containerName) {
        for (int attempt = 0; attempt < PORT_ATTEMPTS; attempt++) {
            int hostPort = randomPort();
            int previewPort = randomPort();
            if (previewPort == hostPort) {
                continue;
            }
            ExecResult r = runCapture("docker", "run", "-d", "--name", containerName,
                    "-p", hostPort + ":" + EnvironmentBackend.DEV_ENGINE_CONTAINER_PORT,
                    "-p", previewPort + ":" + EnvironmentBackend.DEV_PREVIEW_CONTAINER_PORT,
                    "-v", volumeOf(containerName) + ":" + WorkspaceLayout.ROOT,
                    "-w", WorkspaceLayout.ROOT,
                    // 两处归位修复（ADR 0001）：pg 数据进卷、引擎会话数据重定向 .platform
                    "-e", "PGDATA=" + WorkspaceLayout.absolute(WorkspaceLayout.PG_DATA_DIR),
                    "-e", "XDG_DATA_HOME=" + WorkspaceLayout.absolute(WorkspaceLayout.XDG_DATA_HOME),
                    "-e", "WORKSPACE_DB=" + WorkspaceNaming.databaseName(workspaceId),
                    DEV_IMAGE, "sleep", "infinity");
            if (r.exitCode() == 0) {
                return new int[]{hostPort, previewPort};
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

    /** 轮询 URL 直至有 HTTP 响应（任何状态码都算已监听）或超时——预览真实可访问才返回。 */
    private void waitForHttpReady(URI url, String target) {
        long deadline = System.currentTimeMillis() + PREVIEW_READY_TIMEOUT.toMillis();
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
        throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                "等待 " + target + " 就绪超时");
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
