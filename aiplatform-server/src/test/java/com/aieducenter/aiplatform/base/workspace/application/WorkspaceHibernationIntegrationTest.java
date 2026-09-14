package com.aieducenter.aiplatform.base.workspace.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.event.ApplicationEventPublisher;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.application.mapper.WorkspaceMapper;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.base.workspace.infrastructure.archive.LocalDiskSealPackageStore;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 休眠/封存链路集成验收（#171/#172 AC，真库 + Docker CLI 假面 seam）：编排、落库、
 * 封存包文件全真，只有 docker 命令子进程是假面（{@code runCapture}/{@code runCaptureBinary}
 * 覆写——命令形状与退出码可编，tar 命令同收口断言）。真 daemon 的「删容器保卷、
 * 封存→深度唤醒数据完整」验收见 {@code WorkspaceHibernationLiveTest}/
 * {@code WorkspaceSealLiveTest}。
 */
@IntegrationTest
class WorkspaceHibernationIntegrationTest {

    /** 假面 docker CLI：探查结果可编（默认全命令成功），命令全记录（断言形状）。 */
    static final class FakeDockerCli extends DockerEnvironmentBackend {
        final List<String> commands = new ArrayList<>();
        final List<String> binaryCommands = new ArrayList<>();
        volatile boolean containerRunning = true;
        volatile boolean volumePresent = true;
        volatile byte[] packResult = "fake-archive".getBytes();

        @Override
        protected ExecResult runCapture(String... cmd) {
            commands.add(String.join(" ", cmd));
            if ("inspect".equals(cmd[1]) && "-f".equals(cmd[2])) {
                // 容器实态探查：编死值；exit 1 = 不存在/被杀（与真实 docker 口径一致）
                return containerRunning
                        ? new ExecResult("true\n", "", 0)
                        : new ExecResult("", "no such container", 1);
            }
            if ("volume".equals(cmd[1]) && "inspect".equals(cmd[2])) {
                return volumePresent
                        ? new ExecResult("[{}]\n", "", 0)
                        : new ExecResult("", "no such volume", 1);
            }
            return new ExecResult("", "", 0);
        }

        @Override
        protected ByteExec runCaptureBinary(byte[] stdin, String... cmd) {
            binaryCommands.add(String.join(" ", cmd));
            return new ByteExec(packResult, "", 0);
        }

        boolean removedContainer(String containerName) {
            return commands.contains("docker rm -f " + containerName);
        }

        boolean removedVolume(String containerName) {
            return commands.contains("docker volume rm vol-" + containerName);
        }
    }

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WorkspaceProperties properties;

    @TempDir
    private Path archiveDir;

