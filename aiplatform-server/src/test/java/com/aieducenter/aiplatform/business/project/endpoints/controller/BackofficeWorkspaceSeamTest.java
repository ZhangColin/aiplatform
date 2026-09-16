package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

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
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台沙箱观测面（#173，/api/backoffice/workspaces 清单/详情）在 {@code #152}
 * seam 上全绿：MockMvc 穿完整过滤链（签名验签 → 会话豁免 → @RequireSignature
 * 强制闸）→ 真应用服务（base.workspace 观测用例 + 本域项目引用拼装）→
 * aiplatform_test 真库（wsp_workspaces/prj_projects 真行）。docker 依赖照
 * {@code BackofficeProjectFilesSeamTest} 形制收口在 {@code EnvironmentBackend}
 * （外部设施边界）：实态/卷大小两探查按容器名脚本化，封存行「不探卷」以
 * mock 零调用钉死。真 daemon 数值验收归活体联调（#175）。
 */
@BackofficeSeamTest
class BackofficeWorkspaceSeamTest {

    /** 夹具沙箱号段（避开他票夹具的自增占位段）。 */
    private static final long WS_ALIVE = 700101L;      // 期望运行 + 实态运行中（健康）
    private static final long WS_DRIFT = 700102L;      // 期望运行 + 实态无容器（漂移行）
    private static final long WS_SLEEPING = 700103L;   // 期望休眠 + 实态无容器（正常收敛态）
    private static final long WS_SEALED = 700104L;     // 期望封存（卷已删，封存包元数据）

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ProjectRepository projectRepository;

