package com.aieducenter.aiplatform.base.workspace.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闲置休眠活体验收（#171 AC，真 docker daemon + 真库 + 真编排——非假面）：
 * last-touch 拨过阈值 → 扫描一轮 → 容器被删、卷保留、期望态置休眠；再触碰项目
 * （唤醒路径）→ 幂等重建 + 卷内数据原样还在（删容器保卷的完整性收口）。daemon
 * 不在本机时跳过。
 */
@IntegrationTest
class WorkspaceHibernationLiveTest {

    /** 等待唤醒收敛的上界（重建容器 + 中间件就绪）。 */
    private static final Duration WAKE_DEADLINE = Duration.ofSeconds(120);

    /** 卷内数据探针（休眠前写入、唤醒后应原样读回）。 */
    private static final String MARKER_COMMAND =
            "printf hibernate-probe > /workspace/.hibernate-probe";

    @Autowired
    private WorkspaceLifecycleAppService lifecycle;

    @Autowired
    private WorkspaceProvisionAppService provisioner;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DockerEnvironmentBackend backend;

    @Autowired
    private WorkspaceProperties properties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private SealPackageStore sealPackageStore;

    private WorkspaceId workspaceId;

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
    }

    @Test
    void given_idle_workspace_when_scan_then_container_gone_volume_kept_then_wake_data_intact() {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();
        String containerName = workspace.getContainerName();

        // 卷内种数据（工作区唯一持久卷；休眠不动它）
        assertThat(backend.exec(workspace.toHandle(), MARKER_COMMAND).exitCode()).isZero();

        // 闲置：last-touch 拨过阈值（真实形态 = 用户 60 分钟没碰过项目）
        workspace.markTouched(LocalDateTime.now().minus(properties.getIdleThreshold())
                .minusMinutes(1));
        workspaceRepository.save(workspace);

        int acted = new WorkspaceHibernationAppService(
                backend, workspaceRepository, lifecycle, sealPackageStore,
                transactionTemplate, properties)
                .scanOnce(Map.of(), LocalDateTime.now());

        // 容器被删、卷保留、期望态置休眠（置备态保持 READY——实态以探查为准）
        assertThat(acted).isEqualTo(1);
        assertThat(dockerExitCode("docker", "inspect", containerName))
                .as("休眠后容器应已删").isNotZero();
        assertThat(dockerExitCode("docker", "volume", "inspect", "vol-" + containerName))
                .as("休眠后卷应保留").isZero();
        Workspace hibernated = workspaceRepository.findById(workspaceId.id()).orElseThrow();
        assertThat(hibernated.getDesiredState()).isEqualTo(DesiredState.HIBERNATED);
        assertThat(hibernated.getStatus()).isEqualTo(ProvisioningStatus.READY);
        // 旁路直读卷（入口旁路容器）：休眠态下卷内数据原样可读
        assertThat(volumeProbe(containerName)).isEqualTo("hibernate-probe");

        // 唤醒（项目 API 触碰同路径）：重建 + 卷内数据原样还在
        lifecycle.touch(workspaceId.value(), false);
        assertThat(awaitWoken())
                .as("触碰休眠工作区：幂等重建（容器回来 + READY）").isTrue();
        Workspace woken = workspaceRepository.findById(workspaceId.id()).orElseThrow();
        assertThat(woken.getDesiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(backend.exec(woken.toHandle(), "cat /workspace/.hibernate-probe").stdout())
                .isEqualTo("hibernate-probe");
    }

    // ---------- 活体编排 ----------

    private Workspace provisionReadyWorkspace() {
        workspaceId = WorkspaceId.generate();
        workspaceRepository.save(Workspace.registerPending(workspaceId, EnvKind.DEV));
        provisioner.provisionForWake(workspaceId, EnvKind.DEV);   // 同步幂等置备
        return workspaceRepository.findById(workspaceId.id()).orElseThrow();
    }

    /**
     * 等唤醒收敛：容器回来 + 置备态 READY。不能只等 READY——休眠保持 READY 置备态，
     * 触碰的异步自愈启动前轮询会读到休眠前的旧 READY 行（竞态假通过），必须锚
     * 物理实态（容器在跑）与置备态同时成立。
     */
    private boolean awaitWoken() {
        long deadline = System.currentTimeMillis() + WAKE_DEADLINE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Workspace current = workspaceRepository.findById(workspaceId.id()).orElse(null);
            if (current != null && current.getStatus() == ProvisioningStatus.READY
                    && backend.containerState(current.toHandle()) == ContainerState.RUNNING) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private int dockerExitCode(String... cmd) {
        try {
            return new ProcessBuilder(cmd).start().waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 旁路容器直读卷内探针文件（入口旁路，不起中间件）。 */
    private String volumeProbe(String containerName) {
        try {
            Process p = new ProcessBuilder("docker", "run", "--rm", "--entrypoint", "cat",
                    "-v", "vol-" + containerName + ":/workspace",
                    "aiplatform/dev:0.8", "/workspace/.hibernate-probe")
                    .redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "<probe failed: " + e.getMessage() + ">";
        }
    }

    private void sleep() {
        try {
            Thread.sleep(1000);
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
