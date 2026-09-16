package com.aieducenter.aiplatform.base.workspace.application;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.event.ApplicationEventPublisher;

import com.aieducenter.aiplatform.base.workspace.application.event.PreviewReady;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向活收敛模块判定矩阵（#196，编排缝）：期望态×实态×面经公开接口
 * （{@code convergeAsync}/{@code convergeBlocking}）锁全——伪后端按实态编程，
 * 断言动作（重建/深度唤醒/对齐/不动/抛错/让路）与拨针发生与否，不捅模块内部。
 * 纯 Mockito + 受控执行器（探查任务入队不自动跑——互斥窗口可观测）；真库收敛与
 * 事件时序见 {@code WorkspaceLifecycleAppServiceTest}（@IntegrationTest），真库 +
 * Docker CLI 假面链路见 {@code WorkspaceHibernationIntegrationTest}。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceConvergenceAppServiceTest {

    private static final WorkspaceId ID = WorkspaceId.of("42");

    /** 种入的旧触碰时刻：拨针断言的锚（拨了 = 晚于它，没拨 = 仍等于它）。 */
    private static final LocalDateTime OLD_TOUCH = LocalDateTime.of(2026, 9, 1, 12, 0);

    @Mock
    private EnvironmentBackend environmentBackend;

    @Mock
    private WorkspaceRepository workspaceRepository;

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

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private WorkspaceProvisionAppService provisioner;

    @Mock
    private WorkspaceReadinessWaiter readinessWaiter;

    @Mock
    private SealPackageStore sealPackageStore;

    /** 受控执行器：任务排队不跑（runQueued() 手动放行），互斥窗口可观测。 */
    static class QueuedExecutor implements Executor {
        final BlockingQueue<Runnable> queued = new ArrayBlockingQueue<>(16);

        void runQueued() {
            try {
                queued.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }
    }

    // ---------- TOUCH 面（异步；入口无条件拨针，让路也不丢） ----------

    @Test
    void given_provisioning_workspace_when_touch_then_pinned_and_no_probe() {
        Workspace pending = Workspace.registerPending(ID, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);

        // 入口无条件拨针（置备在途也拨——用户来过即活跃）
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getLastTouchAt()).isAfter(OLD_TOUCH);
        // 忙（首次置备/唤醒已在途）：不提交探查任务
        assertThat(executor.queued).isEmpty();
        verify(environmentBackend, never()).containerState(any(WorkspaceHandle.class));
    }

    @Test
    void given_container_dead_when_touch_then_rewake_rebuild_and_app_started() {
        // 载入序列：入口拨针 →（任务内）探查重取 → rewake 事务重取 → 收敛后重取——
        // 收敛后一律 READY
        stubLoads(readyWorkspace(), readyWorkspace(), readyWorkspace(), readyWorkspace());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();

        // 唤醒全链：rewake 落 PROVISIONING → 同步幂等重建 → 应用拉起 → 探活发事件
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Workspace::getStatus)
                .containsExactly(ProvisioningStatus.READY, ProvisioningStatus.PROVISIONING);
        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        verify(environmentBackend).startApp(any(WorkspaceHandle.class));
        verify(environmentBackend).exposePort(any(WorkspaceHandle.class), eq(8081));
        verify(eventPublisher).publishApplicationEvent(any(PreviewReady.class));
    }

    @Test
    void given_never_generated_when_touch_rebuilds_then_app_not_started() {
        stubLoads(readyWorkspace(), readyWorkspace(), readyWorkspace(), readyWorkspace());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        // 未生成工作区唤醒后探活必败（WSP_012 预期口径——恢复到未生成态）
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenThrow(new ApplicationException(WorkspaceMessage.PREVIEW_NOT_SERVING));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, false);
        executor.runQueued();

        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        // 从未跑过 run：不拉应用（预览保持未生成口径，探活失败吞掉不炸任务）
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
    }

    @Test
    void given_container_alive_when_touch_then_no_rebuild() {
        stubLoads(readyWorkspace(), readyWorkspace());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();

        // 实态健康（意图/实态一致）：不动
        verify(provisioner, never()).provisionForWake(any(), any());
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
    }

    @Test
    void given_probe_unknown_when_touch_then_no_rebuild_deferred() {
        // #176：探查失败≠容器不在——daemon 抖动不触发盲重建（预清 rm -f 会杀可能
        // 健康容器上的在途 run）。本轮让路，下次触碰/下轮扫描再收敛
        stubLoads(readyWorkspace(), readyWorkspace());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.UNKNOWN);
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();

        // UNKNOWN：不重建、不拉应用、不探活——同健康路径的「不动」，但下次探查可翻案
        verify(provisioner, never()).provisionForWake(any(), any());
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
        verify(environmentBackend, never()).exposePort(any(WorkspaceHandle.class), anyInt());
    }

    @Test
    void given_concurrent_touches_when_healing_in_flight_then_single_probe_submitted() {
        stubLoads(readyWorkspace(), readyWorkspace(), readyWorkspace());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);
        QueuedExecutor executor = new QueuedExecutor();

        WorkspaceConvergenceAppService service = newService(executor);
        service.convergeAsync(ID, ConvergenceFace.TOUCH, true);
        service.convergeAsync(ID, ConvergenceFace.TOUCH, true);   // 在途互斥：第二次不重复提交

        assertThat(executor.queued).hasSize(1);
        executor.runQueued();        // 完成后互斥解除

        service.convergeAsync(ID, ConvergenceFace.TOUCH, true);
        assertThat(executor.queued).hasSize(1);   // 可再次提交（下轮探查）
    }

    @Test
    void given_probe_throws_when_touch_then_swallowed_and_mutex_released() {
        stubLoads(readyWorkspace(), readyWorkspace(), readyWorkspace());
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenThrow(new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED));
        QueuedExecutor executor = new QueuedExecutor();

        WorkspaceConvergenceAppService service = newService(executor);
        service.convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();   // 探查异常吞掉（尽力而为），互斥释放

        service.convergeAsync(ID, ConvergenceFace.TOUCH, true);
        assertThat(executor.queued).hasSize(1);
    }

    @Test
    void given_wake_fails_to_failed_when_touch_then_app_not_started() {
        Workspace failed = Workspace.registerPending(ID, EnvKind.DEV)
                .markFailed("WSP_002：环境后端操作失败");
        // 收敛后重取为 FAILED（provisionForWake 全败落 FAILED 的模拟）
        stubLoads(readyWorkspace(), failed);
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();

        // 重试上限落 FAILED：不拉应用不发事件（可再触发——下次触碰 rewake 重来）
        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
        verify(eventPublisher, never()).publishApplicationEvent(any(PreviewReady.class));
    }

    // ---------- TOUCH 面深度唤醒（封存态触碰 = 解包回卷 + 同一重建路径） ----------

    @Test
    void given_sealed_with_package_when_touch_then_restored_before_rebuild_and_app_started() {
        // 载入序列：入口拨针 →（任务内）探查重取 → rewake 事务重取 → 收敛后重取
        // （四个独立实例：rewake 会原地迁移状态，共享实例会让收敛后重取读到 PROVISIONING）
        stubLoads(sealedWorkspace(), sealedWorkspace(), sealedWorkspace(), sealedWorkspace());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(sealPackageStore.open("/seal/ws-42.tar.gz")).thenReturn("archive".getBytes());
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();

        // 深度唤醒全链：解包回卷（物理先行）→ rewake → 重建 → 应用拉起（含依赖重装）
        var inOrder = inOrder(environmentBackend, provisioner);
        inOrder.verify(environmentBackend).restoreVolume(any(WorkspaceHandle.class),
                eq("archive".getBytes()));
        inOrder.verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        verify(environmentBackend).startApp(any(WorkspaceHandle.class));
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getDesiredState())
                .isEqualTo(DesiredState.RUNNING);
        // 包不删（再封存覆盖锚 + 删除项目的清理锚），元数据保留
        verify(sealPackageStore, never()).delete(any());
    }

    @Test
    void given_sealed_without_package_when_touch_then_plain_wake_on_fresh_volume() {
        // 无包可记（卷已失）形态：期望封存、元数据为空（四个独立实例，同上）
        stubLoads(bareSealedWorkspace(), bareSealedWorkspace(),
                bareSealedWorkspace(), bareSealedWorkspace());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenThrow(new ApplicationException(WorkspaceMessage.PREVIEW_NOT_SERVING));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();

        // 无包记录：按空卷普通唤醒（不解包），意图照翻运行
        verify(environmentBackend, never()).restoreVolume(any(), any());
        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
    }

    @Test
    void given_sealed_with_unreadable_package_when_touch_then_stays_sealed() {
        stubLoads(sealedWorkspace(), sealedWorkspace());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(sealPackageStore.open("/seal/ws-42.tar.gz"))
                .thenThrow(new UncheckedIOException(
                        new FileNotFoundException("/seal/ws-42.tar.gz")));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();

        // 包不可读：保持封存态待人工介入（不以空卷顶替数据丢失），不重建不拉应用
        verify(environmentBackend, never()).restoreVolume(any(), any());
        verify(provisioner, never()).provisionForWake(any(), any());
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(1)).save(saved.capture());   // 只有入口拨针
        assertThat(saved.getValue().getDesiredState())
                .isEqualTo(DesiredState.SEALED);
    }

    @Test
    void given_restore_fails_when_touch_then_sealed_intent_kept_for_retry() {
        stubLoads(sealedWorkspace(), sealedWorkspace());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(sealPackageStore.open("/seal/ws-42.tar.gz")).thenReturn("archive".getBytes());
        doThrow(new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED))
                .when(environmentBackend).restoreVolume(any(WorkspaceHandle.class), any());
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();   // 解包失败被自愈任务吞掉（尽力而为）

        // 解包失败：意图不动（仍封存），下次触碰/下轮扫描再试
        verify(provisioner, never()).provisionForWake(any(), any());
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getDesiredState())
                .isEqualTo(DesiredState.SEALED);
    }

    // ---------- SCAN 面（异步；平台内部自愈不拨针） ----------

    @Test
    void given_dead_container_when_scan_then_rebuilt_without_pin() {
        // 载入序列：任务内探查重取 → rewake 事务重取 → 收敛后重取（READY）
        stubLoads(oldTouchedReady(), oldTouchedReady(), oldTouchedReady());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.SCAN, true);
        executor.runQueued();

        // 漂移收敛重建（期望运行而实死，#168 型）＋已生成拉应用；只 rewake 落库一次
        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        verify(environmentBackend).startApp(any(WorkspaceHandle.class));
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(1)).save(saved.capture());
        // 扫描面不拨针：闲置钟保持旧值（平台内部自愈不是活跃信号）
        assertThat(saved.getValue().getLastTouchAt()).isEqualTo(OLD_TOUCH);
    }

    @Test
    void given_probe_unknown_when_scan_then_deferred() {
        stubLoads(oldTouchedReady());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.UNKNOWN);
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.SCAN, false);
        executor.runQueued();

        // UNKNOWN 让路下轮：不重建、零落库（拨针更无从谈起）
        verify(provisioner, never()).provisionForWake(any(), any());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_container_alive_when_scan_then_noop() {
        stubLoads(oldTouchedReady());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.SCAN, false);
        executor.runQueued();

        verify(provisioner, never()).provisionForWake(any(), any());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_sealed_when_scan_then_deep_wake_without_pin() {
        // 封存态漂移收敛同走深度唤醒（探查有把握的不在才动）；载入序列同 SCAN 重建行
        stubLoads(sealedWorkspace(), sealedWorkspace(), sealedWorkspace());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(sealPackageStore.open("/seal/ws-42.tar.gz")).thenReturn("archive".getBytes());
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenThrow(new ApplicationException(WorkspaceMessage.PREVIEW_NOT_SERVING));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).convergeAsync(ID, ConvergenceFace.SCAN, false);
        executor.runQueued();

        verify(environmentBackend).restoreVolume(any(WorkspaceHandle.class), any());
        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        // 不拉应用（未生成申报）且只 rewake 落库一次（不拨针）
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
        verify(workspaceRepository, times(1)).save(any(Workspace.class));
    }

    @Test
    void given_deleted_or_provisioning_when_async_task_reload_then_deferred() {
        // 任务内重取（提交到执行的等待间隙状态已变）：已删除 → 静默让路
        when(workspaceRepository.findById(42L)).thenReturn(Optional.empty());
        QueuedExecutor executor = new QueuedExecutor();
        WorkspaceConvergenceAppService service = newService(executor);

        service.convergeAsync(ID, ConvergenceFace.SCAN, false);
        executor.runQueued();

        verify(environmentBackend, never()).containerState(any(WorkspaceHandle.class));
        verify(provisioner, never()).provisionForWake(any(), any());

        // 已在途（入口见 READY 提交后，他人已 rewake 落 PROVISIONING）→ 任务内重取让路
        when(workspaceRepository.findById(42L))
                .thenReturn(Optional.of(readyWorkspace()),
                        Optional.of(Workspace.registerPending(ID, EnvKind.DEV)));
        service.convergeAsync(ID, ConvergenceFace.TOUCH, true);
        executor.runQueued();

        verify(environmentBackend, never()).containerState(any(WorkspaceHandle.class));
        verify(provisioner, never()).provisionForWake(any(), any());
    }

    // ---------- DOWNLOAD 面（同步；不拨针不拉应用，UNKNOWN 跳过） ----------

    @Test
    void given_dead_container_when_download_then_rebuilt_without_pin_or_app() {
        // 载入序列：入口 → rewake 事务重取 → 收敛后重取（READY）→ 等就绪前重取
        stubLoads(oldTouchedReady(), oldTouchedReady(), oldTouchedReady(), oldTouchedReady());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.DOWNLOAD, false);

        // 同步唤醒重建（打包只要容器）：不拉应用、只 rewake 落库一次、不拨针
        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getLastTouchAt()).isEqualTo(OLD_TOUCH);
    }

    @Test
    void given_probe_unknown_when_download_then_skipped() {
        // #176 下载面口径：UNKNOWN 不重建（预清 rm -f 会杀可能健康的容器）——跳过，
        // 容器若在打包自成，真死由打包如实失败、重试即恢复
        stubLoads(oldTouchedReady());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.UNKNOWN);

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.DOWNLOAD, false);

        verify(provisioner, never()).provisionForWake(any(), any());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_container_alive_when_download_then_noop() {
        stubLoads(oldTouchedReady());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.DOWNLOAD, false);

        // 实态健康：不重建不拉应用零落库，调用方径直打包
        verify(provisioner, never()).provisionForWake(any(), any());
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_provisioning_when_download_then_awaits_ready_without_pin() {
        Workspace pending = Workspace.registerPending(ID, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.DOWNLOAD, false);

        // 忙（置备/唤醒在途）：不探查不排队，等就绪接管；DOWNLOAD 不拨针
        verify(readinessWaiter).awaitReady(pending);
        verify(environmentBackend, never()).containerState(any(WorkspaceHandle.class));
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_sealed_when_download_then_untouched_defensive() {
        // 防御行（调用方 contentPackageOf 对封存态直取封存包拦截在前，不至此）：
        // DOWNLOAD 面不动封存态——不解包、不重建、零落库
        stubLoads(sealedWorkspace());

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.DOWNLOAD, false);

        verify(environmentBackend, never()).containerState(any(WorkspaceHandle.class));
        verify(environmentBackend, never()).restoreVolume(any(), any());
        verify(provisioner, never()).provisionForWake(any(), any());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    // ---------- ADMIN 面（同步；除「本就健康」外收敛落定即拨针，UNKNOWN 抛 WSP_002） ----------

    @Test
    void given_healthy_running_when_admin_then_no_action_no_pin() {
        stubLoads(oldTouchedReady());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.ADMIN, false);

        // 探查发现本就健康（意图/实态一致）：不动作不拨针
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
        verify(provisioner, never()).provisionForWake(any(), any());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_healthy_running_when_admin_generated_then_app_started_no_pin() {
        stubLoads(oldTouchedReady());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.ADMIN, true);

        verify(environmentBackend).startApp(any(WorkspaceHandle.class));   // 幂等拉起（已在服直回）
        verify(workspaceRepository, never()).save(any(Workspace.class));   // 本就健康不拨针
    }

    @Test
    void given_hibernated_intent_with_alive_container_when_admin_then_aligned_and_pinned() {
        // 期望休眠而实态在跑（休眠删容器失败残留/外部重建的漂移形）：唤醒对齐意图，
        // 否则扫描器按休眠意图再删容器——唤醒被静默撤销。对齐是收敛动作：拨针
        Workspace hibernated = oldTouchedReady();
        hibernated.hibernate();
        stubLoads(hibernated);
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.ADMIN, false);

        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getDesiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(saved.getValue().getLastTouchAt()).isAfter(OLD_TOUCH);   // 闲置窗口重新计
    }

    @Test
    void given_dead_container_when_admin_then_rebuilt_and_pinned() {
        // 载入序列：入口 → rewake 事务重取 → 收敛后重取（READY）→ 等就绪前重取
        stubLoads(oldTouchedReady(), oldTouchedReady(), oldTouchedReady(), oldTouchedReady());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.ADMIN, true);

        // 重建收敛＋应用拉起；rewake 落库一次（不拨）＋收敛落定拨针一次（醒完秒睡缺口）
        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        verify(environmentBackend).startApp(any(WorkspaceHandle.class));
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getLastTouchAt()).isEqualTo(OLD_TOUCH);
        assertThat(saved.getAllValues().get(1).getLastTouchAt()).isAfter(OLD_TOUCH);
    }

    @Test
    void given_provisioning_when_admin_then_awaits_ready_and_pinned() {
        Workspace pending = Workspace.registerPending(ID, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        // 等就绪落定（他人收敛的成果：READY 工作区）
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> oldTouchedReady());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.ADMIN, false);

        // 忙则让路→等就绪（不探查）；落定即收敛成果——ADMIN 拨针（醒完不秒睡）
        verify(readinessWaiter).awaitReady(pending);
        verify(environmentBackend, never()).containerState(any(WorkspaceHandle.class));
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getLastTouchAt()).isAfter(OLD_TOUCH);
    }

    @Test
    void given_sealed_during_await_when_admin_then_wsp_016_without_pin() {
        // 等就绪落定却见封存意图（等待间隙被并发封存——防御行：封存入口均拒置备
        // 在途，正常不至）：未收敛成运行态，如实 WSP_016 不拨针（与深度唤醒未成同口径）
        Workspace pending = Workspace.registerPending(ID, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> sealedWorkspace());

        assertThatThrownBy(() -> newService(new QueuedExecutor())
                .convergeBlocking(ID, ConvergenceFace.ADMIN, false))
                .isInstanceOf(ApplicationException.class)
                .hasMessage(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE.message());

        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_probe_unknown_when_admin_then_wsp_002_without_rebuild() {
        // #176：探查失败≠容器不在——同步面不盲重建，如实回 WSP_002 可重试
        stubLoads(oldTouchedReady());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.UNKNOWN);

        assertThatThrownBy(() -> newService(new QueuedExecutor())
                .convergeBlocking(ID, ConvergenceFace.ADMIN, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessage(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());

        verify(provisioner, never()).provisionForWake(any(), any());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_sealed_with_package_when_admin_then_deep_wake_and_pinned() {
        // 载入序列：入口（封存）→ rewake 事务重取 → 收敛后重取（READY 运行）→ 等就绪前重取
        stubLoads(sealedWorkspace(), sealedWorkspace(), readyWorkspace(), oldTouchedReady());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(sealPackageStore.open("/seal/ws-42.tar.gz")).thenReturn("archive".getBytes());
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));

        newService(new QueuedExecutor()).convergeBlocking(ID, ConvergenceFace.ADMIN, true);

        // 封存态不经探查直走深度唤醒（卷已删容器必不在）：解包回卷 → 重建 → 拉应用
        var inOrder = inOrder(environmentBackend, provisioner);
        inOrder.verify(environmentBackend).restoreVolume(any(WorkspaceHandle.class),
                eq("archive".getBytes()));
        inOrder.verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        verify(environmentBackend, never()).containerState(any(WorkspaceHandle.class));
        verify(environmentBackend).startApp(any(WorkspaceHandle.class));
        // rewake 落库（封存工作区 last-touch 逾 31 天）＋收敛落定拨针（深度唤醒分钟级
        // 成本后闲置钟不走旧值——下轮扫描不会立刻又睡掉）
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getLastTouchAt())
                .isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void given_sealed_with_unreadable_package_when_admin_then_wsp_016_without_pin() {
        // 深度唤醒未成（包不可读，内核保持封存态待人工）：如实 WSP_016 而非伪成功，不拨针
        stubLoads(sealedWorkspace(), sealedWorkspace());
        when(sealPackageStore.open("/seal/ws-42.tar.gz"))
                .thenThrow(new UncheckedIOException(
                        new FileNotFoundException("/seal/ws-42.tar.gz")));
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> newService(new QueuedExecutor())
                .convergeBlocking(ID, ConvergenceFace.ADMIN, true))
                .isInstanceOf(ApplicationException.class)
                .hasMessage(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE.message());

        verify(provisioner, never()).provisionForWake(any(), any());
        verify(workspaceRepository, never()).save(any(Workspace.class));
    }

    @Test
    void given_mutex_held_when_admin_then_yields_and_awaits_without_double_rebuild()
            throws Exception {
        // 「忙则让路→等就绪」同步面口径：他人收敛任务持互斥在途——本调用不排队不重复
        // 重建，等就绪接管（落定后 ADMIN 照拨针：醒完不秒睡）
        stubLoads(readyWorkspace(), readyWorkspace(), readyWorkspace(), readyWorkspace(),
                readyWorkspace(), readyWorkspace());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.ABSENT);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        when(readinessWaiter.awaitReady(any())).thenAnswer(inv -> inv.getArgument(0));
        CountDownLatch rebuildEntered = new CountDownLatch(1);
        CountDownLatch releaseRebuild = new CountDownLatch(1);
        doAnswer(inv -> {
            rebuildEntered.countDown();
            assertThat(releaseRebuild.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(provisioner).provisionForWake(any(WorkspaceId.class), any(EnvKind.class));

        WorkspaceConvergenceAppService service = newService(new QueuedExecutor());
        Thread first = new Thread(() ->
                service.convergeBlocking(ID, ConvergenceFace.ADMIN, false));
        first.start();
        assertThat(rebuildEntered.await(5, TimeUnit.SECONDS)).isTrue();   // 首调用持互斥重建中

        service.convergeBlocking(ID, ConvergenceFace.ADMIN, false);   // 让路 → 等就绪
        releaseRebuild.countDown();
        first.join(5000);

        // 全程只重建一次（让路不重复进内核）
        verify(provisioner, times(1)).provisionForWake(any(WorkspaceId.class), any(EnvKind.class));
    }

    // ---------- 应用拉起轻路径（预览面自愈随迁入口） ----------

    @Test
    void given_ready_workspace_when_request_app_start_then_app_started_and_event() {
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(readyWorkspace()));
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).requestAppStart(ID);
        executor.runQueued();

        // 预览面轻路径：容器在而应用死——拉起 + 探活 + PreviewReady（前端刷新锚）
        verify(environmentBackend).startApp(any(WorkspaceHandle.class));
        verify(eventPublisher).publishApplicationEvent(any(PreviewReady.class));
    }

    @Test
    void given_provisioning_workspace_when_request_app_start_then_noop() {
        when(workspaceRepository.findById(42L))
                .thenReturn(Optional.of(Workspace.registerPending(ID, EnvKind.DEV)));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).requestAppStart(ID);

        assertThat(executor.queued).isEmpty();
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
    }

    // ---------- 面×式固定配对（不允许自由组合未测配对） ----------

    @Test
    void given_mismatched_face_mode_when_converge_then_rejected_as_programming_error() {
        WorkspaceConvergenceAppService service = newService(new QueuedExecutor());

        // 异步面只收 TOUCH/SCAN；同步面只收 DOWNLOAD/ADMIN——错配是编排错误
        assertThatThrownBy(() -> service.convergeAsync(ID, ConvergenceFace.ADMIN, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.convergeAsync(ID, ConvergenceFace.DOWNLOAD, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.convergeBlocking(ID, ConvergenceFace.TOUCH, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.convergeBlocking(ID, ConvergenceFace.SCAN, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 测试数据 ----------

    private final AtomicInteger loads = new AtomicInteger();

    /** 按调用次序回放载入结果（超出回放末值）——载入次序即编排步序，显式可读。 */
    private void stubLoads(Workspace... sequence) {
        loads.set(0);
        when(workspaceRepository.findById(42L)).thenAnswer(inv -> Optional.of(
                sequence[Math.min(loads.incrementAndGet(), sequence.length) - 1]));
    }

    private Workspace readyWorkspace() {
        Workspace pending = Workspace.registerPending(ID, EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(ID, "ws-42", "previewnet")));
        return pending;
    }

    /** READY 且 last-touch 在旧锚（拨针断言用：拨了晚于锚，没拨仍等于锚）。 */
    private Workspace oldTouchedReady() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(OLD_TOUCH);
        return workspace;
    }

    /** 已封存工作区：READY 置备态 + 期望封存 + 包元数据（深度唤醒的输入形态）。 */
    private Workspace sealedWorkspace() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(LocalDateTime.now().minusDays(31));
        workspace.hibernate();
        return workspace.seal(new SealPackage("/seal/ws-42.tar.gz", 128L), LocalDateTime.now());
    }

    /** 无包封存工作区：期望封存、元数据为空（卷已失的外部漂移收敛形态）。 */
    private Workspace bareSealedWorkspace() {
        Workspace workspace = readyWorkspace();
        workspace.markTouched(LocalDateTime.now().minusDays(31));
        workspace.hibernate();
        return workspace.seal(null, LocalDateTime.now());
    }

    private WorkspaceConvergenceAppService newService(Executor executor) {
        return new WorkspaceConvergenceAppService(environmentBackend, workspaceRepository,
                transactionTemplate, eventPublisher, provisioner, readinessWaiter,
                sealPackageStore, executor);
    }
}
