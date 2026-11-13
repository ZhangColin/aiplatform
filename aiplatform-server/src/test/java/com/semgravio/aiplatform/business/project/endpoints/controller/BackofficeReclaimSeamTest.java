package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceReclaimAction;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.ReclaimScope;
import com.semgravio.aiplatform.base.workspace.domain.model.ThreeCleanCheck;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceReclaimActionRepository;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectType;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台一键回收（#490，/api/backoffice/projects/{id}/reclaim 与
 * /api/backoffice/workspaces/{id}/reclaim）在 {@code #152} seam 上全绿：MockMvc
 * 穿完整过滤链（签名验签 → 会话豁免 → @RequireSignature 强制闸）→ 真组合应用
 * 服务（删除级联内核＋base 回收面）→ aiplatform_test 真库（prj_projects/
 * wsp_reclaim_actions 真行）。销毁内核（#485 互斥＋三清）以 {@code @MockitoBean}
 * 收口按三清事实应答（内核面归 WorkspaceLifecycleAppServiceTest 与 live 类）；
 * 留真库断言：留痕行（append-only）＋项目行去留（项目级删净/工作区级不动）。
 */
@BackofficeSeamTest
class BackofficeReclaimSeamTest {

    /** 夹具号段（避开他票夹具段）。 */
    private static final long WS_ID = 730201L;

    private static final String OPERATOR_ID = "900401";
    private static final String OPERATOR_NAME = "运营·回收员";

    /** 干净三清事实（探查成形、三面全清）。 */
    private static final ThreeCleanCheck CLEAN =
            ThreeCleanCheck.of(ContainerState.ABSENT, false, false);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceReclaimActionRepository reclaimActionRepository;

    /** 销毁内核 mock（#485 面归 WorkspaceLifecycleAppServiceTest 与 live 类）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        reclaimActionRepository.deleteAll();
        projectRepository.findByWorkspaceId(WS_ID).ifPresent(projectRepository::delete);
    }

    // ---------- 项目级：四面报告＋留痕 ----------

    @Test
    void given_project_when_signed_reclaim_project_then_four_faces_report_and_trace()
            throws Exception {
        Long projectId = seedProject();
        when(workspaceLifecycleAppService.destroyForReclaim(Long.toString(WS_ID)))
                .thenReturn(CLEAN);

        signedPost("/api/backoffice/projects/" + projectId + "/reclaim")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.scope").value("PROJECT"))
                .andExpect(jsonPath("$.data.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.data.workspaceId").value(Long.toString(WS_ID)))
                .andExpect(jsonPath("$.data.runsStopped").value(false))
                .andExpect(jsonPath("$.data.container").value("CLEAN"))
                .andExpect(jsonPath("$.data.volume").value("CLEAN"))
                .andExpect(jsonPath("$.data.registryRow").value("CLEAN"))
                .andExpect(jsonPath("$.data.projectRow").value("CLEAN"))
                .andExpect(jsonPath("$.data.residueFree").value(true))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME))
                .andExpect(jsonPath("$.data.reclaimedAt").exists());

        // 项目行删净（四面之一，真库）＋append-only 留痕（操作者＋四面事实）
        assertThat(projectRepository.findById(projectId)).isEmpty();
        List<WorkspaceReclaimAction> traces = reclaimActionRepository.findAll();
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getScope()).isEqualTo(ReclaimScope.PROJECT);
        assertThat(traces.get(0).getProjectId()).isEqualTo(projectId);
        assertThat(traces.get(0).getOperatorId()).isEqualTo(OPERATOR_ID);
        assertThat(traces.get(0).allClean()).isTrue();
    }

    @Test
    void given_volume_residue_when_signed_reclaim_project_then_report_red() throws Exception {
        // 残留如实呈报：销毁尽力后卷仍在（占用等）——回执红不虚报全清、归收编通道
        Long projectId = seedProject();
        when(workspaceLifecycleAppService.destroyForReclaim(anyString()))
                .thenReturn(ThreeCleanCheck.of(ContainerState.ABSENT, true, false));

        signedPost("/api/backoffice/projects/" + projectId + "/reclaim")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.volume").value("RESIDUE"))
                .andExpect(jsonPath("$.data.projectRow").value("CLEAN"))
                .andExpect(jsonPath("$.data.residueFree").value(false));

        assertThat(projectRepository.findById(projectId))
                .as("受理即删：销毁尽力后项目行照删").isEmpty();
        assertThat(reclaimActionRepository.findAll().get(0).isVolumeClean()).isFalse();
    }

    // ---------- 工作区级：三清＋项目面不动 ----------

    @Test
    void given_linked_project_when_signed_reclaim_workspace_then_three_clean_project_untouched()
            throws Exception {
        Long projectId = seedProject();
        when(workspaceLifecycleAppService.destroyForReclaim(Long.toString(WS_ID)))
                .thenReturn(CLEAN);

        signedPost("/api/backoffice/workspaces/" + WS_ID + "/reclaim")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("WORKSPACE"))
                .andExpect(jsonPath("$.data.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.data.workspaceId").value(Long.toString(WS_ID)))
                .andExpect(jsonPath("$.data.container").value("CLEAN"))
                .andExpect(jsonPath("$.data.volume").value("CLEAN"))
                .andExpect(jsonPath("$.data.registryRow").value("CLEAN"))
                .andExpect(jsonPath("$.data.projectRow").value("UNTOUCHED"))
                .andExpect(jsonPath("$.data.residueFree").value(true));

        // 项目面不动（真库）＋留痕 scope=WORKSPACE
        assertThat(projectRepository.findById(projectId)).isPresent();
        WorkspaceReclaimAction trace = reclaimActionRepository.findAll().get(0);
        assertThat(trace.getScope()).isEqualTo(ReclaimScope.WORKSPACE);
        assertThat(trace.getProjectRowClean()).isNull();
    }

    // ---------- 寻址负例（错误码前缀随寻址对象） ----------

    @Test
    void given_unknown_or_malformed_target_when_signed_reclaim_then_404() throws Exception {
        when(workspaceLifecycleAppService.destroyForReclaim(anyString()))
                .thenThrow(new com.cartisan.core.exception.ApplicationException(
                        com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage.WORKSPACE_NOT_FOUND));
        signedPost("/api/backoffice/projects/999999999/reclaim")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        for (String id : new String[] {"999999999", "not-a-tsid"}) {
            signedPost("/api/backoffice/workspaces/" + id + "/reclaim")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("工作区不存在"));
        }
        // 未成动作不留痕（寻址失败零副作用）
        assertThat(reclaimActionRepository.findAll()).isEmpty();
    }

    // ---------- 签名负例 ----------

    @Test
    void given_no_signature_headers_when_reclaim_then_401_signature_required() throws Exception {
        // 全裸请求落到类级 @RequireSignature 强制闸：无用户会话也无签名 → 401
        mockMvc.perform(post("/api/backoffice/projects/123/reclaim")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
        mockMvc.perform(post("/api/backoffice/workspaces/123/reclaim")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // -------- 夹具 --------

    /** 签名 POST（无体）＋操作者透传头（留痕锚）。 */
    private ResultActions signedPost(String path) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                post(path).contentType(MediaType.APPLICATION_JSON), path, "")
                .header("X-User-Id", OPERATOR_ID)
                .header("X-User-Name", OPERATOR_NAME));
    }

    /** 落一行带账户属主的项目（SSE 归属路由键要求至少其一）。 */
    private Long seedProject() {
        Project project = projectRepository.save(Project.create(
                "回收对象", ProjectType.WEBSITE, null, WS_ID, 730301L, null));
        return project.getId();
    }
}