    private final List<WorkspaceId> seeded = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (WorkspaceId id : seeded) {
            workspaceRepository.findById(id.id()).ifPresent(workspaceRepository::delete);
        }
    }

    @Test
    void given_idle_workspace_when_scan_once_then_container_removed_volume_kept_intent_hibernated() {
        FakeDockerCli backend = new FakeDockerCli();
        WorkspaceId id = seedReadyWorkspace(LocalDateTime.now().minusHours(2));

        int acted = newHibernationService(backend)
                .scanOnce(Map.of(), LocalDateTime.now());

        assertThat(acted).isEqualTo(1);
        // 容器删、卷零删（保卷——整轮不发 docker volume rm）
        assertThat(backend.removedContainer("ws-" + id.value())).isTrue();
        assertThat(backend.removedVolume("ws-" + id.value())).isFalse();
        // 意图落库：期望态休眠、置备态保持 READY（记录反映上次置备成功）
        Workspace workspace = workspaceRepository.findById(id.id()).orElseThrow();
        assertThat(workspace.getDesiredState()).isEqualTo(DesiredState.HIBERNATED);
        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.READY);
    }

    @Test
    void given_recently_touched_workspace_when_scan_once_then_untouched() {
        FakeDockerCli backend = new FakeDockerCli();
        WorkspaceId id = seedReadyWorkspace(LocalDateTime.now().minusMinutes(10));

        int acted = newHibernationService(backend)
                .scanOnce(Map.of(), LocalDateTime.now());

        // 阈值内触碰过：不动（不删容器、不改意图）
        assertThat(acted).isZero();
        assertThat(backend.removedVolume("ws-" + id.value())).isFalse();
        Workspace workspace = workspaceRepository.findById(id.id()).orElseThrow();
        assertThat(workspace.getDesiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(backend.commands.stream().noneMatch(c -> c.startsWith("docker rm"))).isTrue();
    }

    @Test
    void given_run_in_flight_and_dead_container_when_scan_once_then_not_hibernated_but_rebuilt() {
        FakeDockerCli backend = new FakeDockerCli();
        backend.containerRunning = false;   // 容器实死（#168 型漂移）
        WorkspaceId id = seedReadyWorkspace(LocalDateTime.now().minusHours(8));

        int acted = newHibernationService(backend).scanOnce(
                Map.of(id.id(), new WorkspaceScanFact(true, false)), LocalDateTime.now());

        // run 在途恒活跃：即使闲置超阈值也不休眠；期望运行而实死 → 收敛重建回 READY
        assertThat(acted).isEqualTo(1);
        assertThat(backend.removedVolume("ws-" + id.value())).isFalse();
        Workspace workspace = workspaceRepository.findById(id.id()).orElseThrow();
        assertThat(workspace.getDesiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.READY);
    }

    // ---------- 封存与深度唤醒（#172 AC） ----------

    @Test
    void given_hibernated_over_seal_threshold_when_scan_once_then_volume_deleted_package_on_disk() throws IOException {
        FakeDockerCli backend = new FakeDockerCli();
        backend.packResult = "archive-with-db".getBytes();
        WorkspaceId id = seedHibernatedWorkspace(LocalDateTime.now().minusDays(31));

        int acted = newHibernationService(backend)
                .scanOnce(Map.of(), LocalDateTime.now());

        assertThat(acted).isEqualTo(1);
        // tar 命令收口（假面 seam 断言形状）：旁路容器整卷打包、仅排除三大可重建缓存
        assertThat(backend.binaryCommands).hasSize(1);
        String pack = backend.binaryCommands.get(0);
        assertThat(pack).contains("docker run --rm --entrypoint tar");
        assertThat(pack).contains("-v vol-ws-" + id.value() + ":/workspace");
        assertThat(pack).contains("--exclude=./node_modules --exclude=./.pnpm-store --exclude=./.next");
        assertThat(pack).contains("-C /workspace .");
        // 包落盘（数据库随包口径 = 整卷 tar，无其他排除）+ 卷删除
        Workspace workspace = workspaceRepository.findById(id.id()).orElseThrow();
        assertThat(workspace.getDesiredState()).isEqualTo(DesiredState.SEALED);
        assertThat(workspace.getArchivePath()).isNotNull();
        Path sealedFile = Path.of(workspace.getArchivePath());
        assertThat(sealedFile).exists();
        assertThat(workspace.getArchiveSizeBytes()).isEqualTo(Files.size(sealedFile));
        assertThat(workspace.getSealedAt()).isNotNull();
        assertThat(backend.removedVolume("ws-" + id.value())).isTrue();
    }

    @Test
    void given_sealed_workspace_when_touch_then_deep_wake_restores_before_rebuild() {
        FakeDockerCli backend = new FakeDockerCli();
        backend.containerRunning = false;   // 封存态：容器必不在
        WorkspaceId id = seedSealedWorkspace("restored-by-deep-wake".getBytes());

        newLifecycleService(backend).touch(id.value(), true);

        // 解包回卷命令收口：重建卷（rm→create）+ 旁路容器 stdin 解包 + 清陈旧 pid
        String restore = String.join(" ", backend.binaryCommands);
        assertThat(restore).contains("docker run --rm -i --entrypoint sh");
        assertThat(restore).contains("tar xzf - -C /workspace && rm -f /workspace/data/pg/postmaster.pid");
        assertThat(backend.commands).containsSequence(
                "docker volume rm vol-ws-" + id.value(),
                "docker volume create vol-ws-" + id.value());
        // 次序：先解包回卷、后重建容器（createWorkspace 的 docker run -d）
        assertThat(backend.binaryCommands.get(0)).contains("--entrypoint sh");
        assertThat(backend.commands.indexOf("docker volume create vol-ws-" + id.value()))
                .isLessThan(containerRunIndex(backend));
        // 意图翻运行、置备收敛 READY（假面 CLI 全成功 → 重建全程可跑）；包保留
        Workspace workspace = workspaceRepository.findById(id.id()).orElseThrow();
        assertThat(workspace.getDesiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(Path.of(workspace.getArchivePath())).exists();
    }

    @Test
    void given_sealed_without_readable_package_when_touch_then_stays_sealed() throws IOException {
        FakeDockerCli backend = new FakeDockerCli();
        WorkspaceId id = seedSealedWorkspace("gone".getBytes());
        // 包被外部删除：open 不可读 → 保持封存态（不以空卷顶替），下次触碰再试
        Files.deleteIfExists(Path.of(workspaceRepository.findById(id.id()).orElseThrow()
                .getArchivePath()));

        newLifecycleService(backend).touch(id.value(), true);

        Workspace workspace = workspaceRepository.findById(id.id()).orElseThrow();
        assertThat(workspace.getDesiredState()).isEqualTo(DesiredState.SEALED);
        assertThat(backend.binaryCommands).isEmpty();   // 未解包、未重建
    }

    @Test
    void given_seal_wake_rehibernate_when_seal_again_then_old_package_overwritten() throws IOException {
        FakeDockerCli backend = new FakeDockerCli();
        backend.containerRunning = false;
        WorkspaceId id = seedHibernatedWorkspace(LocalDateTime.now().minusDays(31));
        WorkspaceLifecycleAppService lifecycle = newLifecycleService(backend);

        newHibernationService(backend).scanOnce(Map.of(), LocalDateTime.now());   // 第一次封存
        String firstPath = workspaceRepository.findById(id.id()).orElseThrow().getArchivePath();

        lifecycle.touch(id.value(), true);   // 深度唤醒（包保留）
        assertThat(workspaceRepository.findById(id.id()).orElseThrow().getDesiredState())
                .isEqualTo(DesiredState.RUNNING);

        // 再休眠（触碰拨新 + 意图回休眠）→ 再封存：旧包被覆盖（同路径、内容换新）
        Workspace rewoken = workspaceRepository.findById(id.id()).orElseThrow();
        rewoken.markTouched(LocalDateTime.now().minusDays(32));
        workspaceRepository.save(rewoken.hibernate());
        backend.packResult = "second-archive".getBytes();
        newHibernationService(backend).scanOnce(Map.of(), LocalDateTime.now());

        Workspace resealed = workspaceRepository.findById(id.id()).orElseThrow();
        assertThat(resealed.getDesiredState()).isEqualTo(DesiredState.SEALED);
        assertThat(resealed.getArchivePath()).isEqualTo(firstPath);
        assertThat(Files.readAllBytes(Path.of(firstPath))).isEqualTo("second-archive".getBytes());
        try (var files = Files.list(Path.of(firstPath).getParent())) {
            assertThat(files.count()).isEqualTo(1);   // 确定性命名：无旧包残留
        }
    }

    @Test
    void given_sealed_workspace_when_destroy_then_package_purged() throws IOException {
        FakeDockerCli backend = new FakeDockerCli();
        WorkspaceId id = seedSealedWorkspace("to-be-purged".getBytes());
        Path archivePath = Path.of(
                workspaceRepository.findById(id.id()).orElseThrow().getArchivePath());

        newLifecycleService(backend).destroy(id.value());

        assertThat(archivePath).doesNotExist();
        assertThat(workspaceRepository.findById(id.id())).isEmpty();
    }

    // ---------- 装配 ----------

    private int containerRunIndex(FakeDockerCli backend) {
        return backend.commands.stream()
                .map(c -> c.startsWith("docker run -d") ? backend.commands.indexOf(c) : -1)
                .max(Integer::compare).orElse(-1);
    }

    /** 假面后端 + 真库/真事务/真编排（含真置备器——假面 CLI 全成功下重建全程可跑）。 */
    private WorkspaceHibernationAppService newHibernationService(FakeDockerCli backend) {
        return new WorkspaceHibernationAppService(
                backend, workspaceRepository, newLifecycleService(backend),
                sealPackageStore(), transactionTemplate, properties);
    }

    /** 封存包存储走真实现（临时目录）——包落盘/覆盖/清理是本片验收物。 */
    private LocalDiskSealPackageStore sealPackageStore() {
        WorkspaceProperties sealProperties = new WorkspaceProperties();
        sealProperties.setSealArchiveDir(archiveDir.toString());
        return new LocalDiskSealPackageStore(sealProperties);
    }

    private WorkspaceLifecycleAppService newLifecycleService(FakeDockerCli backend) {
        WorkspaceProvisionAppService provisioner = new WorkspaceProvisionAppService(
                backend, workspaceRepository, 1, Runnable::run);
        return new WorkspaceLifecycleAppService(
                backend, workspaceRepository, transactionTemplate,
                mock(ApplicationEventPublisher.class), mock(WorkspaceMapper.class),
                provisioner, mock(WorkspaceReadinessWaiter.class), properties,
                sealPackageStore(), Runnable::run);
    }

    /** 种一棵 READY 工作区（真库），last-touch 拨到给定时刻。 */
    private WorkspaceId seedReadyWorkspace(LocalDateTime lastTouchAt) {
        WorkspaceId id = WorkspaceId.generate();
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(WorkspaceHandle.dev(
                id, WorkspaceNaming.containerName(id), WorkspaceNaming.PREVIEW_NETWORK)));
        pending.markTouched(lastTouchAt);
        workspaceRepository.save(pending);
        seeded.add(id);
        return id;
    }

    /** 种一棵休眠满期工作区（真库）：READY + 期望休眠 + last-touch 逾封存阈值。 */
    private WorkspaceId seedHibernatedWorkspace(LocalDateTime lastTouchAt) {
        WorkspaceId id = WorkspaceId.generate();
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(WorkspaceHandle.dev(
                id, WorkspaceNaming.containerName(id), WorkspaceNaming.PREVIEW_NETWORK)));
        pending.markTouched(lastTouchAt);
        workspaceRepository.save(pending.hibernate());
        seeded.add(id);
        return id;
    }

    /** 种一棵已封存工作区（真库 + 真包文件）：封存产物 = 本测试的深度唤醒输入。 */
    private WorkspaceId seedSealedWorkspace(byte[] archive) {
        WorkspaceId id = seedHibernatedWorkspace(LocalDateTime.now().minusDays(31));
        SealPackage pkg = sealPackageStore().save(id, archive);
        Workspace workspace = workspaceRepository.findById(id.id()).orElseThrow();
        workspaceRepository.save(workspace.seal(pkg, LocalDateTime.now()));
        return id;
    }
}
