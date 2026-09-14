package com.aieducenter.aiplatform.base.workspace.application;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
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
import com.aieducenter.aiplatform.base.workspace.application.mapper.WorkspaceMapper;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 唤醒自愈编排（#170）：项目域触碰 → 异步实态探查 → 幂等重建 →（已生成）应用拉起
 * → 预览事件。纯 Mockito + 受控执行器（探查任务入队不自动跑——互斥窗口可观测）；
 * 真库收敛与事件时序见 {@code WorkspaceLifecycleAppServiceTest}（@IntegrationTest）。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceWakeupOrchestrationTest {

    private static final WorkspaceId ID = WorkspaceId.of("42");

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
    private WorkspaceMapper workspaceMapper;

    @Mock
    private WorkspaceProvisionAppService provisioner;

    @Mock
    private WorkspaceReadinessWaiter readinessWaiter;

    @Mock
    private WorkspaceProperties properties;

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

    @Test
    void given_provisioning_workspace_when_touch_then_last_touch_marked_and_no_probe() {
        Workspace pending = Workspace.registerPending(ID, EnvKind.DEV);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(pending));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).touch("42", true);

        // last-touch 拨动落库
        ArgumentCaptor<Workspace> saved = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository).save(saved.capture());
        assertThat(saved.getValue().getLastTouchAt()).isAfterOrEqualTo(LocalDateTime.MIN);
        // 首次置备/唤醒已在途：不提交探查任务
        assertThat(executor.queued).isEmpty();
        verify(environmentBackend, never()).isContainerRunning(any(WorkspaceHandle.class));
    }

    @Test
    void given_container_dead_when_touch_then_rewake_rebuild_and_app_started() {
        // 载入序列：touch 探查 →（任务内）heal 探查 → rewake 事务重取 → 收敛后重取 →
        // exposePreview 重取——收敛后一律 READY
        stubLoads(readyWorkspace(), readyWorkspace(), readyWorkspace(), readyWorkspace());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(false);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).touch("42", true);
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
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(false);
        // 未生成工作区唤醒后探活必败（WSP_012 预期口径——恢复到未生成态）
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenThrow(new ApplicationException(WorkspaceMessage.PREVIEW_NOT_SERVING));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).touch("42", false);
        executor.runQueued();

        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        // 从未跑过 run：不拉应用（预览保持未生成口径，探活失败吞掉不炸任务）
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
    }

    @Test
    void given_container_alive_when_touch_then_no_rebuild() {
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(readyWorkspace()));
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(true);
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).touch("42", true);
        executor.runQueued();

        // 实态健康（意图/实态一致）：不动
        verify(provisioner, never()).provisionForWake(any(), any());
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
    }

    @Test
    void given_concurrent_touches_when_healing_in_flight_then_single_probe_submitted() {
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(readyWorkspace()));
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(true);
        QueuedExecutor executor = new QueuedExecutor();

        WorkspaceLifecycleAppService service = newService(executor);
        service.touch("42", true);
        service.touch("42", true);   // 在途互斥：第二次不重复提交

        assertThat(executor.queued).hasSize(1);
        executor.runQueued();        // 完成后互斥解除

        service.touch("42", true);
        assertThat(executor.queued).hasSize(1);   // 可再次提交（下轮探查）
    }

    @Test
    void given_probe_throws_when_touch_then_swallowed_and_mutex_released() {
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(readyWorkspace()));
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class)))
                .thenThrow(new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED));
        QueuedExecutor executor = new QueuedExecutor();

        WorkspaceLifecycleAppService service = newService(executor);
        service.touch("42", true);
        executor.runQueued();   // 探查异常吞掉（尽力而为），互斥释放

        service.touch("42", true);
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
        when(environmentBackend.isContainerRunning(any(WorkspaceHandle.class))).thenReturn(false);
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).touch("42", true);
        executor.runQueued();

        // 重试上限落 FAILED：不拉应用不发事件（可再触发——下次触碰 rewake 重来）
        verify(provisioner).provisionForWake(ID, EnvKind.DEV);
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
        verify(eventPublisher, never()).publishApplicationEvent(any(PreviewReady.class));
    }

    @Test
    void given_ready_workspace_when_request_app_start_then_app_started_and_event() {
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(readyWorkspace()));
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenReturn(URI.create("http://42.localhost/"));
        QueuedExecutor executor = new QueuedExecutor();

        newService(executor).requestAppStart("42");
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

        newService(executor).requestAppStart("42");

        assertThat(executor.queued).isEmpty();
        verify(environmentBackend, never()).startApp(any(WorkspaceHandle.class));
    }

    @Test
    void given_provisioning_workspace_when_expose_preview_then_starting_pending() {
        when(workspaceRepository.findById(42L))
                .thenReturn(Optional.of(Workspace.registerPending(ID, EnvKind.DEV)));

        // 置备/唤醒中：立即待期（WSP_013 系统启动中），不阻塞请求线程长等
        assertThatThrownBy(() -> newService(new QueuedExecutor()).exposePreview("42"))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                .isEqualTo(WorkspaceMessage.WORKSPACE_STARTING);
    }

    @Test
    void given_failed_workspace_when_expose_preview_then_provision_failed() {
        Workspace failed = Workspace.registerPending(ID, EnvKind.DEV)
                .markFailed("WSP_002：环境后端操作失败");
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(failed));

        // FAILED 语义保留（awaitReady 时代的 WSP_010 口径不变）
        assertThatThrownBy(() -> newService(new QueuedExecutor()).exposePreview("42"))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                .isEqualTo(WorkspaceMessage.WORKSPACE_PROVISION_FAILED);
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

    private WorkspaceLifecycleAppService newService(Executor executor) {
        return new WorkspaceLifecycleAppService(environmentBackend, workspaceRepository,
                transactionTemplate, eventPublisher, workspaceMapper, provisioner,
                readinessWaiter, properties, executor);
    }
}