    /** docker 依赖收口：实态一瞥与卷用量两探查脚本化（按容器名）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    @BeforeEach
    void setUp() {
        seedWorkspace(WS_ALIVE, DesiredState.RUNNING, null);
        seedWorkspace(WS_DRIFT, DesiredState.RUNNING, null);
        seedWorkspace(WS_SLEEPING, DesiredState.HIBERNATED, null);
        seedWorkspace(WS_SEALED, DesiredState.SEALED, new SealPackage("/sealed/ws-700104.tar.gz", 4096L));

        Map<String, ContainerState> states = new HashMap<>();
        states.put(containerOf(WS_ALIVE), ContainerState.RUNNING);
        states.put(containerOf(WS_DRIFT), ContainerState.ABSENT);
        states.put(containerOf(WS_SLEEPING), ContainerState.ABSENT);
        states.put(containerOf(WS_SEALED), ContainerState.ABSENT);
        when(environmentBackend.containerState(any())).thenAnswer(invocation -> {
            WorkspaceHandle handle = invocation.getArgument(0);
            return states.getOrDefault(handle.containerName(), ContainerState.UNKNOWN);
        });
        when(environmentBackend.volumeSizeBytes(any())).thenAnswer(invocation -> {
            WorkspaceHandle handle = invocation.getArgument(0);
            return switch (handle.containerName()) {
                case "ws-700101" -> 104857600L;   // 100 MiB
                case "ws-700102" -> 209715200L;   // 200 MiB
                case "ws-700103" -> 314572800L;   // 300 MiB
                default -> null;
            };
        });
    }

    @AfterEach
    void tearDown() {
        projectRepository.deleteAll();
        workspaceRepository.deleteAll();
    }

    // ---------- 清单：期望态 vs 实态两列如实分示 ----------

    @Test
    void given_running_and_drift_and_hibernated_and_sealed_when_signed_list_then_two_columns_honest()
            throws Exception {
        linkProject(WS_ALIVE, "健康项目的沙箱");
        Project archived = linkProject(WS_DRIFT, "漂移项目的沙箱");
        archived.archive();
        projectRepository.save(archived);

        signedGet("/api/backoffice/workspaces")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items", hasSize(4)))
                // 新沙箱在前（id 倒序定死）：封存 → 休眠 → 漂移 → 健康
                .andExpect(jsonPath("$.data.items[0].workspaceId").value(Long.toString(WS_SEALED)))
                .andExpect(jsonPath("$.data.items[0].desiredState").value(3))
                .andExpect(jsonPath("$.data.items[0].containerState").value(3))
                // 漂移行一眼可见：期望运行（1）而实态无容器（3）
                .andExpect(jsonPath("$.data.items[2].workspaceId").value(Long.toString(WS_DRIFT)))
                .andExpect(jsonPath("$.data.items[2].desiredState").value(1))
                .andExpect(jsonPath("$.data.items[2].containerState").value(3))
                .andExpect(jsonPath("$.data.items[2].containerStateName").value("无容器"))
                .andExpect(jsonPath("$.data.items[2].desiredStateName").value("运行"))
                // 健康行：两列一致
                .andExpect(jsonPath("$.data.items[3].containerState").value(1))
                .andExpect(jsonPath("$.data.items[3].volumeSizeBytes").value(104857600))
                // 卷大小列如实（漂移行卷仍在——休眠语义之外的漂移也占存储）
                .andExpect(jsonPath("$.data.items[2].volumeSizeBytes").value(209715200))
                // 封存行：封存时刻/包大小呈现，卷大小容缺（卷已删）
                .andExpect(jsonPath("$.data.items[0].sealedAt").exists())
                .andExpect(jsonPath("$.data.items[0].archiveSizeBytes").value(4096))
                .andExpect(jsonPath("$.data.items[0].volumeSizeBytes").doesNotExist())
                // 项目引用：归属项目可认（归档位如实）；无所属项目容缺 null
                .andExpect(jsonPath("$.data.items[3].project.projectId").exists())
                .andExpect(jsonPath("$.data.items[3].project.name").value("健康项目的沙箱"))
                .andExpect(jsonPath("$.data.items[2].project.archived").value(true))
                .andExpect(jsonPath("$.data.items[1].project").doesNotExist())
                // last-touch 列在场
                .andExpect(jsonPath("$.data.items[3].lastTouchAt").exists());
    }

    @Test
    void given_sealed_workspace_when_list_then_volume_never_probed() throws Exception {
        // 封存行卷大小容缺不是「探了没有」而是「根本不探」：卷已删，探查无意义
        signedGet("/api/backoffice/workspaces?desired=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].workspaceId").value(Long.toString(WS_SEALED)));

        verify(environmentBackend, never()).volumeSizeBytes(handleOf(WS_SEALED));
    }

    // ---------- 过滤维度 ----------

    @Test
    void given_actual_absent_filter_when_list_then_drift_and_converged_rows_only() throws Exception {
        // actual=3 捞「无容器」清单：漂移 + 休眠/封存正常态；total 如实＝筛后计数
        signedGet("/api/backoffice/workspaces?actual=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[*].containerStateName",
                        containsInAnyOrder("无容器", "无容器", "无容器")));
    }

    @Test
    void given_desired_running_and_actual_absent_when_list_then_drift_row_only() throws Exception {
        // 组合过滤＝捞漂移工作清单：期望运行而实态已亡
        signedGet("/api/backoffice/workspaces?desired=1&actual=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].workspaceId").value(Long.toString(WS_DRIFT)))
                .andExpect(jsonPath("$.data.items[0].volumeSizeBytes").value(209715200));
    }

    @Test
    void given_page_beyond_total_when_list_then_empty_page_as_is() throws Exception {
        // 4 行 size=3：第 1 页 3 行、第 2 页 1 行、第 3 页真空页
        signedGet("/api/backoffice/workspaces?page=3&size=3")
                .andExpect(status().isOk())
                // 空页如实：200 空清单非错误，total/页码照报
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.page").value(3));
    }

    @Test
    void given_rogue_pagination_when_list_then_clamped() throws Exception {
        signedGet("/api/backoffice/workspaces?page=0&size=500")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void given_illegal_filter_code_when_list_then_framework_envelope() throws Exception {
        signedGet("/api/backoffice/workspaces?desired=99")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("desired 取值 99 非法，合法取值：1=运行, 2=休眠, 3=封存"));
        signedGet("/api/backoffice/workspaces?actual=not-a-code")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("actual 取值 not-a-code 非法，合法取值：1=运行中, 2=已停止, 3=无容器, 4=未知"));
        signedGet("/api/backoffice/workspaces?page=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parameter validation failed"));
    }

    // ---------- 详情 ----------

    @Test
    void given_existing_workspace_when_signed_detail_then_full_fields_with_project_ref()
            throws Exception {
        linkProject(WS_DRIFT, "漂移项目的沙箱");

        signedGet("/api/backoffice/workspaces/" + WS_DRIFT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(Long.toString(WS_DRIFT)))
                .andExpect(jsonPath("$.data.containerName").value(containerOf(WS_DRIFT)))
                .andExpect(jsonPath("$.data.networkName").value(WorkspaceNaming.PREVIEW_NETWORK))
                .andExpect(jsonPath("$.data.kind").value(1))
                .andExpect(jsonPath("$.data.status").value(
                        ProvisioningStatus.READY.getCode()))
                .andExpect(jsonPath("$.data.provisionError").doesNotExist())
                .andExpect(jsonPath("$.data.desiredState").value(1))
                .andExpect(jsonPath("$.data.containerState").value(3))
                .andExpect(jsonPath("$.data.volumeSizeBytes").value(209715200))
                .andExpect(jsonPath("$.data.lastTouchAt").exists())
                .andExpect(jsonPath("$.data.sealedAt").doesNotExist())
                .andExpect(jsonPath("$.data.archivePath").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists())
                .andExpect(jsonPath("$.data.resources", hasSize(0)))
                .andExpect(jsonPath("$.data.project.projectId").exists())
                .andExpect(jsonPath("$.data.project.name").value("漂移项目的沙箱"))
                .andExpect(jsonPath("$.data.project.archived").value(false));
    }

    @Test
    void given_unknown_or_malformed_workspace_when_signed_detail_then_wsp_001() throws Exception {
        for (String id : new String[] {"999999999", "not-a-tsid"}) {
            signedGet("/api/backoffice/workspaces/" + id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("工作区不存在"));
        }
    }

    // ---------- 签名负例 ----------

    @Test
    void given_no_signature_headers_when_get_workspaces_then_401_signature_required()
            throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(get("/api/backoffice/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // -------- 夹具 --------

    /** 签名 GET（无体）：pathWithQuery 原样参与签名计算（与验签 Filter 同构）。 */
    private ResultActions signedGet(String pathWithQuery) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null));
    }

    private static String containerOf(long id) {
        return WorkspaceNaming.containerName(WorkspaceId.of(Long.toString(id)));
    }

    private WorkspaceHandle handleOf(long id) {
        return WorkspaceHandle.dev(WorkspaceId.of(Long.toString(id)), containerOf(id),
                WorkspaceNaming.PREVIEW_NETWORK);
    }

    /** 落一行工作区（READY 起点，按需迁移到休眠/封存意图）。 */
    private void seedWorkspace(long id, DesiredState desired, SealPackage sealed) {
        WorkspaceId workspaceId = WorkspaceId.of(Long.toString(id));
        Workspace workspace = Workspace.dev(workspaceId,
                WorkspaceNaming.containerName(workspaceId), WorkspaceNaming.PREVIEW_NETWORK);
        if (desired == DesiredState.HIBERNATED) {
            workspace.hibernate();
        } else if (desired == DesiredState.SEALED) {
            workspace.hibernate();
            workspace.seal(sealed, LocalDateTime.of(2026, 9, 15, 10, 0));
        }
        workspaceRepository.save(workspace);
    }

    /** 工作区名下建项目（软引用无 FK）。 */
    private Project linkProject(long workspaceId, String name) {
        return projectRepository.save(
                Project.create(name, ProjectType.WEBSITE, workspaceId, null));
    }
}
