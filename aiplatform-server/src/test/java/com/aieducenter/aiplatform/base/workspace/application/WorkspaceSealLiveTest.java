package com.aieducenter.aiplatform.base.workspace.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.infrastructure.archive.LocalDiskSealPackageStore;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 封存与深度唤醒活体验收（#172 AC，真 docker daemon + 真库 + 真编排——非假面）：
 * 休眠满期 → 扫描一轮 → 整卷打成封存包（排除三大缓存、数据库随包）落盘 + 卷删除 +
 * 期望态置封存；再触碰项目（深度唤醒）→ 解包回卷 + 重建 + 依赖重装 + 应用起服 →
 * 文件与数据库数据完整恢复、预览可探活。分钟级长任务，@Timeout 15 分钟兜底。
 * daemon 不在本机时跳过。
 */
@IntegrationTest
class WorkspaceSealLiveTest {

    /** 深度唤醒收敛上界：解包 + 重建 + pnpm install（冷链路全量下载）+ dev 起服。 */
    private static final Duration DEEP_WAKE_DEADLINE = Duration.ofMinutes(12);

    /** 卷内文件探针（封存前写入、深度唤醒后应原样读回）。 */
    private static final String FILE_MARKER = "seal-probe";

    @Autowired
    private WorkspaceConvergenceAppService convergence;

    @Autowired
    private WorkspaceProvisionAppService provisioner;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DockerEnvironmentBackend backend;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WorkspaceProperties properties;

    @TempDir
    private Path archiveDir;

    private WorkspaceId workspaceId;

    private Path archivePath() {
        Workspace workspace = workspaceRepository.findById(workspaceId.id()).orElse(null);
        return workspace != null && workspace.getArchivePath() != null
                ? Path.of(workspace.getArchivePath()) : null;
    }

    @AfterEach
    void cleanup() {
        if (workspaceId != null) {
            workspaceRepository.findById(workspaceId.id()).ifPresent(workspace -> {
                try {
                    backend.destroyWorkspace(workspace.toHandle());
                } catch (RuntimeException ignored) {
                    // 尽力而为清理
                }
                workspaceRepository.delete(workspace);
            });
        }
        Path archive = archivePath();
        if (archive != null) {
            // 记录已删后兜底清包（destroy 的清包路径只在记录在时生效）
            try {
                Files.deleteIfExists(archive);
            } catch (Exception ignored) {
                // 尽力而为
            }
        }
    }

