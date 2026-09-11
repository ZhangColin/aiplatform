package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
}
