package com.aieducenter.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.WorkspaceAction;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.enums.WorkspaceActionKind;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.Operator;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceActionRepository;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台沙箱动作用例（#174）：纯 Mockito 直测——四动作的守卫链（run 在途拒、状态
 * 拒、非 DEV 拒、互斥在途拒）与副作用序（删容器保卷 / rm＋幂等重建 / 封存四步），
 * 操作者留痕（append-only 动作行）。唤醒重建内核（wakeUp）归
 * {@code WorkspaceWakeupOrchestrationTest}，真库＋docker 假面签名全链见
 * {@code BackofficeWorkspaceActionSeamTest}。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceActionAppServiceTest {

    private static final long WS_ID = 710101L;

    /** 管理员操作者（签名面透传头口径：TSID＋昵称）。 */
    private static final Operator ADMIN = new Operator("900001", "运营同学");

    @Mock
    private EnvironmentBackend environmentBackend;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceLifecycleAppService lifecycle;

    @Mock
    private SealPackageStore sealPackageStore;

    @Mock
    private WorkspaceActionRepository actionRepository;

    @Mock
    private WorkspaceReadinessWaiter readinessWaiter;

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

    @Test
    void given_running_workspace_when_force_hibernate_then_container_removed_intent_flipped_action_journaled() {
        Workspace workspace = seeded(DesiredState.RUNNING);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 互斥面：同步直通（当前线程持锁执行）
        when(lifecycle.runExclusivelyBlocking(eq(workspace.workspaceId()), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return true;
                });

        newService().forceHibernate(Long.toString(WS_ID), false, ADMIN);

        // 删容器保卷（物理先动）＋意图落休眠（库内行断言）
        verify(environmentBackend).hibernate(workspace.toHandle());
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getDesiredState()).isEqualTo(DesiredState.HIBERNATED);
        // 操作者留痕：append-only 动作行（who/what/when）
        ArgumentCaptor<WorkspaceAction> journaled = ArgumentCaptor.forClass(WorkspaceAction.class);
        verify(actionRepository).save(journaled.capture());
        assertThat(journaled.getValue().getWorkspaceId()).isEqualTo(WS_ID);
        assertThat(journaled.getValue().getAction()).isEqualTo(WorkspaceActionKind.HIBERNATE);
        assertThat(journaled.getValue().getOperatorId()).isEqualTo("900001");
        assertThat(journaled.getValue().getOperatorName()).isEqualTo("运营同学");
        assertThat(journaled.getValue().getActedAt()).isNotNull();
    }

    @Test
    void given_run_in_flight_when_force_hibernate_then_refused_wsp_015_no_side_effect() {
        Workspace workspace = seeded(DesiredState.RUNNING);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> newService().forceHibernate(Long.toString(WS_ID), true, ADMIN))
                .isInstanceOf(ApplicationException.class)
                .hasMessage(WorkspaceMessage.WORKSPACE_ACTION_RUN_IN_FLIGHT.message());

        verify(environmentBackend, never()).hibernate(any());
        verify(actionRepository, never()).save(any());
    }

    // ---------- 唤醒（等就绪；run 在途不受限） ----------

    @Test
    void given_healthy_running_workspace_when_wake_then_ready_as_is_without_rebuild() {
        Workspace workspace = seeded(DesiredState.RUNNING);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(environmentBackend.isContainerRunning(workspace.toHandle())).thenReturn(true);

        newService().wake(Long.toString(WS_ID), false, ADMIN);

        // 意图/实态一致：不翻状态零落库、不进重建内核
        verify(workspaceRepository, never()).save(any(Workspace.class));
        verify(lifecycle, never()).wakeUp(any(), anyBoolean());
        verify(environmentBackend, never()).startApp(any());
        verify(actionRepository).save(any(WorkspaceAction.class));   // 唤醒也是写口：留痕
    }

    @Test
    void given_healthy_running_workspace_when_wake_generated_then_app_started() {
        Workspace workspace = seeded(DesiredState.RUNNING);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(environmentBackend.isContainerRunning(workspace.toHandle())).thenReturn(true);

        newService().wake(Long.toString(WS_ID), true, ADMIN);

        verify(environmentBackend).startApp(workspace.toHandle());   // 幂等拉起（已在服直回）
    }

    @Test
    void given_hibernated_intent_with_leftover_container_when_wake_then_intent_aligned_running() {
        // 期望休眠而实态在跑（休眠删容器失败残留/外部重建的漂移形）：唤醒对齐意图，
        // 否则扫描器按休眠意图再删容器——唤醒被静默撤销
        Workspace workspace = seeded(DesiredState.HIBERNATED);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(environmentBackend.isContainerRunning(workspace.toHandle())).thenReturn(true);

        newService().wake(Long.toString(WS_ID), false, ADMIN);

        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getDesiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(saved.getValue().getLastTouchAt()).isNotNull();   // 闲置窗口重新计
        verify(actionRepository).save(any(WorkspaceAction.class));
    }

    @Test
    void given_dead_container_when_wake_then_wakeup_kernel_driven_and_ready_awaited() {
        Workspace workspace = seeded(DesiredState.RUNNING);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(environmentBackend.isContainerRunning(workspace.toHandle())).thenReturn(false);
        when(lifecycle.runExclusivelyBlocking(eq(workspace.workspaceId()), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return true;
                });
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().wake(Long.toString(WS_ID), true, ADMIN);

        // 唤醒内核（rewake→幂等重建→拉应用）在同进程直驱，同步等就绪
        verify(lifecycle).wakeUp(workspace, true);
        verify(readinessWaiter).awaitReady(any());
        verify(actionRepository).save(any(WorkspaceAction.class));
    }

    @Test
    void given_sealed_workspace_when_wake_and_package_unreadable_then_wsp_016_not_journaled() {
        Workspace workspace = seeded(DesiredState.SEALED);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(lifecycle.runExclusivelyBlocking(eq(workspace.workspaceId()), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return true;
                });
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));
        // wakeUp 对不可读包保持封存态（数据完整性优先，内核吞掉不翻意图）——mock
        // 直证：wakeUp 打桩为 no-op，工作区仍是 SEALED

        assertThatThrownBy(() -> newService().wake(Long.toString(WS_ID), true, ADMIN))
                .isInstanceOf(ApplicationException.class)
                .hasMessage(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE.message());

        verify(lifecycle).wakeUp(workspace, true);
        verify(actionRepository, never()).save(any());   // 未执行成的不留痕
    }

    // ---------- 互斥在途拒（WSP_017：如实回忙，不排队） ----------

    @Test
    void given_convergence_task_in_flight_when_heavy_action_then_refused_wsp_017() {
        // 触碰自愈/扫描封存等收敛任务持有互斥面：重活动作不排队、如实回忙
        Workspace workspace = seeded(DesiredState.RUNNING);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(lifecycle.runExclusivelyBlocking(eq(workspace.workspaceId()), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> newService().forceHibernate(Long.toString(WS_ID), false, ADMIN))
                .isInstanceOf(ApplicationException.class)
                .hasMessage(WorkspaceMessage.WORKSPACE_ACTION_BUSY.message());

        verify(environmentBackend, never()).hibernate(any());
        verify(actionRepository, never()).save(any());
    }

    // ---------- 强制重建（rm＋幂等重建；#168 型「预览死了」的标准化处置） ----------

    @Test
    void given_running_workspace_when_force_rebuild_then_container_forcibly_removed_and_rebuilt() {
        Workspace workspace = seeded(DesiredState.RUNNING);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(lifecycle.runExclusivelyBlocking(eq(workspace.workspaceId()), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return true;
                });
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().forceRebuild(Long.toString(WS_ID), true, false, ADMIN);

        // rm 先行（「强制」的意义：在跑但坏了的容器也杀）→ 同一唤醒内核重建 → 等就绪
        verify(environmentBackend).hibernate(workspace.toHandle());
        verify(lifecycle).wakeUp(any(Workspace.class), eq(true));
        verify(readinessWaiter).awaitReady(any());
        verify(actionRepository).save(any(WorkspaceAction.class));
    }

    @Test
    void given_sealed_workspace_when_force_rebuild_then_refused_wsp_009_volume_protected() {
        Workspace workspace = seeded(DesiredState.SEALED);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> newService().forceRebuild(Long.toString(WS_ID), true, false, ADMIN))
                .isInstanceOf(ApplicationException.class)
                .hasMessage(WorkspaceMessage.WORKSPACE_STATE_INVALID.message());

        // 封存态卷已删、数据只在包里：重建会拿空卷顶替——拒绝且零物理副作用
        verify(environmentBackend, never()).hibernate(any());
        verify(lifecycle, never()).wakeUp(any(), anyBoolean());
    }

    // ---------- 封存（产物同自动封存：打包→落盘→意图→删卷） ----------

    @Test
    void given_running_workspace_when_seal_then_same_sequence_as_auto_seal_from_running() {
        Workspace workspace = seeded(DesiredState.RUNNING);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(lifecycle.runExclusivelyBlocking(eq(workspace.workspaceId()), any()))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(1).run();
                    return true;
                });
        when(environmentBackend.packVolume(workspace.toHandle()))
                .thenReturn("卷内容".getBytes());
        when(sealPackageStore.save(eq(workspace.workspaceId()), any()))
                .thenReturn(new SealPackage("/sealed/ws-710101.tar.gz", 12L));
        when(environmentBackend.deleteVolume(workspace.toHandle())).thenReturn(true);

        newService().seal(Long.toString(WS_ID), false, ADMIN);

        // 动作序（数据安全定序，同自动封存 sealNow）：删容器 → 意图跳（RUNNING→
        // HIBERNATED）→ 打包 → 落盘 → 意图置封存＋元数据 → 删卷
        InOrder inOrder = inOrder(environmentBackend, sealPackageStore, workspaceRepository);
        inOrder.verify(environmentBackend).hibernate(workspace.toHandle());
        inOrder.verify(workspaceRepository).save(any(Workspace.class));
        inOrder.verify(environmentBackend).packVolume(workspace.toHandle());
        inOrder.verify(sealPackageStore).save(eq(workspace.workspaceId()), any());
        inOrder.verify(workspaceRepository).save(any(Workspace.class));
        inOrder.verify(environmentBackend).deleteVolume(workspace.toHandle());
        // 终态行断言（末次保存＝封存意图＋元数据）
        ArgumentCaptor<Workspace> saves = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(2)).save(saves.capture());
        Workspace sealedRow = saves.getAllValues().get(1);
        assertThat(sealedRow.getDesiredState()).isEqualTo(DesiredState.SEALED);
        assertThat(sealedRow.getArchivePath()).isEqualTo("/sealed/ws-710101.tar.gz");
        assertThat(sealedRow.getArchiveSizeBytes()).isEqualTo(12L);
        ArgumentCaptor<WorkspaceAction> journaled = ArgumentCaptor.forClass(WorkspaceAction.class);
        verify(actionRepository).save(journaled.capture());
        assertThat(journaled.getValue().getAction()).isEqualTo(WorkspaceActionKind.SEAL);
    }

    @Test
    void given_sealed_workspace_when_seal_then_refused_wsp_009_no_repack() {
        Workspace workspace = seeded(DesiredState.SEALED);
        when(workspaceRepository.findById(WS_ID)).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> newService().seal(Long.toString(WS_ID), false, ADMIN))
                .isInstanceOf(ApplicationException.class)
                .hasMessage(WorkspaceMessage.WORKSPACE_STATE_INVALID.message());

        // 重复封存不覆盖既有包记录（要再封走「唤醒→休眠→封存」周期）
        verify(environmentBackend, never()).packVolume(any());
        verify(sealPackageStore, never()).save(any(), any());
    }

    // -------- 夹具 --------

    private WorkspaceActionAppService newService() {
        return new WorkspaceActionAppService(environmentBackend, workspaceRepository,
                transactionTemplate, lifecycle, sealPackageStore, readinessWaiter,
                actionRepository);
    }

    /** READY＋DEV 工作区（期望态按需迁移；SEAL 起点=休眠→封存）。 */
    private Workspace seeded(DesiredState desired) {
        WorkspaceId id = WorkspaceId.of(Long.toString(WS_ID));
        Workspace workspace = Workspace.dev(id, WorkspaceNaming.containerName(id),
                WorkspaceNaming.PREVIEW_NETWORK);
        if (desired == DesiredState.HIBERNATED) {
            workspace.hibernate();
        } else if (desired == DesiredState.SEALED) {
            workspace.hibernate();
            workspace.seal(new SealPackage("/sealed/ws-710101.tar.gz", 4096L),
                    LocalDateTime.of(2026, 9, 15, 10, 0));
        }
        return workspace;
    }
}
