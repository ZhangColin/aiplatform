package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceLostMark;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.SealPackage;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 在册失联分级处置（#488，ADR-0045）：纯 Mockito 直测——「在册但项目行已删」的
 * 行（销毁中途吞错的静止现场）分级：物理资源仍在 → 只标记失联（机器不删可能是
 * 用户数据的卷）；物理已空 → 删行做注册表卫生（互斥面内重取＋重探守卫）。真库
 * ＋真 daemon 的两形态活体验收归 {@code WorkspaceDetachmentLiveTest}；扫描轮接线
 * 归 {@code WorkspaceHibernationAppServiceTest}。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceDetachmentAppServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 12, 0);

    /** 失联判定的宽限线（新建行窗口，10 分钟口径——与 #486 收编宽限同值）。 */
    private static final LocalDateTime GRACE_LINE = NOW.minusMinutes(10);

    @Mock
    private EnvironmentBackend environmentBackend;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceLostMarkRepository lostMarkRepository;

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

    // ---------- 形态①：行在、项目删、物理资源在 → 标记失联不删除 ----------

    @Test
    void given_unclaimed_row_with_running_container_when_reconcile_then_marked_not_deleted() {
        Workspace workspace = agedWorkspace(42);
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenReturn(ContainerState.RUNNING);
        when(environmentBackend.volumeExists(any(WorkspaceHandle.class))).thenReturn(true);

        service().reconcileDetached(List.of(workspace), Set.of(), NOW);

        // 标记落库（判定时刻随行）；行不删、物理资源绝不动（ADR-0045：机器不裁决用户数据）
        ArgumentCaptor<WorkspaceLostMark> mark = ArgumentCaptor.forClass(WorkspaceLostMark.class);
        verify(lostMarkRepository).save(mark.capture());
        assertThat(mark.getValue().getWorkspaceId()).isEqualTo(42L);
        assertThat(mark.getValue().getMarkedAt()).isEqualTo(NOW);
        verify(workspaceRepository, never()).delete(any(Workspace.class));
        verify(environmentBackend, never()).destroyWorkspace(any());
        verify(environmentBackend, never()).deleteVolume(any());
    }

    // ---------- 形态②：行在、项目删、物理全空 → 删行做注册表卫生 ----------

    @Test
    void given_unclaimed_hollow_row_when_reconcile_then_row_purged_no_mark() {
        Workspace workspace = agedWorkspace(42);
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenReturn(ContainerState.ABSENT);
        when(environmentBackend.volumeExists(any(WorkspaceHandle.class))).thenReturn(false);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        runExclusivelyAwaitingSynchronously();

        service().reconcileDetached(List.of(workspace), Set.of(), NOW);

        // 空壳行卫生删除（互斥面内重取在）；失联标记不落（清单不被空壳噪音淹没）
        verify(workspaceRepository).delete(workspace);
        verify(lostMarkRepository, never()).save(any(WorkspaceLostMark.class));
    }

    @Test
    void given_unclaimed_hollow_row_with_seal_package_when_reconcile_then_package_cleaned() {
        Workspace workspace = agedWorkspace(42);
        workspace.hibernate();
        workspace.seal(new SealPackage("/sealed/ws-42.tar.gz", 4096L),
                NOW.minusDays(1));   // 销毁吞错现场：级联停在删包前，包残留而行在
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenReturn(ContainerState.ABSENT);
        when(environmentBackend.volumeExists(any(WorkspaceHandle.class))).thenReturn(false);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        runExclusivelyAwaitingSynchronously();

        service().reconcileDetached(List.of(workspace), Set.of(), NOW);

        // 封存包随手清（行删后包即无锚点——不留平台存储孤儿）；行照删
        verify(sealPackageStore).delete("/sealed/ws-42.tar.gz");
        verify(workspaceRepository).delete(workspace);
    }

    // ---------- 双闸与守卫 ----------

    @Test
    void given_claimed_row_when_reconcile_then_untouched() {
        Workspace workspace = agedWorkspace(42);

        service().reconcileDetached(List.of(workspace), Set.of(42L), NOW);

        // 认领在（项目行在）＝ 非失联：不探查、不标记、不删行
        verifyNoInteractions(environmentBackend, lostMarkRepository, workspaceRepository);
    }

    @Test
    void given_row_within_grace_window_when_reconcile_then_untouched() {
        Workspace workspace = agedWorkspace(42);
        workspace.markTouched(NOW.minusMinutes(2));   // 新建窗口内（项目行可能尚未落库）

        service().reconcileDetached(List.of(workspace), Set.of(), NOW);

        // 宽限窗内不动（闸一）：防「工作区已落库、项目行未落」创建窗口误标
        verifyNoInteractions(environmentBackend, lostMarkRepository, workspaceRepository);
    }

    @Test
    void given_provisioning_row_when_reconcile_then_untouched() {
        // 在途置备行（物理未建、项目行可能未落——误判「物理全空」删行会被置备线程
        // complete() 复活）：与休眠面同口径「在途不扰」，宽限窗外也不动
        Workspace provisioning = Workspace.registerPending(WorkspaceId.of("42"), EnvKind.DEV);
        provisioning.markTouched(NOW.minusHours(1));

        service().reconcileDetached(List.of(provisioning), Set.of(), NOW);

        verifyNoInteractions(environmentBackend, lostMarkRepository, workspaceRepository);
    }

    @Test
    void given_probe_unknown_when_reconcile_then_marked_not_deleted() {
        Workspace workspace = agedWorkspace(42);
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenReturn(ContainerState.UNKNOWN);
        when(environmentBackend.volumeExists(any(WorkspaceHandle.class))).thenReturn(false);

        service().reconcileDetached(List.of(workspace), Set.of(), NOW);

        // 探查失败≠容器不在（#176 口径，闸二）：失联是事实（项目行没了）照标，删行让路
        verify(lostMarkRepository).save(any(WorkspaceLostMark.class));
        verify(workspaceRepository, never()).delete(any(Workspace.class));
    }

    @Test
    void given_already_marked_row_when_reconcile_then_no_duplicate_mark() {
        Workspace workspace = agedWorkspace(42);
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenReturn(ContainerState.RUNNING);
        when(environmentBackend.volumeExists(any(WorkspaceHandle.class))).thenReturn(true);
        when(lostMarkRepository.findById(42L))
                .thenReturn(Optional.of(WorkspaceLostMark.mark(workspace.workspaceId(),
                        NOW.minusDays(1))));

        service().reconcileDetached(List.of(workspace), Set.of(), NOW);

        // 幂等：已标跳过（不翻审计列、不覆盖首标时刻——「失联多久」锚稳定）
        verify(lostMarkRepository, never()).save(any(WorkspaceLostMark.class));
    }

    @Test
    void given_row_deleted_before_purge_when_reconcile_then_silently_noop() {
        Workspace workspace = agedWorkspace(42);
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenReturn(ContainerState.ABSENT);
        when(environmentBackend.volumeExists(any(WorkspaceHandle.class))).thenReturn(false);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.empty());   // 判定后行已被删（销毁竞态）
        runExclusivelyAwaitingSynchronously();

        service().reconcileDetached(List.of(workspace), Set.of(), NOW);

        // 行已删：幂等落空不复活、不炸（销毁/并发卫生删行竞态）
        verify(workspaceRepository, never()).delete(any(Workspace.class));
        verify(lostMarkRepository, never()).save(any(WorkspaceLostMark.class));
    }

    @Test
    void given_resource_rebuilt_between_verdict_and_purge_when_reconcile_then_marked_not_deleted() {
        Workspace workspace = agedWorkspace(42);
        // 判定探查（互斥面外）：全空 → 进删行收口
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenReturn(ContainerState.ABSENT, ContainerState.RUNNING);   // 收口内重探：又被唤醒重建了
        when(environmentBackend.volumeExists(any(WorkspaceHandle.class))).thenReturn(false);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        runExclusivelyAwaitingSynchronously();

        service().reconcileDetached(List.of(workspace), Set.of(), NOW);

        // 判定到删行之间物理复活（后台手动唤醒失联行竞态）：不删行——改走标记待裁决
        verify(workspaceRepository, never()).delete(any(Workspace.class));
        verify(lostMarkRepository).save(any(WorkspaceLostMark.class));
    }

    @Test
    void given_probe_failure_on_one_row_when_reconcile_then_round_survives() {
        Workspace failing = agedWorkspace(42);
        Workspace healthy = agedWorkspace(43);
        when(environmentBackend.containerState(any(WorkspaceHandle.class)))
                .thenThrow(new RuntimeException("daemon down"))
                .thenReturn(ContainerState.RUNNING);
        when(environmentBackend.volumeExists(any(WorkspaceHandle.class))).thenReturn(true);

        service().reconcileDetached(List.of(failing, healthy), Set.of(), NOW);

        // 单条失败不炸整轮（尽力而为，下轮再试）：后行照常分级标记
        verify(lostMarkRepository).save(argThat(mark -> mark.getWorkspaceId() == 43L));
    }

    // ---------- 夹具 ----------

    /** READY DEV 行且已出宽限窗（最近触碰拨到 1 小时前——新建窗口保护不适用）。 */
    private Workspace agedWorkspace(long id) {
        Workspace pending = Workspace.registerPending(WorkspaceId.of(Long.toString(id)), EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(WorkspaceId.of(Long.toString(id)),
                        "ws-" + id, "previewnet")));
        pending.markTouched(NOW.minusHours(1));
        return pending;
    }

    private WorkspaceDetachmentAppService service() {
        return new WorkspaceDetachmentAppService(environmentBackend, workspaceRepository,
                lostMarkRepository, sealPackageStore, transactionTemplate, convergence);
    }

    /** 独占等待提交直通（测试不异步）：空壳卫生任务同步跑，副作用次序可断言。 */
    private void runExclusivelyAwaitingSynchronously() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(convergence).runExclusivelyAwaiting(any(WorkspaceId.class), any());
    }
}
