package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.DesiredState;
import com.semgravio.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.semgravio.aiplatform.base.workspace.domain.model.SealPackage;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectType;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台沙箱观测面（#173，/api/backoffice/workspaces 清单/详情；#487 起采样落库）
 * 在 {@code #152} seam 上全绿：MockMvc 穿完整过滤链（签名验签 → 会话豁免 →
 * @RequireSignature 强制闸）→ 真应用服务（base.workspace 观测读模型 + 本域项目
 * 引用拼装）→ aiplatform_test 真库（wsp_workspaces/wsp_observation_samples/prj_projects
 * 真行）。#487 治超时口径：<b>清单/详情零 docker 调用</b>——实态与卷大小两列来自
 * 采样表（真库 exists 子查询过滤），响应秒级；{@code EnvironmentBackend} 以
 * {@code @MockitoBean} 收口且全程<b>零交互</b>（verifyNoInteractions 钉死——探查
 * 混进来即红）。采样写面（真 daemon 采样值与 du 实测一致）归
 * {@code WorkspaceObservationSamplingLiveTest}。
 */
@BackofficeSeamTest
class BackofficeWorkspaceSeamTest {

    /** 夹具沙箱号段（避开他票夹具的自增占位段）。 */
    private static final long WS_ALIVE = 700101L;      // 期望运行 + 采样运行中（健康）
    private static final long WS_DRIFT = 700102L;      // 期望运行 + 采样无容器（漂移行）
    private static final long WS_SLEEPING = 700103L;   // 期望休眠 + 采样无容器（正常收敛态）
    private static final long WS_SEALED = 700104L;     // 期望封存（卷已删，封存包元数据）
    private static final long WS_FRESH = 700105L;      // 新工作区（首个采样窗内，无采样帧）

    private static final LocalDateTime SAMPLED_AT =
            LocalDateTime.of(2026, 11, 13, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceObservationSampleRepository sampleRepository;

    @Autowired
    private ProjectRepository projectRepository;

    /** docker 依赖收口（#487 起观测读面零依赖）：整个用例类零交互即观测面无探查。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    @BeforeEach
    void setUp() {
        seedWorkspace(WS_ALIVE, DesiredState.RUNNING, null);
        seedWorkspace(WS_DRIFT, DesiredState.RUNNING, null);
        seedWorkspace(WS_SLEEPING, DesiredState.HIBERNATED, null);
        seedWorkspace(WS_SEALED, DesiredState.SEALED, new SealPackage("/sealed/ws-700104.tar.gz", 4096L));
        seedWorkspace(WS_FRESH, DesiredState.RUNNING, null);

        seedSample(WS_ALIVE, ContainerState.RUNNING, 104857600L);   // 100 MiB
        seedSample(WS_DRIFT, ContainerState.ABSENT, 209715200L);    // 200 MiB
        seedSample(WS_SLEEPING, ContainerState.ABSENT, 314572800L); // 300 MiB
        // 封存行采样帧带陈旧卷字节（封存前所采）：读面按注册表意图容缺卷列——
        // 「封存行不显示卷大小」是意图事实，不是采样缺失
        seedSample(WS_SEALED, ContainerState.ABSENT, 999L);
        // WS_FRESH 不落采样行：新工作区首个采样窗内的如实缺省形态
    }

    @AfterEach
    void tearDown() {
        projectRepository.deleteAll();
        sampleRepository.deleteAll();
        workspaceRepository.deleteAll();
    }

    // ---------- 清单：期望态 vs 采样实态两列如实分示 ----------

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
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.items", hasSize(5)))
                // 新沙箱在前（id 倒序定死）：未采样 → 封存 → 休眠 → 漂移 → 健康
                .andExpect(jsonPath("$.data.items[0].workspaceId").value(Long.toString(WS_FRESH)))
                // 未采样行：实态两列与新鲜度标注如实缺省（没有帧 ≠ 探到未知）
                .andExpect(jsonPath("$.data.items[0].containerState").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].containerStateName").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].volumeSizeBytes").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].sampledAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].workspaceId").value(Long.toString(WS_SEALED)))
                .andExpect(jsonPath("$.data.items[1].desiredState").value(3))
                .andExpect(jsonPath("$.data.items[1].containerState").value(3))
                // 漂移行一眼可见：期望运行（1）而采样实态无容器（3）
                .andExpect(jsonPath("$.data.items[3].workspaceId").value(Long.toString(WS_DRIFT)))
                .andExpect(jsonPath("$.data.items[3].desiredState").value(1))
                .andExpect(jsonPath("$.data.items[3].containerState").value(3))
                .andExpect(jsonPath("$.data.items[3].containerStateName").value("无容器"))
                .andExpect(jsonPath("$.data.items[3].desiredStateName").value("运行"))
                // 健康行：两列一致＋新鲜度标注随行
                .andExpect(jsonPath("$.data.items[4].containerState").value(1))
                .andExpect(jsonPath("$.data.items[4].volumeSizeBytes").value(104857600))
                .andExpect(jsonPath("$.data.items[4].sampledAt").exists())
                // 卷大小列如实（漂移行卷仍在——休眠语义之外的漂移也占存储）
                .andExpect(jsonPath("$.data.items[3].volumeSizeBytes").value(209715200))
                .andExpect(jsonPath("$.data.items[3].sampledAt").exists())
                // 封存行：封存时刻/包大小呈现，卷大小按意图容缺（陈旧采样不顶包）
                .andExpect(jsonPath("$.data.items[1].sealedAt").exists())
                .andExpect(jsonPath("$.data.items[1].archiveSizeBytes").value(4096))
                .andExpect(jsonPath("$.data.items[1].volumeSizeBytes").doesNotExist())
                // 项目引用：归属项目可认（归档位如实）；无所属项目容缺 null
                .andExpect(jsonPath("$.data.items[4].project.projectId").exists())
                .andExpect(jsonPath("$.data.items[4].project.name").value("健康项目的沙箱"))
                .andExpect(jsonPath("$.data.items[3].project.archived").value(true))
                .andExpect(jsonPath("$.data.items[2].project").doesNotExist())
                // last-touch 列在场
                .andExpect(jsonPath("$.data.items[4].lastTouchAt").exists());

        // 清单零 docker 调用（#487 AC）：观测读面纯读库——探查混进来即红
        verifyNoInteractions(environmentBackend);
    }

    // ---------- 过滤维度（实态过滤随采样落库转 SQL 侧） ----------

    @Test
    void given_actual_absent_filter_when_list_then_sampled_absent_rows_only() throws Exception {
        // actual=3 捞「采样无容器」清单：漂移 + 休眠/封存正常态；未采样行无从筛；
        // total 如实＝筛后计数（库侧 exists 子查询，不再全量探查后内存筛）
        signedGet("/api/backoffice/workspaces?actual=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[*].containerStateName",
                        containsInAnyOrder("无容器", "无容器", "无容器")));

        verifyNoInteractions(environmentBackend);
    }

    @Test
    void given_actual_running_filter_when_list_then_only_sampled_running() throws Exception {
        signedGet("/api/backoffice/workspaces?actual=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].workspaceId").value(Long.toString(WS_ALIVE)));

        verifyNoInteractions(environmentBackend);
    }

    @Test
    void given_desired_running_and_actual_absent_when_list_then_drift_row_only() throws Exception {
        // 组合过滤＝捞漂移工作清单：期望运行而采样实态已亡
        signedGet("/api/backoffice/workspaces?desired=1&actual=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].workspaceId").value(Long.toString(WS_DRIFT)))
                .andExpect(jsonPath("$.data.items[0].volumeSizeBytes").value(209715200));

        verifyNoInteractions(environmentBackend);
    }

    @Test
    void given_page_beyond_total_when_list_then_empty_page_as_is() throws Exception {
        // 5 行 size=3：第 1 页 3 行、第 2 页 2 行、第 3 页真空页
        signedGet("/api/backoffice/workspaces?page=3&size=3")
                .andExpect(status().isOk())
                // 空页如实：200 空清单非错误，total/页码照报
                .andExpect(jsonPath("$.data.total").value(5))
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

    // ---------- 详情（同口径零 docker） ----------

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
                .andExpect(jsonPath("$.data.status").value(ProvisioningStatus.READY.getCode()))
                .andExpect(jsonPath("$.data.provisionError").doesNotExist())
                .andExpect(jsonPath("$.data.desiredState").value(1))
                .andExpect(jsonPath("$.data.containerState").value(3))
                .andExpect(jsonPath("$.data.volumeSizeBytes").value(209715200))
                .andExpect(jsonPath("$.data.sampledAt").exists())
                .andExpect(jsonPath("$.data.lastTouchAt").exists())
                .andExpect(jsonPath("$.data.sealedAt").doesNotExist())
                .andExpect(jsonPath("$.data.archivePath").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists())
                .andExpect(jsonPath("$.data.resources", hasSize(0)))
                .andExpect(jsonPath("$.data.project.projectId").exists())
                .andExpect(jsonPath("$.data.project.name").value("漂移项目的沙箱"))
                .andExpect(jsonPath("$.data.project.archived").value(false));

        // 详情同口径零 docker 调用（#487）：观测读面纯读库
        verifyNoInteractions(environmentBackend);
    }

    @Test
    void given_unsampled_workspace_when_signed_detail_then_actual_columns_absent() throws Exception {
        signedGet("/api/backoffice/workspaces/" + WS_FRESH)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(Long.toString(WS_FRESH)))
                .andExpect(jsonPath("$.data.containerState").doesNotExist())
                .andExpect(jsonPath("$.data.containerStateName").doesNotExist())
                .andExpect(jsonPath("$.data.volumeSizeBytes").doesNotExist())
                .andExpect(jsonPath("$.data.sampledAt").doesNotExist());

        verifyNoInteractions(environmentBackend);
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

    /** 落一行采样帧（扫描轮旁路采样的落库形态）。 */
    private void seedSample(long id, ContainerState state, Long volumeBytes) {
        sampleRepository.save(WorkspaceObservationSample.record(
                WorkspaceId.of(Long.toString(id)), state, volumeBytes, SAMPLED_AT));
    }

    /** 工作区名下建项目（软引用无 FK）。 */
    private Project linkProject(long workspaceId, String name) {
        return projectRepository.save(
                Project.create(name, ProjectType.WEBSITE, null, workspaceId, null, null));
    }
}
