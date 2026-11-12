package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceLostMark;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.DesiredState;
import com.semgravio.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.semgravio.aiplatform.base.workspace.domain.model.SealPackage;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 观测读模型（#487 采样落库后）：清单/详情纯读库（注册表＋采样表）——
 * <b>零环境后端依赖</b>（旧「逐行现场 docker 探查＋旁路容器 du」口径退役）。
 * 机制形状：①实态过滤随实态落采样表一并转 SQL 侧（exists 子查询，total 如实＝
 * 筛后计数——真库行为归 {@code BackofficeWorkspaceSeamTest}）；②采样帧拼装——
 * containerState/volumeSizeBytes 来自采样行、{@code sampledAt} 新鲜度标注随行；
 * ③封存行卷大小按注册表意图容缺（卷已删是意图事实，陈旧采样不顶包）；④未采样
 * 行（新工作区首个采样窗内）实态两列如实缺省 null；⑤分页钳制/寻址 404 口径
 * 照旧。采样写面归 {@code WorkspaceObservationSamplingAppServiceTest}。
 */
class WorkspaceObservationAppServiceTest {

    private static final LocalDateTime SAMPLED_AT =
            LocalDateTime.of(2026, 11, 13, 12, 0);

    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final WorkspaceObservationSampleRepository sampleRepository =
            mock(WorkspaceObservationSampleRepository.class);
    private final WorkspaceLostMarkRepository lostMarkRepository =
            mock(WorkspaceLostMarkRepository.class);
    private final WorkspaceObservationAppService service = new WorkspaceObservationAppService(
            workspaceRepository, sampleRepository, lostMarkRepository);

    // ---------- 清单：SQL 分页＋采样帧拼装 ----------

    @Test
    void given_samples_when_observations_then_rows_carry_sampled_facts_with_freshness() {
        Workspace alive = readyWorkspace(41);
        Workspace sleeping = readyWorkspace(42);
        when(workspaceRepository.findAll(anySpec(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sleeping, alive)));
        when(sampleRepository.findAllById(any()))
                .thenReturn(List.of(
                        sample(41, ContainerState.RUNNING, 100L),
                        sample(42, ContainerState.ABSENT, 200L)));

        PageResponse<WorkspaceObservation> result =
                service.observations(null, null, new Pagination(1, 20, null));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(WorkspaceObservation::workspaceId)
                .containsExactly(42L, 41L);
        // 实态两列来自采样行（零 docker），采样时刻随行（新鲜度标注）
        assertThat(result.items().get(1).containerState()).isEqualTo(ContainerState.RUNNING);
        assertThat(result.items().get(1).volumeSizeBytes()).isEqualTo(100L);
        assertThat(result.items().get(1).sampledAt()).isEqualTo(SAMPLED_AT);
        // 当页行按页批量取采样（一次 findAllById，非逐行）
        verify(sampleRepository).findAllById(any());
    }

