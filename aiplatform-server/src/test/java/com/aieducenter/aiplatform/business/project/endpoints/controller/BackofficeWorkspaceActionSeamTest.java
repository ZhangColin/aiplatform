package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.business.project.application.CodingRunTrack;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台沙箱动作面（#174，/api/backoffice/workspaces/{id}/ 四动作）在 {@code #152}
 * seam 上全绿：MockMvc 穿完整过滤链（签名验签 → 会话豁免 → @RequireSignature
 * 强制闸）→ 真应用服务（business.project 组合项目事实 + base.workspace 动作
 * 用例）→ aiplatform_test 真库（wsp_workspaces/prj_projects/wsp_workspace_actions
 * 真行，留痕断言走 JdbcTemplate 级——票 AC 口径）。docker/封存包存储依赖照
 * {@code BackofficeWorkspaceSeamTest} 形制收口在两端口（外部设施边界）：脚本化
 * 假面按容器名应答；置备内核（provisionForWake→createWorkspace）走真置备器 +
 * 假面供给。真 daemon 数值验收归活体联调（#175）。
 */
@BackofficeSeamTest
class BackofficeWorkspaceActionSeamTest {

    /** 夹具沙箱号段（避开他票夹具段）。 */
    private static final long WS_RUNNING = 720101L;     // 期望运行 + 实态运行中
    private static final long WS_ASLEEP = 720102L;      // 期望休眠（容器已删）
    private static final long WS_SEALED = 720103L;      // 期望封存（卷已删）

    private static final String OPERATOR_ID = "900001";
    private static final String OPERATOR_NAME = "运营同学";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CodingRunTrack codingRunTrack;

    /** docker 依赖收口：脚本化假面（按容器名应答；置备成功翻实态）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    /** 封存包存储收口：平台侧磁盘/对象存储是外部设施，seam 只验编排。 */
    @MockitoBean
    private SealPackageStore sealPackageStore;

    /** 实态脚本（容器名 → 一瞥）：动作改变 docker 世界后由置备内核翻页。 */
    private final Map<String, ContainerState> states = new HashMap<>();

    @BeforeEach
    void setUp() {
        seedWorkspace(WS_RUNNING, DesiredState.RUNNING, null);
        seedWorkspace(WS_ASLEEP, DesiredState.HIBERNATED, null);
        seedWorkspace(WS_SEALED, DesiredState.SEALED,
                new SealPackage("/sealed/ws-720103.tar.gz", 4096L));

        states.put(containerOf(WS_RUNNING), ContainerState.RUNNING);
        states.put(containerOf(WS_ASLEEP), ContainerState.ABSENT);
        states.put(containerOf(WS_SEALED), ContainerState.ABSENT);
        when(environmentBackend.containerState(any())).thenAnswer(invocation -> {
            WorkspaceHandle handle = invocation.getArgument(0);
            return states.getOrDefault(handle.containerName(), ContainerState.UNKNOWN);
        });
        when(environmentBackend.volumeSizeBytes(any())).thenReturn(104857600L);
        when(environmentBackend.exposePort(any(), anyInt()))
                .thenAnswer(invocation -> java.net.URI.create("https://preview.example"));
        // 置备内核供给：重建即活（真实形状 = createWorkspace 落定副作用后回句柄）
        when(environmentBackend.createWorkspace(any(), any())).thenAnswer(invocation -> {
            WorkspaceId id = invocation.getArgument(0);
            states.put(containerOf(id.id()), ContainerState.RUNNING);
            return WorkspaceProvision.of(WorkspaceHandle.dev(id,
                    WorkspaceNaming.containerName(id), WorkspaceNaming.PREVIEW_NETWORK));
        });
        when(sealPackageStore.save(any(), any()))
                .thenAnswer(invocation -> new SealPackage("/sealed/fresh.tar.gz", 2048L));
        when(environmentBackend.packVolume(any())).thenReturn("整卷内容".getBytes());
    }

    @AfterEach
    void tearDown() {
        projectRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM wsp_workspace_actions");
        workspaceRepository.deleteAll();
    }

    // ---------- 四动作全通 ----------

