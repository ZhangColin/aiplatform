package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台项目文件包下载（#174，/api/backoffice/projects/{id}/files/package）在
 * {@code #152} seam 上全绿：封存项目直取封存包（零 docker）、未封存项目即时
 * 导出源码包（同订单源码包 packSource 内核——订单流程不动，构造上零交叠）。
 * docker/封存包存储收口在两端口（外部设施边界），真 daemon 数值验收归 #175。
 */
@BackofficeSeamTest
class BackofficeProjectFilesPackageSeamTest {

    /** 夹具号段（避开他票夹具段）。 */
    private static final long WS_LIVE = 720201L;      // 在线项目沙箱
    private static final long WS_SEALED = 720202L;    // 已封存项目沙箱（包在）
    private static final long WS_SEALED_BARE = 720203L;  // 已封存但无包记录（外部漂移形态）

    private static final byte[] SOURCE_BYTES = "源码包字节".getBytes();
    private static final byte[] ARCHIVE_BYTES = "封存包字节".getBytes();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @MockitoBean
    private EnvironmentBackend environmentBackend;

    @MockitoBean
    private SealPackageStore sealPackageStore;

    private Project liveProject;
    private Project sealedProject;
    private Project sealedBareProject;

    @BeforeEach
    void setUp() {
        liveProject = linkProject(WS_LIVE, "在线项目");
        sealedProject = linkProject(WS_SEALED, "封存项目");
        sealedBareProject = linkProject(WS_SEALED_BARE, "无包封存项目");

        when(environmentBackend.containerState(any())).thenAnswer(invocation -> {
            WorkspaceHandle handle = invocation.getArgument(0);
            return handle.containerName().equals(containerOf(WS_LIVE))
                    ? ContainerState.RUNNING : ContainerState.ABSENT;
        });
        when(environmentBackend.packSource(any())).thenReturn(SOURCE_BYTES);
        // 未封存沙箱若需唤醒重建：置备内核补齐供给（真实形状）
        when(environmentBackend.createWorkspace(any(), any())).thenAnswer(invocation -> {
            WorkspaceId id = invocation.getArgument(0);
            return WorkspaceProvision.of(WorkspaceHandle.dev(id,
                    WorkspaceNaming.containerName(id), WorkspaceNaming.PREVIEW_NETWORK));
        });
        when(sealPackageStore.open("/sealed/ws-720202.tar.gz")).thenReturn(ARCHIVE_BYTES);
    }

    @AfterEach
    void tearDown() {
        projectRepository.deleteAll();
        workspaceRepository.deleteAll();
    }

    @Test
    void given_live_project_when_signed_files_package_then_source_tar_gz_stream()
            throws Exception {
        signedGet(packagePath(liveProject))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/gzip"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + liveProject.getId() + "-source.tar.gz\""))
                .andExpect(content().bytes(SOURCE_BYTES));

        // 即时导出：同订单源码包导出的 packSource 内核
        verify(environmentBackend).packSource(handleOf(WS_LIVE));
    }

    @Test
    void given_sealed_project_when_signed_files_package_then_seal_archive_directly()
            throws Exception {
        signedGet(packagePath(sealedProject))
                .andExpect(status().isOk())
                .andExpect(content().bytes(ARCHIVE_BYTES))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + sealedProject.getId() + "-archive.tar.gz\""));

        // 直取封存包：零 docker（不探容器、不打包、不重建）
        verify(environmentBackend, never()).packSource(any());
        verify(environmentBackend, never()).createWorkspace(any(), any());
    }

    @Test
    void given_sealed_project_without_package_when_signed_files_package_then_wsp_016()
            throws Exception {
        signedGet(packagePath(sealedBareProject))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1016))
                .andExpect(jsonPath("$.message").value("封存包不存在或不可读"));
    }

    @Test
    void given_unknown_project_when_signed_files_package_then_prj_001() throws Exception {
        signedGet("/api/backoffice/projects/999999999/files/package")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    // -------- 夹具 --------

    private static String packagePath(Project project) {
        return "/api/backoffice/projects/" + project.getId() + "/files/package";
    }

    private ResultActions signedGet(String pathWithQuery) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null));
    }

    private static String containerOf(long id) {
        return WorkspaceNaming.containerName(WorkspaceId.of(Long.toString(id)));
    }

    private static WorkspaceHandle handleOf(long id) {
        return WorkspaceHandle.dev(WorkspaceId.of(Long.toString(id)), containerOf(id),
                WorkspaceNaming.PREVIEW_NETWORK);
    }

    /** 工作区（按需封存迁移）＋名下项目（软引用无 FK）。 */
    private Project linkProject(long workspaceId, String name) {
        WorkspaceId id = WorkspaceId.of(Long.toString(workspaceId));
        Workspace workspace = Workspace.dev(id, WorkspaceNaming.containerName(id),
                WorkspaceNaming.PREVIEW_NETWORK);
        if (workspaceId == WS_SEALED || workspaceId == WS_SEALED_BARE) {
            workspace.hibernate();
            workspace.seal(workspaceId == WS_SEALED
                    ? new SealPackage("/sealed/ws-720202.tar.gz", 4096L) : null,
                    LocalDateTime.of(2026, 9, 15, 10, 0));
        }
        workspaceRepository.save(workspace);
        return projectRepository.save(
                Project.create(name, ProjectType.WEBSITE, workspaceId, null));
    }
}
