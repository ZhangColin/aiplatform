package com.aieducenter.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 休眠链路集成验收（#171 AC，真库 + Docker CLI 假面 seam）：编排、落库、事件全真，
 * 只有 docker 命令子进程是假面（{@code runCapture} 覆写——命令形状与退出码可编）。
 * 真 daemon 的「删容器保卷、唤醒数据还在」验收见 {@code WorkspaceHibernationLiveTest}。
 */
@IntegrationTest
class WorkspaceHibernationIntegrationTest {

    /** 假面 docker CLI：探查结果可编（默认全命令成功），命令全记录（断言形状）。 */
    static final class FakeDockerCli extends DockerEnvironmentBackend {
        final List<String> commands = new ArrayList<>();
        volatile boolean containerRunning = true;

        @Override
        protected ExecResult runCapture(String... cmd) {
            commands.add(String.join(" ", cmd));
            if ("inspect".equals(cmd[1]) && "-f".equals(cmd[2])) {
                // 容器实态探查：编死值；exit 1 = 不存在/被杀（与真实 docker 口径一致）
                return containerRunning
                        ? new ExecResult("true\n", "", 0)
                        : new ExecResult("", "no such container", 1);
            }
            return new ExecResult("", "", 0);
        }

        boolean removedContainer(String containerName) {
            return commands.contains("docker rm -f " + containerName);
        }

        /** 卷零删（保卷）：休眠/收敛轮不发 docker volume rm（幂等重建的 volume create 在途可发）。 */
        boolean removedVolume() {
            return commands.stream().anyMatch(c -> c.startsWith("docker volume rm"));
        }
    }

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private WorkspaceProperties properties;

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
        assertThat(backend.removedVolume()).isFalse();
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
        assertThat(backend.removedVolume()).isFalse();
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
        assertThat(backend.removedVolume()).isFalse();
        Workspace workspace = workspaceRepository.findById(id.id()).orElseThrow();
        assertThat(workspace.getDesiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(workspace.getStatus()).isEqualTo(ProvisioningStatus.READY);
    }

    // ---------- 装配 ----------

    /** 假面后端 + 真库/真事务/真编排（含真置备器——假面 CLI 全成功下重建全程可跑）。 */
    private WorkspaceHibernationAppService newHibernationService(FakeDockerCli backend) {
        WorkspaceProvisionAppService provisioner = new WorkspaceProvisionAppService(
                backend, workspaceRepository, 1, Runnable::run);
        WorkspaceLifecycleAppService lifecycle = new WorkspaceLifecycleAppService(
                backend, workspaceRepository, transactionTemplate,
                mock(ApplicationEventPublisher.class), mock(WorkspaceMapper.class),
                provisioner, mock(WorkspaceReadinessWaiter.class), properties, Runnable::run);
        return new WorkspaceHibernationAppService(
                backend, workspaceRepository, lifecycle, properties);
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
}
