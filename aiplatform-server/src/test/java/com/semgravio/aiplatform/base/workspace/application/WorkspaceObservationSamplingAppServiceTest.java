package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.HostObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.DesiredState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.semgravio.aiplatform.base.workspace.domain.model.EnvironmentUsage;
import com.semgravio.aiplatform.base.workspace.domain.model.SealPackage;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.repository.HostObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 占用采样编排（#487 写面；#489 追加主机帧）：扫描轮末尾旁路采样——每轮全量
 * （demo 阶段单机数量级串行 du 可在轮窗内完成）、后台单线程、在途让路不排队
 * （du 慢轮不堆积）；封存行不采卷（卷已删，du 无意义——起旁路容器白跑）；首采
 * 落行/复采同行覆盖（每工作区一行；主机帧全机一行）；单行失败不炸整轮（尽力而
 * 为，下轮再采）；注册表不可达整轮中止（宁漏勿误——同收编双闸口径）。docker
 * 命令形状归 {@code DockerEnvironmentBackendObservationTest}／
 * {@code DockerEnvironmentBackendEnvironmentUsageTest}，真 daemon 采样值与
 * du/df 实测一致归 {@code WorkspaceObservationSamplingLiveTest}／
 * {@code BackofficeResourceFaceLiveTest}。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceObservationSamplingAppServiceTest {

    @Mock
    private EnvironmentBackend environmentBackend;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private WorkspaceObservationSampleRepository sampleRepository;

    @Mock
    private HostObservationSampleRepository hostRepository;

    /** 受控执行器：任务入队不跑（验收 fire-and-forget 与在途让路），手动放行。 */
    private final Queue<Runnable> queued = new ArrayDeque<>();

    private WorkspaceObservationSamplingAppService newService() {
        return new WorkspaceObservationSamplingAppService(
                environmentBackend, workspaceRepository, sampleRepository,
                hostRepository, queued::add);
    }

    // ---------- 采样轮：全量探测＋落库 ----------

    @Test
    void given_workspaces_when_round_then_all_sampled_and_upserted_with_freshness() {
        Workspace alive = readyWorkspace(41);
        Workspace sleeping = readyWorkspace(42);
        sleeping.hibernate();
        when(workspaceRepository.findAll()).thenReturn(List.of(alive, sleeping));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());
        when(environmentBackend.containerState(any())).thenReturn(ContainerState.RUNNING);
        when(environmentBackend.volumeSizeBytes(any())).thenReturn(100L);

        newService().sampleRound();

        // 实态逐台探、卷大小逐台 du（休眠行卷仍在，照采——运营要看的就是占用）
        verify(environmentBackend).containerState(alive.toHandle());
        verify(environmentBackend).containerState(sleeping.toHandle());
        verify(environmentBackend).volumeSizeBytes(alive.toHandle());
        verify(environmentBackend).volumeSizeBytes(sleeping.toHandle());
        ArgumentCaptor<WorkspaceObservationSample> saved =
                ArgumentCaptor.forClass(WorkspaceObservationSample.class);
        verify(sampleRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(WorkspaceObservationSample::getWorkspaceId)
                .containsExactlyInAnyOrder(41L, 42L);
        assertThat(saved.getAllValues()).extracting(WorkspaceObservationSample::getContainerState)
                .containsOnly(ContainerState.RUNNING);
        assertThat(saved.getAllValues()).extracting(WorkspaceObservationSample::getVolumeSizeBytes)
                .containsOnly(100L);
        // 新鲜度标注随行（采样时刻非空）
        assertThat(saved.getAllValues())
                .allSatisfy(sample -> assertThat(sample.getSampledAt()).isNotNull());
    }

    @Test
    void given_sealed_workspace_when_round_then_state_sampled_volume_skipped() {
        Workspace sealed = sealedWorkspace(43);
        when(workspaceRepository.findAll()).thenReturn(List.of(sealed));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());
        when(environmentBackend.containerState(any())).thenReturn(ContainerState.ABSENT);

        newService().sampleRound();

        // 封存 = 删卷换包（注册表意图）：不 du（卷不在，起旁路容器白跑），卷列容缺
        verify(environmentBackend, never()).volumeSizeBytes(any());
        ArgumentCaptor<WorkspaceObservationSample> saved =
                ArgumentCaptor.forClass(WorkspaceObservationSample.class);
        verify(sampleRepository).save(saved.capture());
        assertThat(saved.getValue().getContainerState()).isEqualTo(ContainerState.ABSENT);
        assertThat(saved.getValue().getVolumeSizeBytes()).isNull();
    }

    @Test
    void given_existing_sample_row_when_round_then_same_row_resampled() {
        Workspace alive = readyWorkspace(41);
        when(workspaceRepository.findAll()).thenReturn(List.of(alive));
        WorkspaceObservationSample existing = WorkspaceObservationSample.record(
                alive.workspaceId(), ContainerState.RUNNING, 1L,
                LocalDateTime.of(2026, 11, 1, 0, 0));
        when(sampleRepository.findAllById(any())).thenReturn(List.of(existing));
        when(environmentBackend.containerState(any())).thenReturn(ContainerState.ABSENT);
        when(environmentBackend.volumeSizeBytes(any())).thenReturn(200L);

        newService().sampleRound();

        // 每工作区一行：复采在原行覆盖（同主键），不另落新行
        verify(sampleRepository).save(existing);
        assertThat(existing.getContainerState()).isEqualTo(ContainerState.ABSENT);
        assertThat(existing.getVolumeSizeBytes()).isEqualTo(200L);
        assertThat(existing.getSampledAt()).isAfter(LocalDateTime.of(2026, 11, 1, 0, 0));
    }

    @Test
    void given_one_row_probe_fails_when_round_then_others_still_sampled() {
        Workspace bad = readyWorkspace(41);
        Workspace good = readyWorkspace(42);
        when(workspaceRepository.findAll()).thenReturn(List.of(bad, good));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());
        // 契约上探查不抛（UNKNOWN/null），此处防真实后端违约：单行异常不炸整轮
        when(environmentBackend.containerState(any()))
                .thenThrow(new RuntimeException("daemon 抖动"))
                .thenReturn(ContainerState.RUNNING);

        assertThatCode(() -> newService().sampleRound()).doesNotThrowAnyException();

        verify(sampleRepository).save(any(WorkspaceObservationSample.class));
    }

    @Test
    void given_registry_unreachable_when_round_then_abort_without_writes() {
        when(workspaceRepository.findAll()).thenThrow(new RuntimeException("库不可达"));

        assertThatCode(() -> newService().sampleRound()).doesNotThrowAnyException();

        // 宁漏勿误（同收编双闸）：注册表不可达＝无从构成采样集，整轮中止不落一行
        // （主机帧同门：无主计数以注册表求差，准绳无从构成绝不盲写）
        verifyNoInteractions(sampleRepository);
        verifyNoInteractions(hostRepository);
        verifyNoInteractions(environmentBackend);
    }

    // ---------- 主机帧（#489 资源面大盘采样：轮末全机一行覆盖） ----------

    @Test
    void given_round_when_host_phase_then_singleton_row_upserted_with_orphan_diff() {
        Workspace alive = readyWorkspace(41);
        when(workspaceRepository.findAll()).thenReturn(List.of(alive));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());
        when(environmentBackend.containerState(any())).thenReturn(ContainerState.RUNNING);
        when(environmentBackend.volumeSizeBytes(any())).thenReturn(100L);
        when(environmentBackend.environmentUsage()).thenReturn(new EnvironmentUsage(
                new EnvironmentUsage.Usage(14, 7_750_000_000L),
                new EnvironmentUsage.Usage(6, 151_600L),
                new EnvironmentUsage.Usage(73, 845_900_000L),
                new EnvironmentUsage.Usage(2, 5_000_000_000L),
                Set.of(41L, 55L),      // 在跑 ws-41（在册）＋ws-55（无主）
                Set.of(41L, 77L, 88L)));   // vol-ws-41（在册）＋77/88（无主）
        when(hostRepository.findById(HostObservationSample.SINGLETON_ID))
                .thenReturn(Optional.empty());

        newService().sampleRound();

        // 首采落全机唯一行；docker 四类用量原数、无主计数＝docker 清单对注册表求差
        ArgumentCaptor<HostObservationSample> hostCaptor =
                ArgumentCaptor.forClass(HostObservationSample.class);
        verify(hostRepository).save(hostCaptor.capture());
        HostObservationSample host = hostCaptor.getValue();
        assertThat(host.getId()).isEqualTo(HostObservationSample.SINGLETON_ID);
        assertThat(host.getImageCount()).isEqualTo(14);
        assertThat(host.getImageSizeBytes()).isEqualTo(7_750_000_000L);
        assertThat(host.getContainerCount()).isEqualTo(6);
        assertThat(host.getVolumeCount()).isEqualTo(73);
        assertThat(host.getBuildCacheSizeBytes()).isEqualTo(5_000_000_000L);
        assertThat(host.getOrphanContainerCount()).isEqualTo(1);
        assertThat(host.getOrphanVolumeCount()).isEqualTo(2);
        // 主机盘列随帧（statfs 锚路径与字节——与 df 对账的锚）
        assertThat(host.getHostPath()).isNotBlank();
        assertThat(host.getHostTotalBytes()).isPositive();
        assertThat(host.getHostUsableBytes()).isPositive();
        assertThat(host.getSampledAt()).isNotNull();
    }

    @Test
    void given_docker_unreachable_when_host_phase_then_row_written_with_docker_columns_null() {
        Workspace alive = readyWorkspace(41);
        when(workspaceRepository.findAll()).thenReturn(List.of(alive));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());
        when(environmentBackend.containerState(any())).thenReturn(ContainerState.RUNNING);
        when(environmentBackend.volumeSizeBytes(any())).thenReturn(100L);
        when(environmentBackend.environmentUsage())
                .thenThrow(new RuntimeException("docker daemon 不在"));
        HostObservationSample existing = HostObservationSample.first().resample(
                "/old", 1L, 1L,
                new EnvironmentUsage(new EnvironmentUsage.Usage(9, 9),
                        new EnvironmentUsage.Usage(9, 9), new EnvironmentUsage.Usage(9, 9),
                        new EnvironmentUsage.Usage(9, 9), Set.of(), Set.of()),
                9L, 9L, LocalDateTime.of(2026, 11, 1, 0, 0));
        when(hostRepository.findById(HostObservationSample.SINGLETON_ID))
                .thenReturn(Optional.of(existing));

        newService().sampleRound();

        // 复采同行覆盖；docker 面缺测轮如实写 NULL（「零」与「不可达」在观测面上
        // 必须可分——旧值不顶包）；主机盘列照采（statfs 无 docker 依赖）
        verify(hostRepository).save(existing);
        assertThat(existing.getImageCount()).isNull();
        assertThat(existing.getVolumeSizeBytes()).isNull();
        assertThat(existing.getOrphanContainerCount()).isNull();
        assertThat(existing.getHostTotalBytes()).isPositive();
        assertThat(existing.getSampledAt())
                .isAfter(LocalDateTime.of(2026, 11, 1, 0, 0));
    }

    @Test
    void given_host_frame_persist_failure_when_round_then_workspace_rows_still_sampled() {
        Workspace alive = readyWorkspace(41);
        when(workspaceRepository.findAll()).thenReturn(List.of(alive));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());
        when(environmentBackend.containerState(any())).thenReturn(ContainerState.RUNNING);
        when(environmentBackend.volumeSizeBytes(any())).thenReturn(100L);
        when(hostRepository.findById(HostObservationSample.SINGLETON_ID))
                .thenThrow(new RuntimeException("库抖动"));

        // 主机帧尽力而为：落库失败不炸整轮（工作区采样行已落，主机帧下轮再采）
        assertThatCode(() -> newService().sampleRound()).doesNotThrowAnyException();
        verify(sampleRepository).save(any(WorkspaceObservationSample.class));
    }

    // ---------- 扫描轮挂钩：fire-and-forget＋在途让路 ----------

    @Test
    void given_scan_hook_when_requestRound_then_returns_without_blocking() {
        WorkspaceObservationSamplingAppService service = newService();
        when(workspaceRepository.findAll()).thenReturn(List.of());

        service.requestRound();

        // 提交即返回（扫描轮主流程零 docker 调用）：任务还在队列里，未执行
        assertThat(queued).hasSize(1);
        verifyNoInteractions(environmentBackend);
        queued.remove().run();   // 放行后台线程侧
        verify(workspaceRepository).findAll();
    }

    @Test
    void given_round_in_flight_when_request_again_then_yield_not_queue() {
        WorkspaceObservationSamplingAppService service = newService();

        service.requestRound();
        service.requestRound();   // 首轮未落定（队列未放行）：让路不排队

        assertThat(queued).hasSize(1);
        // 首轮落定后下一请求可再入
        queued.remove().run();
        service.requestRound();
        assertThat(queued).hasSize(1);
    }

    @Test
    void given_rejected_execution_when_requestRound_then_guard_released() {
        // 关停期执行器拒收：互斥位必须释放（否则一次拒绝永久哑掉采样）——首次拒收、
        // 此后放行回队列的执行器：第二次请求可再入队即证明互斥位已归还
        WorkspaceObservationSamplingAppService service =
                new WorkspaceObservationSamplingAppService(environmentBackend,
                        workspaceRepository, sampleRepository, hostRepository,
                        new Executor() {
                            private boolean rejectedOnce;

                            @Override
                            public void execute(Runnable command) {
                                if (!rejectedOnce) {
                                    rejectedOnce = true;
                                    throw new RejectedExecutionException("已关停");
                                }
                                queued.add(command);
                            }
                        });

        assertThatCode(service::requestRound).doesNotThrowAnyException();
        assertThat(queued).isEmpty();

        service.requestRound();
        assertThat(queued).hasSize(1);
    }

    // -------- 夹具 --------

    private Workspace readyWorkspace(long id) {
        Workspace pending = Workspace.registerPending(
                WorkspaceId.of(Long.toString(id)), EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(WorkspaceId.of(Long.toString(id)),
                        "ws-" + id, "previewnet")));
        return pending;
    }

    private Workspace sealedWorkspace(long id) {
        Workspace workspace = readyWorkspace(id);
        workspace.hibernate();
        return workspace.seal(new SealPackage("/sealed/ws.tar.gz", 4096L),
                LocalDateTime.of(2026, 9, 15, 10, 0));
    }
}