    @Test
    void given_hibernated_workspace_when_signed_wake_then_rebuilt_ready_and_journaled()
            throws Exception {
        linkProject(WS_ASLEEP, "睡着的项目的沙箱");   // 已生成（helper 内置）→ 唤醒拉应用

        signedPost(actionPath(WS_ASLEEP, "wake"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(Long.toString(WS_ASLEEP)))
                .andExpect(jsonPath("$.data.desiredState").value(1))
                .andExpect(jsonPath("$.data.containerState").value(1))
                .andExpect(jsonPath("$.data.status").value(ProvisioningStatus.READY.getCode()));

        // 唤醒也是写口：动作行落库（who/what）
        assertActionRow(WS_ASLEEP, 1, OPERATOR_ID, OPERATOR_NAME);
    }

    @Test
    void given_running_workspace_when_signed_hibernate_then_container_removed_intent_flipped()
            throws Exception {
        signedPost(actionPath(WS_RUNNING, "hibernate"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.desiredState").value(2));

        verify(environmentBackend).hibernate(handleOf(WS_RUNNING));
        assertActionRow(WS_RUNNING, 2, OPERATOR_ID, OPERATOR_NAME);
        // 库内意图如实翻页
        assertThat(jdbcTemplate.queryForObject(
                "SELECT desired_state FROM wsp_workspaces WHERE id = ?", Integer.class,
                WS_RUNNING)).isEqualTo(2);
    }

    @Test
    void given_running_workspace_when_signed_rebuild_then_container_replaced_intent_running()
            throws Exception {
        linkProject(WS_RUNNING, "重建项目的沙箱").markGenerated();

        signedPost(actionPath(WS_RUNNING, "rebuild"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.desiredState").value(1))
                .andExpect(jsonPath("$.data.containerState").value(1));

        // rm 先行、重建紧随（卷保留）：两步都发生
        verify(environmentBackend).hibernate(handleOf(WS_RUNNING));
        verify(environmentBackend).createWorkspace(eq(workspaceIdOf(WS_RUNNING)), eq(EnvKind.DEV));
        assertActionRow(WS_RUNNING, 3, OPERATOR_ID, OPERATOR_NAME);
    }

    @Test
    void given_running_workspace_when_signed_seal_then_same_outcome_as_auto_seal()
            throws Exception {
        signedPost(actionPath(WS_RUNNING, "seal"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.desiredState").value(3))
                .andExpect(jsonPath("$.data.archivePath").value("/sealed/fresh.tar.gz"))
                .andExpect(jsonPath("$.data.archiveSizeBytes").value(2048));

        verify(environmentBackend).deleteVolume(handleOf(WS_RUNNING));
        assertActionRow(WS_RUNNING, 4, OPERATOR_ID, OPERATOR_NAME);
    }

    // ---------- run 在途：三动作拒绝（明确数字业务码 1015），唤醒不受限 ----------

    @Test
    void given_run_in_flight_when_signed_hibernate_rebuild_seal_then_refused_1015()
            throws Exception {
        Project project = linkProject(WS_RUNNING, "跑着 run 的项目的沙箱");
        codingRunTrack.begin(project.getId());
        try {
            for (String action : new String[] {"hibernate", "rebuild", "seal"}) {
                signedPost(actionPath(WS_RUNNING, action), null)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value(1015))
                        .andExpect(jsonPath("$.message")
                                .value("编码 run 进行中，沙箱动作被拒（先取消 run 或等收口）"));
            }
            // 拒绝零副作用：意图不动、不留痕、不动物理面
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT desired_state FROM wsp_workspaces WHERE id = ?", Integer.class,
                    WS_RUNNING)).isEqualTo(1);
            assertThat(actionCount(WS_RUNNING)).isEqualTo(0);
            verify(environmentBackend, never()).hibernate(any());
            verify(environmentBackend, never()).packVolume(any());
        } finally {
            codingRunTrack.end(project.getId());
        }
    }

    @Test
    void given_run_in_flight_when_signed_wake_then_allowed() throws Exception {
        Project project = linkProject(WS_ASLEEP, "跑着 run 的睡着项目的沙箱");
        codingRunTrack.begin(project.getId());
        try {
            signedPost(actionPath(WS_ASLEEP, "wake"), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.desiredState").value(1));
        } finally {
            codingRunTrack.end(project.getId());
        }
    }

    // ---------- 操作者留痕：透传头落库、缺头落空 ----------

    @Test
    void given_no_operator_headers_when_signed_hibernate_then_journaled_with_null_operator()
            throws Exception {
        mockMvc.perform(BackofficeSignatures.signed(
                        post(actionPath(WS_RUNNING, "hibernate")).contentType(MediaType.APPLICATION_JSON),
                        actionPath(WS_RUNNING, "hibernate"), ""))
                .andExpect(status().isOk());

        assertActionRow(WS_RUNNING, 2, null, null);   // 落空口径（#155）
    }

    // ---------- 状态/寻址负例 ----------

    @Test
    void given_sealed_workspace_when_signed_hibernate_or_rebuild_or_seal_then_wsp_009()
            throws Exception {
        for (String action : new String[] {"hibernate", "rebuild", "seal"}) {
            signedPost(actionPath(WS_SEALED, action), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1009));
        }
        assertThat(actionCount(WS_SEALED)).isEqualTo(0);
    }

    @Test
    void given_unknown_workspace_when_signed_any_action_then_wsp_001() throws Exception {
        signedPost("/api/backoffice/workspaces/999999999/wake", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1001));
    }

    // ---------- 签名负例 ----------

    @Test
    void given_no_signature_headers_when_post_action_then_401() throws Exception {
        mockMvc.perform(post(actionPath(WS_RUNNING, "hibernate")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // ---------- 详情兜底（动作响应＝观测详情口径） ----------

    @Test
    void given_action_response_shape_when_wake_then_observation_fields_present() throws Exception {
        linkProject(WS_ASLEEP, "带项目的睡着沙箱");

        signedPost(actionPath(WS_ASLEEP, "wake"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.project.name").value("带项目的睡着沙箱"))
                .andExpect(jsonPath("$.data.volumeSizeBytes").value(104857600))
                .andExpect(jsonPath("$.data.lastTouchAt").exists());
    }

    // -------- 夹具 --------

    private static String actionPath(long workspaceId, String action) {
        return "/api/backoffice/workspaces/" + workspaceId + "/" + action;
    }

    /** 签名 POST（无体动作；带操作者透传头）。 */
    private ResultActions signedPost(String path, String unusedBody) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                post(path).contentType(MediaType.APPLICATION_JSON), path, "")
                .header("X-User-Id", OPERATOR_ID)
                .header("X-User-Name", OPERATOR_NAME));
    }

    private static String containerOf(long id) {
        return WorkspaceNaming.containerName(WorkspaceId.of(Long.toString(id)));
    }

    private static WorkspaceId workspaceIdOf(long id) {
        return WorkspaceId.of(Long.toString(id));
    }

    private static WorkspaceHandle handleOf(long id) {
        return WorkspaceHandle.dev(workspaceIdOf(id), containerOf(id),
                WorkspaceNaming.PREVIEW_NETWORK);
    }

    /** 落一行工作区（READY 起点，按需迁移到休眠/封存意图）。 */
    private void seedWorkspace(long id, DesiredState desired, SealPackage sealed) {
        WorkspaceId workspaceId = workspaceIdOf(id);
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

    /** 工作区名下建项目（软引用无 FK），返回已保存实体供进一步迁移。 */
    private Project linkProject(long workspaceId, String name) {
        Project project = Project.create(name, ProjectType.WEBSITE, workspaceId, null);
        project.markGenerated();
        return projectRepository.save(project);
    }

    /** JdbcTemplate 级动作行断言（票 AC 口径：操作者留痕落库）。 */
    private void assertActionRow(long workspaceId, int actionCode,
            String operatorId, String operatorName) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT action, operator_id, operator_name, acted_at FROM wsp_workspace_actions"
                        + " WHERE workspace_id = ?",
                workspaceId);
        assertThat(row.get("action")).isEqualTo(actionCode);
        assertThat(row.get("operator_id")).isEqualTo(operatorId);
        assertThat(row.get("operator_name")).isEqualTo(operatorName);
        assertThat(row.get("acted_at")).isNotNull();
    }

    private int actionCount(long workspaceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wsp_workspace_actions WHERE workspace_id = ?",
                Integer.class, workspaceId);
        return count == null ? 0 : count;
    }
}
