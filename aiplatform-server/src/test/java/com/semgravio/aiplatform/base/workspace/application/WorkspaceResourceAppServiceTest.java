package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.semgravio.aiplatform.base.workspace.application.dto.response.BackofficeResourceOverviewResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.BackofficeResourceSweepResponse;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.HostObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceSweepAction;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.EnvironmentUsage;
import com.semgravio.aiplatform.base.workspace.domain.model.OrphanSweepReport;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.repository.HostObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceSweepActionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 资源面用例（#489，/api/backoffice/resources 两端点的域面）：
 * <ul>
 * <li><b>overview 纯读库零 docker</b>（#487 治超时口径延伸到资源大盘）：各面来自
 * 主机观测采样行（扫描轮旁路采样）＋失联标记计数＋最近清扫留痕——{@code
 * EnvironmentBackend} 零交互钉死，请求路径零 docker 长操作；</li>
 * <li><b>sweep 复用 #486 收编面</b>（同一实现、同一准绳——不另写一套）：保留集＝
 * 注册表全量、宽限线＝触发时刻减 10 分钟（与扫描轮同常量同口径）；库不可达双闸
 * ——一刀未动、如实呈报（留痕行也落：动了没动都要可审计）；逐项报告全量呈现＋
 * append-only 留痕（操作者＋报告 JSON）。</li>
 * </ul>
 * docker 命令形状归 {@code DockerEnvironmentBackendOrphanSweepTest}，真 daemon
 * 对账（df/system df 一致、自造无主现场收编）归 {@code BackofficeResourceFaceLiveTest}。
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceResourceAppServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 12, 0);

    @Mock
    private EnvironmentBackend environmentBackend;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private HostObservationSampleRepository hostRepository;

    @Mock
    private WorkspaceLostMarkRepository lostMarkRepository;

    @Mock
    private WorkspaceSweepActionRepository sweepActionRepository;

    private WorkspaceResourceAppService newService() {
        return new WorkspaceResourceAppService(environmentBackend, workspaceRepository,
                hostRepository, lostMarkRepository, sweepActionRepository);
    }

    // ---------- overview：纯读库零 docker ----------

    @Test
    void given_sampled_host_row_when_overview_then_faces_mapped_and_zero_docker() {
        HostObservationSample sample = HostObservationSample.first().resample(
                "/opt/aiplatform", 100_000_000_000L, 40_000_000_000L,
                new EnvironmentUsage(
                        new EnvironmentUsage.Usage(14, 7_750_000_000L),
                        new EnvironmentUsage.Usage(6, 151_600L),
                        new EnvironmentUsage.Usage(73, 845_900_000L),
                        new EnvironmentUsage.Usage(2, 5_000_000_000L),
                        Set.of(41L), Set.of(41L, 77L, 88L)),
                1L, 2L, LocalDateTime.of(2026, 11, 13, 11, 55));
        when(hostRepository.findById(HostObservationSample.SINGLETON_ID))
                .thenReturn(Optional.of(sample));
        when(lostMarkRepository.count()).thenReturn(3L);
        when(sweepActionRepository.findTopByOrderBySweptAtDescIdDesc())
                .thenReturn(Optional.of(WorkspaceSweepAction.record(
                        new Operator("99001", "运营管理员"), true,
                        1, 2, 4, 0, "[]", LocalDateTime.of(2026, 11, 13, 10, 0))));

        BackofficeResourceOverviewResponse overview = newService().overview();

        // 主机盘面：锚路径＋总量＋可用＋派生已用（total−usable）
        assertThat(overview.host().path()).isEqualTo("/opt/aiplatform");
        assertThat(overview.host().totalBytes()).isEqualTo(100_000_000_000L);
        assertThat(overview.host().usableBytes()).isEqualTo(40_000_000_000L);
        assertThat(overview.host().usedBytes()).isEqualTo(60_000_000_000L);
        // docker 四类用量＋无主计数原数映射；新鲜度标注随行
        assertThat(overview.docker().imageCount()).isEqualTo(14);
        assertThat(overview.docker().imageSizeBytes()).isEqualTo(7_750_000_000L);
        assertThat(overview.docker().buildCacheSizeBytes()).isEqualTo(5_000_000_000L);
        assertThat(overview.orphans().containerCount()).isEqualTo(1);
        assertThat(overview.orphans().volumeCount()).isEqualTo(2);
        assertThat(overview.sampledAt()).isEqualTo(LocalDateTime.of(2026, 11, 13, 11, 55));
        // 在册失联计数（只标记待裁决清单的规模）＋最近清扫留痕直读
        assertThat(overview.lostWorkspaceCount()).isEqualTo(3);
        assertThat(overview.lastSweep().operatorName()).isEqualTo("运营管理员");
        assertThat(overview.lastSweep().sweptVolumes()).isEqualTo(2);
        // 请求路径零 docker（AC：响应秒级——不在请求路径跑 docker 长操作）
        verifyNoInteractions(environmentBackend);
    }

    @Test
    void given_never_sampled_when_overview_then_faces_null_as_is() {
        when(hostRepository.findById(HostObservationSample.SINGLETON_ID))
                .thenReturn(Optional.empty());
        when(lostMarkRepository.count()).thenReturn(0L);
        when(sweepActionRepository.findTopByOrderBySweptAtDescIdDesc())
                .thenReturn(Optional.empty());

        BackofficeResourceOverviewResponse overview = newService().overview();

        // 未采样的首个扫描窗内：各面如实 null（「没有帧」≠「零」）
        assertThat(overview.host()).isNull();
        assertThat(overview.docker()).isNull();
        assertThat(overview.orphans()).isNull();
        assertThat(overview.sampledAt()).isNull();
        assertThat(overview.lostWorkspaceCount()).isZero();
        assertThat(overview.lastSweep()).isNull();
        verifyNoInteractions(environmentBackend);
    }

    // ---------- sweep：复用 #486 收编面（同一准绳）＋留痕 ----------

    @Test
    void given_registry_when_sweep_then_same_keep_set_and_grace_as_scan_round() {
        Workspace alpha = readyWorkspace(41);
        Workspace beta = readyWorkspace(42);
        when(workspaceRepository.findAll()).thenReturn(List.of(alpha, beta));
        when(environmentBackend.sweepOrphanWorkspaceContainers(any(), any()))
                .thenReturn(new OrphanSweepReport(List.of(
                        item(OrphanSweepReport.Kind.CONTAINER, "ws-55",
                                OrphanSweepReport.Disposition.SWEPT, null),
                        item(OrphanSweepReport.Kind.CONTAINER, "ws-41",
                                OrphanSweepReport.Disposition.SKIPPED,
                                OrphanSweepReport.SkipReason.REGISTERED))));
        when(environmentBackend.sweepOrphanWorkspaceVolumes(any(), any()))
                .thenReturn(new OrphanSweepReport(List.of(
                        item(OrphanSweepReport.Kind.VOLUME, "vol-ws-77",
                                OrphanSweepReport.Disposition.SWEPT, null))));

        BackofficeResourceSweepResponse response = newService().sweep(
                new Operator("99001", "运营管理员"), NOW);

        // 同一准绳（#486 不另写一套）：保留集＝注册表全量编号（含休眠在册——用户
        // 数据永不进收编面）、宽限线＝触发时刻减 10 分钟（与扫描轮同常量同口径）
        verify(environmentBackend).sweepOrphanWorkspaceContainers(
                argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(41L, 42L))),
                eq(NOW.minusMinutes(10)));
        verify(environmentBackend).sweepOrphanWorkspaceVolumes(
                argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(41L, 42L))),
                eq(NOW.minusMinutes(10)));
        // 逐项报告合流（容器＋卷）＋计数分面＋宽限线呈现
        assertThat(response.registryReachable()).isTrue();
        assertThat(response.sweptContainers()).isEqualTo(1);
        assertThat(response.sweptVolumes()).isEqualTo(1);
        assertThat(response.skippedCount()).isEqualTo(1);
        assertThat(response.items()).hasSize(3);
        assertThat(response.items()).contains(
                new BackofficeResourceSweepResponse.SweepItem("CONTAINER", "ws-55",
                        "SWEPT", null, null),
                new BackofficeResourceSweepResponse.SweepItem("CONTAINER", "ws-41",
                        "SKIPPED", "REGISTERED", null));
        // append-only 留痕（#473 故事 12）：操作者＋逐项报告 JSON 全量落档
        ArgumentCaptor<WorkspaceSweepAction> trace =
                ArgumentCaptor.forClass(WorkspaceSweepAction.class);
        verify(sweepActionRepository).save(trace.capture());
        assertThat(trace.getValue().getOperatorId()).isEqualTo("99001");
        assertThat(trace.getValue().isRegistryReachable()).isTrue();
        assertThat(trace.getValue().getSweptContainers()).isEqualTo(1);
        assertThat(trace.getValue().getItemsJson()).contains("ws-55", "vol-ws-77");
        assertThat(trace.getValue().getSweptAt()).isEqualTo(NOW);
    }

    @Test
    void given_registry_unreachable_when_sweep_then_no_blade_and_reported_trace() {
        when(workspaceRepository.findAll()).thenThrow(new RuntimeException("库不可达"));

        BackofficeResourceSweepResponse response = newService().sweep(
                new Operator("99001", "运营管理员"), NOW);

        // 双闸之二（库不可达宁可漏删）：保留集无从构成绝不动手——一刀未动、
        // 如实呈报跳过原因（库不可达），留痕行照落（动了没动都要可审计）
        verifyNoInteractions(environmentBackend);
        assertThat(response.registryReachable()).isFalse();
        assertThat(response.items()).isEmpty();
        assertThat(response.sweptContainers()).isZero();
        assertThat(response.graceLine()).isEqualTo(NOW.minusMinutes(10));
        ArgumentCaptor<WorkspaceSweepAction> trace =
                ArgumentCaptor.forClass(WorkspaceSweepAction.class);
        verify(sweepActionRepository).save(trace.capture());
        assertThat(trace.getValue().isRegistryReachable()).isFalse();
        assertThat(trace.getValue().getItemsJson()).isEqualTo("[]");
    }

    @Test
    void given_missing_operator_header_when_sweep_then_traced_as_null_not_rejected() {
        when(workspaceRepository.findAll()).thenReturn(List.of());
        when(environmentBackend.sweepOrphanWorkspaceContainers(any(), any()))
                .thenReturn(new OrphanSweepReport(List.of()));
        when(environmentBackend.sweepOrphanWorkspaceVolumes(any(), any()))
                .thenReturn(new OrphanSweepReport(List.of()));

        BackofficeResourceSweepResponse response = newService().sweep(null, NOW);

        // 缺透传头落空口径（照 WorkspaceAction 先例）：不拒，留痕列 NULL
        assertThat(response.operatorId()).isNull();
        assertThat(response.operatorName()).isNull();
        verify(sweepActionRepository).save(argThat(action -> action.getOperatorId() == null
                && action.getOperatorName() == null));
    }

    // -------- 夹具 --------

    private static OrphanSweepReport.Item item(OrphanSweepReport.Kind kind, String identifier,
            OrphanSweepReport.Disposition disposition, OrphanSweepReport.SkipReason skipReason) {
        return new OrphanSweepReport.Item(kind, identifier, disposition, skipReason, null);
    }

    private Workspace readyWorkspace(long id) {
        Workspace pending = Workspace.registerPending(
                WorkspaceId.of(Long.toString(id)), EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(WorkspaceId.of(Long.toString(id)),
                        "ws-" + id, "previewnet")));
        return pending;
    }
}
