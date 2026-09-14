package com.aieducenter.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 休眠扫描编排（#171，ADR-0016）：纯 Mockito 直测——扫描器每轮「先休眠判定、再探
 * 实态收敛」的分支次序与副作用（删容器保卷 + 意图落库）。真库 + Docker CLI 假面
 * seam 的链路见 {@code WorkspaceHibernationIntegrationTest}，真 daemon 的数据保全
 * 见 {@code WorkspaceHibernationLiveTest}。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceHibernationAppServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 12, 0);

    @Mock
    private EnvironmentBackend environmentBackend;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceLifecycleAppService lifecycle;

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
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(true);

        int acted = newService().scanOnce(Map.of(), NOW);

        // 阈值内触碰过：不删容器、不落库、不唤醒
        assertThat(acted).isZero();
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
        verify(workspaceRepository, never()).save(any(Workspace.class));
        verify(lifecycle, never()).healDrift(any(), eq(true));
    }

    @Test
    void given_run_in_flight_when_scan_even_idle_then_not_hibernated_and_drift_healed() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusHours(8));   // 远超阈值
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(false);

        int acted = newService().scanOnce(
                Map.of(42L, new WorkspaceScanFact(true, true)), NOW);

        // run 在途恒活跃：不休眠；容器已死而期望运行 → 漂移收敛（#168 型不再无声）
        assertThat(acted).isEqualTo(1);
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
        verify(lifecycle).healDrift(workspace.workspaceId(), true);
    }

    @Test
    void given_active_workspace_with_dead_container_when_scan_then_drift_healed() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusMinutes(5));
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(false);

        int acted = newService().scanOnce(Map.of(), NOW);

        // 期望运行而容器实死（#168「DB 记 ready、实死两天」）：扫描轮收敛
        assertThat(acted).isEqualTo(1);
        verify(lifecycle).healDrift(workspace.workspaceId(), false);
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
    }

    @Test
    void given_hibernated_intent_with_leftover_container_when_scan_idle_then_container_removed() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(NOW.minusHours(3));
        workspace.hibernate();   // 构造意图残留形态：HIBERNATED + 容器仍在（删失败/外部重建）
        when(workspaceRepository.findAll()).thenReturn(List.of(workspace));
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(true);

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
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(false);

        int acted = newService().scanOnce(Map.of(), NOW);

        // 已休眠且容器已无：正合意图，无事可做
        assertThat(acted).isZero();
        verifyNoInteractions(lifecycle);
        verify(environmentBackend, never()).hibernate(any(WorkspaceHandle.class));
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
        verify(environmentBackend, never()).isContainerRunning(any(WorkspaceHandle.class));
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

    // ---------- 测试数据 ----------

    private Workspace readyWorkspace() {
        Workspace pending = Workspace.registerPending(WorkspaceId.of("42"), EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(WorkspaceId.of("42"), "ws-42", "previewnet")));
        return pending;
    }

    private WorkspaceHibernationAppService newService() {
        return new WorkspaceHibernationAppService(
                environmentBackend, workspaceRepository, lifecycle, properties);
    }
}
