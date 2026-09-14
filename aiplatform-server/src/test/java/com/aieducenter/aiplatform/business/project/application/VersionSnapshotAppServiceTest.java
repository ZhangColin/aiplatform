package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.domain.model.SnapshotHandle;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionViewStartResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 「查看当时」编排（#92）：ref 寻址守卫（非成版 404，不触工作区）、并发上限守卫
 * （≤2，超限 409）、起/停快照的编排（viewId 生成 + 预览 URL 映射 + 在途注册表
 * 收口）。环境后端起快照的物理面由 {@code DockerEnvironmentBackendTest} 活体覆盖，
 * 本测试只验编排缝。
 */
@IntegrationTest
class VersionSnapshotAppServiceTest {

    private static final long OWNER = 3897654321098765432L;
    private static final String HASH = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
    private static final String WORKSPACE_ID = "9900";

    @Autowired
    private VersionSnapshotAppService service;

    @MockitoBean
    private ProjectRepository projectRepository;

    @MockitoBean
    private ProjectVersionAppService versionAppService;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    private Project project() {
        return Project.create("快照项目", null, Long.parseLong(WORKSPACE_ID), OWNER);
    }

    private void stubWorkspace(long projectId) {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project()));
    }

    @Test
    void given_valid_ref_when_start_view_then_view_id_and_preview_url_returned() {
        long projectId = 1001L;
        stubWorkspace(projectId);
        when(workspaceLifecycleAppService.startSnapshot(eq(WORKSPACE_ID), anyString(), eq(HASH)))
                .thenReturn(new SnapshotHandle("ws-9900-snap-1", URI.create("http://snap-9900.localhost/")));

        VersionViewStartResponse resp = service.startView(projectId, HASH);

        assertThat(resp.viewId()).isNotBlank();
        // #141：预览 URL 是环境后端拼好的网关子域（本层透传，不再拼 localhost:端口）
        assertThat(resp.previewUrl()).isEqualTo("http://snap-9900.localhost/");
        verify(versionAppService).requireVersion(any(Project.class), eq(HASH));
    }

    @Test
    void given_unknown_ref_when_start_view_then_404_without_snapshot() {
        long projectId = 1002L;
        stubWorkspace(projectId);
        doThrow(new ApplicationException(ProjectMessage.VERSION_NOT_FOUND))
                .when(versionAppService).requireVersion(any(Project.class), anyString());

        assertThatThrownBy(() -> service.startView(projectId, "beefbeef"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_NOT_FOUND.message());
        verify(workspaceLifecycleAppService, never()).startSnapshot(anyString(), anyString(), anyString());
    }

    @Test
    void given_two_active_views_when_start_third_then_409_limit() {
        long projectId = 1003L;
        stubWorkspace(projectId);
        when(workspaceLifecycleAppService.startSnapshot(eq(WORKSPACE_ID), anyString(), eq(HASH)))
                .thenReturn(new SnapshotHandle("ws-9900-snap-1", URI.create("http://snap-1.localhost/")),
                        new SnapshotHandle("ws-9900-snap-2", URI.create("http://snap-2.localhost/")));

        service.startView(projectId, HASH);
        service.startView(projectId, HASH);

        assertThatThrownBy(() -> service.startView(projectId, HASH))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_VIEW_LIMIT.message());
    }

    @Test
    void given_active_view_when_stop_then_snapshot_destroyed() {
        long projectId = 1004L;
        stubWorkspace(projectId);
        SnapshotHandle handle = new SnapshotHandle("ws-9900-snap-1", URI.create("http://snap-1.localhost/"));
        when(workspaceLifecycleAppService.startSnapshot(eq(WORKSPACE_ID), anyString(), eq(HASH)))
                .thenReturn(handle);

        String viewId = service.startView(projectId, HASH).viewId();

        service.stopView(projectId, viewId);
        verify(workspaceLifecycleAppService).stopSnapshot(handle);
    }

    @Test
    void given_unknown_view_id_when_stop_then_404() {
        assertThatThrownBy(() -> service.stopView(1005L, "unknown-view"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_VIEW_NOT_FOUND.message());
    }

    // ---------- 闲置快照清扫（#171：快照也是容器，同样是被扫的浪费源） ----------

    @Test
    void given_fresh_view_session_when_sweep_then_snapshot_kept() {
        long projectId = 1006L;
        stubWorkspace(projectId);
        SnapshotHandle handle = new SnapshotHandle("ws-9900-snap-1", URI.create("http://snap-1.localhost/"));
        when(workspaceLifecycleAppService.startSnapshot(eq(WORKSPACE_ID), anyString(), eq(HASH)))
                .thenReturn(handle);
        service.startView(projectId, HASH);

        // 会话起服后 10 分钟（阈值 60m 内）：在用快照不受影响（进保留集，孤儿清扫不碰）
        LocalDateTime tenMinutesLater = LocalDateTime.now().plusMinutes(10);
        int swept = service.sweepIdleViews(tenMinutesLater, Duration.ofMinutes(60));

        assertThat(swept).isZero();
        verify(workspaceLifecycleAppService, never()).stopSnapshot(any(SnapshotHandle.class));
        ArgumentCaptor<Set<String>> keep = ArgumentCaptor.forClass(Set.class);
        verify(workspaceLifecycleAppService).sweepOrphanSnapshots(keep.capture());
        // 在用会话进保留集（单例注册表跨用例累积，只锚本会话在内）
        assertThat(keep.getValue()).contains("ws-9900-snap-1");
    }

    @Test
    void given_stale_view_session_when_sweep_then_snapshot_destroyed_and_registry_cleared() {
        long projectId = 1007L;
        stubWorkspace(projectId);
        // 句柄名与先行用例区分（单例注册表累积下，等值句柄的销毁次数会并账）
        SnapshotHandle handle = new SnapshotHandle("ws-9900-snap-7", URI.create("http://snap-7.localhost/"));
        when(workspaceLifecycleAppService.startSnapshot(eq(WORKSPACE_ID), anyString(), eq(HASH)))
                .thenReturn(handle);
        String viewId = service.startView(projectId, HASH).viewId();

        // 起服后 2 小时无人关闭（阈值 60m）：清扫销毁 + 注册表清出（后续关闭变 404 幂等）。
        // 服务是单例、注册表跨用例累积，清扫数不定（同轮凡逾期皆扫）——只锚本会话的销毁
        LocalDateTime twoHoursLater = LocalDateTime.now().plusHours(2);
        int swept = service.sweepIdleViews(twoHoursLater, Duration.ofMinutes(60));

        assertThat(swept).isGreaterThanOrEqualTo(1);
        verify(workspaceLifecycleAppService, times(1)).stopSnapshot(handle);
        // 本会话已出注册表 → 不进保留集（孤儿兜底可回收其容器）
        ArgumentCaptor<Set<String>> keep = ArgumentCaptor.forClass(Set.class);
        verify(workspaceLifecycleAppService).sweepOrphanSnapshots(keep.capture());
        assertThat(keep.getValue()).doesNotContain("ws-9900-snap-7");
        assertThatThrownBy(() -> service.stopView(projectId, viewId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.VERSION_VIEW_NOT_FOUND.message());
    }
}