    @Test
    void given_actual_filter_when_observations_then_sql_side_pagination_no_probe() {
        // 实态随采样落库后，实态过滤转 SQL 侧（旧「全量探查后内存分页」退役）——
        // 单次 findAll(spec, pageable)，分页/计数由库侧完成；spec 内的 exists
        // 子查询真库行为归 seam 测试，此处锁调用形状
        when(workspaceRepository.findAll(anySpec(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.observations(null, ContainerState.ABSENT, new Pagination(1, 20, null));

        verify(workspaceRepository).findAll(anySpec(), any(Pageable.class));
    }

    @Test
    void given_no_sample_yet_when_observations_then_actual_columns_absent() {
        // 新工作区首个采样窗内：未采样行如实缺省（实态 null ≠ UNKNOWN——探查失败
        // 是采到了 UNKNOWN，未采样是根本没有帧）
        when(workspaceRepository.findAll(anySpec(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(readyWorkspace(41))));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());

        PageResponse<WorkspaceObservation> result =
                service.observations(null, null, new Pagination(1, 20, null));

        assertThat(result.items().get(0).containerState()).isNull();
        assertThat(result.items().get(0).volumeSizeBytes()).isNull();
        assertThat(result.items().get(0).sampledAt()).isNull();
    }

    @Test
    void given_sealed_row_with_stale_volume_sample_when_observe_then_volume_null_by_intent() {
        // 封存 = 删卷换包（注册表意图，时刻新鲜）：卷大小按意图容缺——陈旧采样里的
        // 旧卷字节数不顶包；实态与采样时刻仍如实呈现（封存行照样采样实态）
        Workspace sealed = sealedWorkspace(42);
        when(workspaceRepository.findAll(anySpec(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sealed)));
        when(sampleRepository.findAllById(any()))
                .thenReturn(List.of(sample(42, ContainerState.ABSENT, 999L)));

        PageResponse<WorkspaceObservation> result =
                service.observations(null, null, new Pagination(1, 20, null));

        assertThat(result.items().get(0).volumeSizeBytes()).isNull();
        assertThat(result.items().get(0).containerState()).isEqualTo(ContainerState.ABSENT);
        assertThat(result.items().get(0).sampledAt()).isEqualTo(SAMPLED_AT);
        assertThat(result.items().get(0).sealedAt()).isNotNull();
        assertThat(result.items().get(0).archiveSizeBytes()).isEqualTo(4096L);
    }

    @Test
    void given_rogue_pagination_when_observations_then_clamped() {
        when(workspaceRepository.findAll(anySpec(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());

        service.observations(null, null, new Pagination(0, 500, null));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(workspaceRepository).findAll(anySpec(), pageable.capture());
        // page 0 归 1、size 500 钳 100（框架 Pagination 口径）
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        // 排序 id 倒序定死（新沙箱在前）
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
    }

    // ---------- 在册失联标记列（#488：清单/详情可见，待人工裁决的锚） ----------

    @Test
    void given_lost_marks_when_observations_then_rows_carry_lost_at() {
        LocalDateTime markedAt = LocalDateTime.of(2026, 11, 12, 8, 0);
        Workspace lost = readyWorkspace(42);
        Workspace healthy = readyWorkspace(41);
        when(workspaceRepository.findAll(anySpec(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(lost, healthy)));
        when(sampleRepository.findAllById(any())).thenReturn(List.of());
        when(lostMarkRepository.findAllById(any())).thenReturn(List.of(
                WorkspaceLostMark.mark(WorkspaceId.of("42"), markedAt)));

        PageResponse<WorkspaceObservation> result =
                service.observations(null, null, new Pagination(1, 20, null));

        // 失联行带判定时刻（后台清单可见）、健康行 null；当页批量取标记（一次 findAllById）
        assertThat(result.items().get(0).lostAt()).isEqualTo(markedAt);
        assertThat(result.items().get(1).lostAt()).isNull();
        verify(lostMarkRepository).findAllById(any());
    }

    @Test
    void given_no_mark_when_detail_then_lost_at_null() {
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(readyWorkspace(42)));
        when(sampleRepository.findById(42L)).thenReturn(Optional.empty());
        when(lostMarkRepository.findById(42L)).thenReturn(Optional.empty());

        assertThat(service.observation("42").lostAt()).isNull();
    }

    // ---------- 详情 ----------

    @Test
    void given_existing_workspace_when_observation_then_full_facts_with_freshness() {
        Workspace workspace = readyWorkspace(42);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        when(sampleRepository.findById(42L))
                .thenReturn(Optional.of(sample(42, ContainerState.STOPPED, 2048L)));

        WorkspaceObservation observation = service.observation("42");

        assertThat(observation.workspaceId()).isEqualTo(42L);
        assertThat(observation.containerState()).isEqualTo(ContainerState.STOPPED);
        assertThat(observation.volumeSizeBytes()).isEqualTo(2048L);
        assertThat(observation.sampledAt()).isEqualTo(SAMPLED_AT);
        assertThat(observation.desiredState()).isEqualTo(DesiredState.RUNNING);
        assertThat(observation.status()).isEqualTo(ProvisioningStatus.READY);
    }

    @Test
    void given_unknown_or_malformed_id_when_observation_then_wsp_001() {
        when(workspaceRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.observation("999"))
                .isInstanceOf(ApplicationException.class)
                .hasMessage("工作区不存在");
        assertThatThrownBy(() -> service.observation("not-a-tsid"))
                .isInstanceOf(ApplicationException.class)
                .hasMessage("工作区不存在");
    }

    /** 期望态 Specification 的 null 兼容匹配器（desired 缺省时 spec 为 null）。 */
    private static Specification<Workspace> anySpec() {
        return ArgumentMatchers.any();
    }

    // -------- 夹具 --------

    private Workspace readyWorkspace(long id) {
        WorkspaceId workspaceId = WorkspaceId.of(Long.toString(id));
        return Workspace.dev(workspaceId, WorkspaceNaming.containerName(workspaceId),
                WorkspaceNaming.PREVIEW_NETWORK);
    }

    /** 封存态工作区（READY → 休眠 → 封存，带包元数据）。 */
    private Workspace sealedWorkspace(long id) {
        Workspace workspace = readyWorkspace(id);
        workspace.hibernate();
        return workspace.seal(new SealPackage("/sealed/ws.tar.gz", 4096L),
                LocalDateTime.of(2026, 9, 15, 10, 0));
    }

    private WorkspaceObservationSample sample(long workspaceId, ContainerState state,
            Long volumeBytes) {
        return WorkspaceObservationSample.record(
                WorkspaceId.of(Long.toString(workspaceId)), state, volumeBytes, SAMPLED_AT);
    }
}
