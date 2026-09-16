package com.aieducenter.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 休眠/封存扫描编排（#171/#172，ADR-0016）：纯 Mockito 直测——扫描器每轮「先休眠
 * 判定、再封存判定、再探实态收敛」的分支次序与副作用（删容器保卷 + 意图落库；
 * 封存 = 打包→落盘→意图→删卷的独占任务）。真库 + Docker CLI 假面 seam 的链路见
 * {@code WorkspaceHibernationIntegrationTest}，真 daemon 的数据保全见
 * {@code WorkspaceHibernationLiveTest}/{@code WorkspaceSealLiveTest}。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceHibernationAppServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 12, 0);

    /** 封存假包（save 的回执形状）。 */
    private static final SealPackage PACKAGE =
            new SealPackage("/tmp/seal/ws-42.tar.gz", 1024L);

    @Mock
    private EnvironmentBackend environmentBackend;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceConvergenceAppService convergence;

    @Mock
    private SealPackageStore sealPackageStore;

    /** TransactionTemplate 测试替身：回调真跑（无事务管理器——编排逻辑不感知）。 */
    private final TransactionTemplate transactionTemplate = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> callback) {
            return callback.doInTransaction(null);
        }

        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) {
            action.accept(null);
        }
    };

    /** 闲置判定入参全显式给定（阈值 60m；触碰拨到 2 小时前 = 闲置，10 分钟前 = 活跃）。 */
    private final WorkspaceProperties properties = new WorkspaceProperties();

    @Test
    void given_idle_workspace_when_scan_then_container_removed_volume_kept_intent_hibernated() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusHours(2));
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));

        int acted = newService().scanOnce(Map.of(), NOW);

        assertThat(acted).isEqualTo(1);
        // 删容器保卷（物理先动），意图后落——置备态保持 READY
        verify(environmentBackend).hibernate(workspace.toHandle());
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getDesiredState()).isEqualTo(DesiredState.HIBERNATED);
        assertThat(saved.getValue().getStatus()).isEqualTo(ProvisioningStatus.READY);
    }

    @Test
    void given_recently_touched_workspace_when_scan_then_untouched() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusMinutes(10));
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);

        int acted = newService().scanOnce(Map.of(), NOW);

        // 阈值内触碰过：不删容器、不落库、不唤醒
        assertThat(acted).isZero();
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
        verify(workspaceRepository, never()).save(any(Workspace.class));
        verify(convergence, never()).convergeAsync(any(), any(), eq(true));
    }

    @Test
    void given_run_in_flight_when_scan_even_idle_then_not_hibernated_and_drift_healed() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusHours(8));   // 远超阈值
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);

        int acted = newService().scanOnce(
                Map.of(42L, new WorkspaceScanFact(true, true)), NOW);

        // run 在途恒活跃：不休眠；容器已死而期望运行 → 收敛模块 SCAN 面漂移收敛（#168 型不再无声）
        assertThat(acted).isEqualTo(1);
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
        verify(convergence).convergeAsync(workspace.workspaceId(), ConvergenceFace.SCAN, true);
    }

    @Test
    void given_active_workspace_with_dead_container_when_scan_then_drift_healed() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusMinutes(5));
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);

        int acted = newService().scanOnce(Map.of(), NOW);

        // 期望运行而容器实死（#168「DB 记 ready、实死两天」）：扫描轮收敛
        assertThat(acted).isEqualTo(1);
        verify(convergence).convergeAsync(workspace.workspaceId(), ConvergenceFace.SCAN, false);
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
    }

    @Test
    void given_hibernated_intent_with_leftover_container_when_scan_idle_then_container_removed() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusHours(3));
        workspace.hibernate();   // 构造意图残留形态：HIBERNATED + 容器仍在（删失败/外部重建）
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);

        int acted = newService().scanOnce(Map.of(), NOW);

        // 意图=休眠而容器在（且闲置）：删容器向意图收敛；意图已对，不重复落库
        assertThat(acted).isEqualTo(1);
        verify(environmentBackend).hibernate(workspace.toHandle());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_hibernated_and_converged_when_scan_then_noop() {
        Workspace workspace = readyWorkspace();
        workspace.hibernate();
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);

        int acted = newService().scanOnce(Map.of(), NOW);

        // 已休眠且容器已无：正合意图，无事可做
        assertThat(acted).isZero();
        verifyNoInteractions(convergence);
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
    }

    @Test
    void given_probe_unknown_when_scan_then_both_branches_deferred() {
        // #176：探查失败≠容器不在——重建（②）与删容器（③）两支都不走：盲动手的
        // rm 会杀可能健康的容器上的在途 run。本轮让路，下轮探查再收敛
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusMinutes(5));   // 期望运行且活跃（ABSENT 形态会触发②）
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.UNKNOWN);

        int acted = newService().scanOnce(Map.of(), NOW);

        assertThat(acted).isZero();
        verifyNoInteractions(convergence);
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
    }

    @Test
    void given_hibernation_disabled_when_scan_once_then_noop() {
        properties.setHibernationEnabled(false);   // 总开关内移（#197）：扫描入口自持

        int acted = newService().scanOnce(Map.of(), NOW);

        // 关闭即整轮静默：不触库、不探 docker、不收敛
        assertThat(acted).isZero();
        verifyNoInteractions(workspaceRepository, environmentBackend, convergence);
    }

    @Test
    void given_provisioning_or_non_dev_when_scan_then_skipped_without_probe() {
        Workspace provisioning = Workspace.registerPending(WorkspaceId.of("43"), EnvKind.DEV);
        Workspace runtime = Workspace.runtime(WorkspaceId.of("44"), EnvKind.TEST,
                "ws-44", "previewnet");
        when(workspaceRepository.findAll()).thenReturn(List.of(provisioning, runtime));

        int acted = newService().scanOnce(Map.of(), NOW);

        // 在途置备不扰（置备线程自会收敛）；非 DEV 沙箱不入休眠面（TEST/PROD 占位）
        assertThat(acted).isZero();
        verify(environmentBackend, never()).containerState(any(WorkspaceHandle.class));
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
    }

    @Test
    void given_hibernate_backend_failure_when_scan_then_intent_not_flipped() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusHours(2));
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        doThrow(new RuntimeException("daemon down"))
                .when(environmentBackend).hibernate(any(WorkspaceHandle.class));

        int acted = newService().scanOnce(Map.of(), NOW);

        // 删容器失败不让意图翻（翻了下轮不再重试删）：保持 RUNNING，下轮收敛
        assertThat(acted).isZero();
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    // ---------- 封存（#172） ----------

    @Test
    void given_hibernated_over_seal_threshold_when_scan_then_sealed_package_saved_volume_deleted() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusDays(31));   // 闲置 31 天：休眠满期（60m + 30d 之上）
        workspace.hibernate();   // 先拨时刻后落休眠（markTouched 会把休眠意图翻回运行）
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.packVolume(any(WorkspaceHandle.class)))
                .thenReturn(new byte[] {1, 2, 3});
        when(sealPackageStore.save(any(WorkspaceId.class), any())).thenReturn(PACKAGE);
        when(environmentBackend.deleteVolume(any(WorkspaceHandle.class))).thenReturn(true);
        runExclusivelySynchronously();

        int acted = newService().scanOnce(Map.of(), NOW);

        // 封存全链：打包 → 落盘 → 意图+元数据落库 → 删卷（次序断言）
        assertThat(acted).isEqualTo(1);
        var inOrder = inOrder(environmentBackend, sealPackageStore, workspaceRepository);
        // 打包前置自愈：休眠删容器曾静默失败时先幂等删容器（静默卷打包的前置）
        inOrder.verify(environmentBackend).hibernate(workspace.toHandle());
        inOrder.verify(environmentBackend).packVolume(workspace.toHandle());
        inOrder.verify(sealPackageStore).save(workspace.workspaceId(), new byte[] {1, 2, 3});
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        inOrder.verify(workspaceRepository).save(saved.capture());
        inOrder.verify(environmentBackend).deleteVolume(workspace.toHandle());
        assertThat(saved.getValue().getDesiredState()).isEqualTo(DesiredState.SEALED);
        assertThat(saved.getValue().getArchivePath()).isEqualTo(PACKAGE.path());
        assertThat(saved.getValue().getArchiveSizeBytes()).isEqualTo(PACKAGE.sizeBytes());
        assertThat(saved.getValue().getSealedAt()).isNotNull();
    }

    @Test
    void given_hibernated_but_under_seal_threshold_when_scan_then_not_sealed() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusDays(10));   // 闲置 10 天：休眠未满期
        workspace.hibernate();
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));

        int acted = newService().scanOnce(Map.of(), NOW);

        // 未满期不动：不打包、不删卷、不落库
        assertThat(acted).isZero();
        verify(environmentBackend, never()).packVolume(any(WorkspaceHandle.class));
        verify(environmentBackend, never()).deleteVolume(any(WorkspaceHandle.class));
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_hibernated_over_threshold_but_run_in_flight_when_scan_then_not_sealed() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusDays(40));
        workspace.hibernate();
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));

        newService().scanOnce(Map.of(42L, new WorkspaceScanFact(true, true)), NOW);

        // run 在途恒活跃：休眠/封存都不动手（封存绝不对活跃项目动手）
        verify(convergence, never()).runExclusively(any(), any());
        verify(environmentBackend, never()).packVolume(any(WorkspaceHandle.class));
    }

    @Test
    void given_pack_failure_when_seal_then_intent_not_flipped() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusDays(31));
        workspace.hibernate();
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        when(environmentBackend.packVolume(any(WorkspaceHandle.class)))
                .thenThrow(new RuntimeException("daemon down"));
        runExclusivelySynchronously();

        newService().scanOnce(Map.of(), NOW);

        // 打包失败：不落盘、不落库（意图保持休眠，下轮重试）、不删卷
        verify(sealPackageStore, never()).save(any(), any());
        verify(workspaceRepository, never()).save(any(Workspace.class));
        verify(environmentBackend, never()).deleteVolume(any(WorkspaceHandle.class));
    }

    @Test
    void given_record_deleted_during_seal_when_commit_then_just_saved_package_cleaned() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusDays(31));
        workspace.hibernate();
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        // 首读在（起封存）→ 事务内重取已删（销毁竞争）
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace), Optional.empty());
        when(environmentBackend.packVolume(any(WorkspaceHandle.class)))
                .thenReturn(new byte[] {1});
        when(sealPackageStore.save(any(WorkspaceId.class), any())).thenReturn(PACKAGE);
        runExclusivelySynchronously();

        newService().scanOnce(Map.of(), NOW);

        // 记录已删：刚落的包随手清（不留孤儿），不再动物理面
        verify(sealPackageStore).delete(PACKAGE.path());
        verify(environmentBackend, never()).deleteVolume(any(WorkspaceHandle.class));
    }

    @Test
    void given_sealed_with_leftover_volume_when_scan_then_volume_converged() {
        Workspace workspace = readyWorkspace();
        workspace.hibernate();
        workspace.seal(PACKAGE, NOW);
        workspace.markTouched(NOW.minusDays(40));
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        when(environmentBackend.deleteVolume(any(WorkspaceHandle.class))).thenReturn(true);
        runExclusivelySynchronously();

        int acted = newService().scanOnce(Map.of(), NOW);

        // 期望封存而卷仍在：删卷向意图收敛（独占任务内重取——与深度唤醒互斥；
        // 占卷的容器一并清）；意图已对不落库、不计数（静默卫生）
        assertThat(acted).isZero();
        verify(environmentBackend).deleteVolume(workspace.toHandle());
        verify(environmentBackend).hibernate(workspace.toHandle());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_sealed_residue_task_when_deep_wake_landed_first_then_no_delete() {
        Workspace sealed = readyWorkspace();
        sealed.hibernate();
        sealed.seal(PACKAGE, NOW);
        when(workspaceRepository.findAll()).thenReturn(List.of(sealed));
        // 任务内重取：让路期间已被深度唤醒（期望态翻运行）——卷是刚解包回来的
        Workspace woken = readyWorkspace();
        woken.markTouched(NOW);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(woken));
        runExclusivelySynchronously();

        newService().scanOnce(Map.of(), NOW);

        // 已出封存意图：绝不删卷（交错删卷 = 深度唤醒数据丢失，收敛任务重取防之）
        verify(environmentBackend, never()).deleteVolume(any(WorkspaceHandle.class));
    }

    // ---------- 测试数据 ----------

    private Workspace readyWorkspace() {
        Workspace pending = Workspace.registerPending(WorkspaceId.of("42"), EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(WorkspaceId.of("42"), "ws-42", "previewnet")));
        return pending;
    }

    private WorkspaceHibernationAppService newService() {
        return new WorkspaceHibernationAppService(
                environmentBackend, workspaceRepository, convergence, sealPackageStore,
                transactionTemplate, properties);
    }

    /** 独占提交直通（测试不异步）：封存任务同步跑，副作用次序可断言。 */
    private void runExclusivelySynchronously() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(convergence).runExclusively(any(WorkspaceId.class), any());
    }
}
