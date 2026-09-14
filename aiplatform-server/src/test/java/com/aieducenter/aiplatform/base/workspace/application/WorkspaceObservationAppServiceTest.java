package com.aieducenter.aiplatform.base.workspace.application;

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
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 观测用例编排（#173）：mock 仓储＋环境后端，验机制形状——①实态过滤在探查后
 * 内存完成（total 如实＝筛后计数、页外行不探卷）；②不带实态过滤走 SQL 分页、
 * 只探当页；③封存行卷大小容缺（不探卷）＋封存元数据如实；④分页钳；⑤详情
 * 寻址 404 口径。docker 命令形状归 {@code DockerEnvironmentBackendObservationTest}，
 * 全链 JSON 契约归 {@code BackofficeWorkspaceSeamTest}。
 */
class WorkspaceObservationAppServiceTest {

    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final EnvironmentBackend environmentBackend = mock(EnvironmentBackend.class);
    private final WorkspaceObservationAppService service = new WorkspaceObservationAppService(
            workspaceRepository, environmentBackend);

    // ---------- 实态过滤：探查后内存完成 ----------

    @Test
    void given_actual_filter_when_observations_then_probed_filtered_and_paginated_in_memory() {
        Workspace alive = readyWorkspace(41);
        Workspace dead = readyWorkspace(42);
        Workspace waking = readyWorkspace(43);
        when(workspaceRepository.findAll(anySpec(), any(Sort.class))).thenReturn(List.of(alive, dead, waking));
        stubStates(alive, ContainerState.RUNNING, 100L);
        stubStates(dead, ContainerState.ABSENT, 200L);
        stubStates(waking, ContainerState.ABSENT, 300L);

        PageResponse<WorkspaceObservation> result =
                service.observations(null, ContainerState.ABSENT, 1, 20);

        // total 如实＝筛后计数（2 命中），非全量（3）
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(WorkspaceObservation::workspaceId)
                .containsExactly(42L, 43L);
        // 页外行（筛掉的 41）不探卷——卷探查只服务当页显示
        verify(environmentBackend, never()).volumeSizeBytes(handleOf(alive));
        assertThat(result.items().get(0).volumeSizeBytes()).isEqualTo(200L);
    }

    @Test
    void given_actual_filter_when_page_beyond_matched_then_empty_page_as_is() {
        when(workspaceRepository.findAll(anySpec(), any(Sort.class))).thenReturn(List.of(readyWorkspace(41)));
        stubStates(readyWorkspace(41), ContainerState.ABSENT, 100L);

        PageResponse<WorkspaceObservation> result =
                service.observations(null, ContainerState.ABSENT, 2, 20);

        // 空页如实：200 空清单非错误，total 仍报命中数
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(2);
    }

    // ---------- 不带实态过滤：SQL 分页、只探当页 ----------

    @Test
    void given_no_actual_filter_when_observations_then_sql_page_and_probe_page_only() {
        Workspace alive = readyWorkspace(41);
        Workspace sealed = sealedWorkspace(42);
        when(workspaceRepository.findAll(anySpec(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(alive, sealed)));

        PageResponse<WorkspaceObservation> result =
                service.observations(DesiredState.RUNNING, null, 1, 20);

        assertThat(result.total()).isEqualTo(2);
        // 封存行卷大小容缺：不探卷（卷已删），封存元数据如实呈现
        verify(environmentBackend, never()).volumeSizeBytes(handleOf(sealed));
        assertThat(result.items().get(1).volumeSizeBytes()).isNull();
        assertThat(result.items().get(1).sealedAt()).isNotNull();
        assertThat(result.items().get(1).archiveSizeBytes()).isEqualTo(4096L);
    }

    @Test
    void given_rogue_pagination_when_observations_then_clamped() {
        when(workspaceRepository.findAll(anySpec(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.observations(null, null, 0, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(workspaceRepository).findAll(anySpec(), pageable.capture());
        // page 0 归 1、size 500 钳 100（BackofficePages 口径）
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    // ---------- 详情 ----------

    @Test
    void given_existing_workspace_when_observation_then_full_facts() {
        Workspace workspace = readyWorkspace(42);
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        stubStates(workspace, ContainerState.STOPPED, 2048L);

        WorkspaceObservation observation = service.observation("42");

        assertThat(observation.workspaceId()).isEqualTo(42L);
        assertThat(observation.containerState()).isEqualTo(ContainerState.STOPPED);
        assertThat(observation.volumeSizeBytes()).isEqualTo(2048L);
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

    private void stubStates(Workspace workspace, ContainerState state, Long volumeBytes) {
        WorkspaceHandle handle = handleOf(workspace);
        when(environmentBackend.containerState(handle)).thenReturn(state);
        when(environmentBackend.volumeSizeBytes(handle)).thenReturn(volumeBytes);
    }

    private WorkspaceHandle handleOf(Workspace workspace) {
        return workspace.toHandle();
    }
}