    @Test
    @Timeout(900)
    void given_hibernated_over_threshold_when_scan_then_sealed_then_deep_wake_data_and_app_intact() throws Exception {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();
        String containerName = workspace.getContainerName();
        String dbName = "ws" + workspaceId.value();

        // 卷内种数据：文件探针 + 数据库行（两类用户数据的完整性代表）
        assertThat(backend.exec(workspace.toHandle(),
                "printf " + FILE_MARKER + " > /workspace/.seal-probe").exitCode()).isZero();
        assertThat(backend.exec(workspace.toHandle(),
                "psql -h localhost -U " + dbName + " -d " + dbName
                        + " -c \"create table seal_probe(v text);"
                        + " insert into seal_probe values ('" + FILE_MARKER + "')\"")
                .exitCode()).as("种入数据库探针行").isZero();

        // 休眠态（容器删、卷留）+ 休眠满期（last-touch 逾闲置阈值＋封存阈值）
        workspace.markTouched(LocalDateTime.now().minus(properties.getIdleThreshold())
                .minus(properties.getSealThreshold()).minusMinutes(1));
        workspaceRepository.save(workspace.hibernate());
        backend.hibernate(workspace.toHandle());

        int acted = new WorkspaceHibernationAppService(
                backend, workspaceRepository, convergence, sealPackageStore(),
                transactionTemplate, properties).scanOnce(Map.of(), LocalDateTime.now());

        // 封存落定（收敛模块互斥面异步执行——扫描提交、任务收敛，轮询等落地）：
        // 包落盘（数据库随包、三大缓存排除）+ 卷删除 + 期望态置封存
        assertThat(acted).isEqualTo(1);
        assertThat(awaitSealed(containerName)).as("扫描后封存收敛（打包→落盘→意图→删卷）").isTrue();
        Workspace sealed = workspaceRepository.findById(workspaceId.id()).orElseThrow();
        assertThat(sealed.getDesiredState()).isEqualTo(DesiredState.SEALED);
        assertThat(sealed.getArchivePath()).isNotNull();
        assertThat(sealed.getArchiveSizeBytes()).isPositive();
        Path archive = Path.of(sealed.getArchivePath());
        assertThat(archive).exists();
        String listing = hostTarListing(archive);
        assertThat(listing).contains("data/pg/");          // 数据库随包
        assertThat(listing).contains(".seal-probe");       // 用户文件随包
        assertThat(listing).contains("package.json");      // 应用代码随包
        assertThat(listing).doesNotContain("node_modules")
                .doesNotContain(".pnpm-store")
                .doesNotContain("./.next");                // 三大可重建缓存排除
        assertThat(dockerExitCode("docker", "volume", "inspect", "vol-" + containerName))
                .as("封存后卷应已删").isNotZero();

        // 深度唤醒（项目 API 触碰同路径，收敛模块 TOUCH 面）：解包回卷 + 重建 + 依赖重装 + 应用起服
        convergence.convergeAsync(workspaceId, ConvergenceFace.TOUCH, true);
        assertThat(awaitDeepWoken(containerName))
                .as("触碰封存工作区：深度唤醒（数据回卷 + 应用起服）").isTrue();
        Workspace woken = workspaceRepository.findById(workspaceId.id()).orElseThrow();
        assertThat(woken.getDesiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(backend.exec(woken.toHandle(), "cat /workspace/.seal-probe").stdout())
                .isEqualTo(FILE_MARKER);
        assertThat(backend.exec(woken.toHandle(),
                "psql -h localhost -U " + dbName + " -d " + dbName
                        + " -tAc \"select v from seal_probe\"").stdout().trim())
                .as("数据库数据随深度唤醒完整恢复").isEqualTo(FILE_MARKER);
        assertThat(backend.exec(woken.toHandle(),
                "curl -s -o /dev/null http://localhost:8081").ok())
                .as("应用起服（预览可用判据）").isTrue();
    }

    // ---------- 活体编排 ----------

    /**
     * 等封存收敛：期望态置封存 + 卷删除（删卷在意图落库之后，一并在窗内等到）。
     * 上界 120s：打包/落盘/删卷都是本机 IO，秒级——留余量。
     */
    private boolean awaitSealed(String containerName) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(120).toMillis();
        while (System.currentTimeMillis() < deadline) {
            Workspace current = workspaceRepository.findById(workspaceId.id()).orElse(null);
            if (current != null && current.getDesiredState() == DesiredState.SEALED
                    && dockerExitCode("docker", "volume", "inspect", "vol-" + containerName) != 0) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private Workspace provisionReadyWorkspace() {
        workspaceId = WorkspaceId.generate();
        workspaceRepository.save(Workspace.registerPending(workspaceId, EnvKind.DEV));
        provisioner.provisionForWake(workspaceId, EnvKind.DEV);   // 同步幂等置备
        return workspaceRepository.findById(workspaceId.id()).orElseThrow();
    }

    /**
     * 等深度唤醒收敛：容器回来 + 应用在服（依赖重装含在内——封存包排除了
     * node_modules，起服前必有 pnpm install 冷链路）。锚物理实态，不只等 READY。
     */
    private boolean awaitDeepWoken(String containerName) {
        long deadline = System.currentTimeMillis() + DEEP_WAKE_DEADLINE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (dockerExitCode("docker", "inspect", containerName) == 0
                    && backend.exec(WorkspaceHandle.dev(workspaceId, containerName,
                            "previewnet"), "curl -s -o /dev/null http://localhost:8081").ok()) {
                return true;
            }
            sleep();
        }
        return false;
    }

    /** 封存包存储走真实现（临时目录）——活体验收「包落盘」的实物面。 */
    private SealPackageStore sealPackageStore() {
        WorkspaceProperties sealProperties = new WorkspaceProperties();
        sealProperties.setSealArchiveDir(archiveDir.toString());
        return new LocalDiskSealPackageStore(sealProperties);
    }

    private String hostTarListing(Path archive) throws Exception {
        Process p = new ProcessBuilder("tar", "-tzf", archive.toString()).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }

    private int dockerExitCode(String... cmd) {
        try {
            return new ProcessBuilder(cmd).start().waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    private void sleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }

    private static void requireDockerDaemon() {
        boolean available;
        try {
            available = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .start().waitFor() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "本机 docker daemon 不在，跳过活体验收");
    }
}
