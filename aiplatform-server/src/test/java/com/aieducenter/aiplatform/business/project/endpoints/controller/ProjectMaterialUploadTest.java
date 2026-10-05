package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.Instant;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMaterials;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 物料上传端到端（#286，ADR-0027 图片管道底座，@IntegrationTest 隔离库＋
 * EnvironmentBackend 假面）：真过滤链（BFF 会话绑定 → ApiAuth 闸）→ 真应用/
 * 物料服务 → aiplatform_test 真库。契约面：multipart 落 materials/ 目录
 * （响应 path/name/size——path 即随话发送的附件载荷引用）、容器写入命令＝
 * ProjectMaterials.uploadCommand 正本、上传字节经 stdin 原样灌入（含 NUL 的
 * 二进制不经 shell 参数面）；守卫面：非五格式 4044 / 超 10MB 4043（判定层
 * 拒绝、docker 零触达）/ 已归档 4013 / 项目不存在 4001；写失败 1002
 * （WSP_002）；越权面＝既有会话闸（无会话 401，单账号 v1 口径）。
 */
@IntegrationTest
@AutoConfigureMockMvc
class ProjectMaterialUploadTest {

    /** PNG 魔数＋载荷（含 NUL——二进制字节经 stdin 原样灌入的钉子）。 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01, 0x02};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private BffSessionStore sessionStore;

    /** docker 依赖收口（写入正本＝stdin 灌字节，形制同 #283 seam）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private static final String SESSION_ID = "material-upload-test-session";

    private Long projectId;
    private long workspaceSeq = 930200L;

    /** 全上下文过滤链要求真会话（BffSessionContextFilter 绑定 + ApiAuth 拦截）。 */
    private ResultActions performAsUser(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.cookie(new Cookie("aiplatform_session", SESSION_ID)));
    }

    private MockHttpServletRequestBuilder upload(String filename, byte[] content) {
        return multipart("/api/projects/{id}/materials", projectId).file(
                new MockMultipartFile("file", filename, "image/png", content));
    }

    /** 假面成功回执：写入命令与字节由测试捕获断言，回执=字节数。 */
    private void givenWriteSucceedsReturning(String receipt) {
        when(environmentBackend.execWithStdin(any(WorkspaceHandle.class), anyString(), any()))
                .thenAnswer(invocation -> new ExecResult(receipt, "", 0));
    }

    @BeforeEach
    void seed() {
        sessionStore.put(SESSION_ID, new BffSession(
                1L, "物料上传测试", null, null, null, Instant.now().plusSeconds(3600)));
        Workspace ready = Workspace.registerPending(
                WorkspaceId.of(Long.toString(workspaceSeq)), EnvKind.DEV);
        ready.complete(WorkspaceProvision.of(WorkspaceHandle.dev(
                WorkspaceId.of(Long.toString(workspaceSeq)),
                "ws-" + workspaceSeq, "previewnet")));
        workspaceRepository.save(ready);
        projectId = projectRepository.save(
                Project.create("物料上传测试", null, workspaceSeq, 1L)).getId();
    }

    @AfterEach
    void cleanup() {
        sessionStore.remove(SESSION_ID);
        if (projectId != null) {
            projectRepository.findById(projectId).ifPresent(projectRepository::delete);
            workspaceRepository.findById(workspaceSeq).ifPresent(workspaceRepository::delete);
        }
    }

    // ---------- 契约：multipart 落物料目录，字节经 stdin 原样灌入 ----------

    @Test
    void given_image_upload_when_upload_then_materials_path_with_byte_fidelity()
            throws Exception {
        givenWriteSucceedsReturning(String.valueOf(PNG_BYTES.length));

        String storedPath = performAsUser(upload("logo.png", PNG_BYTES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("logo.png"))
                .andExpect(jsonPath("$.data.size").value(PNG_BYTES.length))
                .andExpect(jsonPath("$.data.path").value(
                        org.hamcrest.Matchers.matchesPattern("materials/\\d+-logo\\.png")))
                .andReturn().getResponse().getContentAsString();

        // 写入命令＝ProjectMaterials.uploadCommand 正本（守卫壳 + cat + stat 回执），
        // 上传字节（含 NUL）经 stdin 原样灌入、不经 shell 参数面
        String path = com.fasterxml.jackson.databind.JsonNode.class.cast(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(storedPath)
                        .get("data").get("path")).asText();
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<WorkspaceHandle> handle = ArgumentCaptor.forClass(WorkspaceHandle.class);
        verify(environmentBackend).execWithStdin(handle.capture(), command.capture(), stdin.capture());
        assertThat(stdin.getValue()).containsExactly(PNG_BYTES);
        assertThat(command.getValue()).isEqualTo(ProjectMaterials.uploadCommand(path));
    }

    @Test
    void given_cjk_and_dangerous_filename_when_upload_then_sanitized_name_echoed()
            throws Exception {
        givenWriteSucceedsReturning("11");

        performAsUser(upload("/Users/me/Desktop/参考 图'.png", PNG_BYTES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("参考 图.png"))
                .andExpect(jsonPath("$.data.path").value(
                        org.hamcrest.Matchers.matchesPattern("materials/\\d+-参考 图\\.png")));
    }

    @Test
    void given_exactly_limit_bytes_when_upload_then_accepted() throws Exception {
        // 恰 10MB：上限含边界（> 才拒）——上限内如实落盘
        givenWriteSucceedsReturning(String.valueOf(ProjectMaterials.MAX_UPLOAD_BYTES));
        byte[] exactly = new byte[(int) ProjectMaterials.MAX_UPLOAD_BYTES];

        performAsUser(upload("big.png", exactly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(ProjectMaterials.MAX_UPLOAD_BYTES));
    }

    // ---------- 守卫：格式 / 大小 / 归档 / 项目（判定层拒绝，docker 零触达） ----------

    @Test
    void given_non_image_extension_when_upload_then_prj_044_without_workspace_touch()
            throws Exception {
        for (String filename : new String[] {"价目表.pdf", "截图.mp4", "无扩展名"}) {
            performAsUser(upload(filename, PNG_BYTES))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(4044))
                    .andExpect(jsonPath("$.message").value("只支持 png、jpg、webp、gif、svg 格式的图片"));
        }
        verify(environmentBackend, never()).execWithStdin(any(), anyString(), any());
    }

    @Test
    void given_over_limit_bytes_when_upload_then_prj_043_without_workspace_touch()
            throws Exception {
        byte[] over = new byte[(int) ProjectMaterials.MAX_UPLOAD_BYTES + 1];

        performAsUser(upload("huge.png", over))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4043))
                .andExpect(jsonPath("$.message").value("文件太大，单张图片不能超过 10MB"));

        verify(environmentBackend, never()).execWithStdin(any(), anyString(), any());
    }

    @Test
    void given_archived_project_when_upload_then_prj_013() throws Exception {
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.archive();
        projectRepository.save(project);

        performAsUser(upload("logo.png", PNG_BYTES))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4013))
                .andExpect(jsonPath("$.message").value("项目已归档（归档是单向终点）"));

        verify(environmentBackend, never()).execWithStdin(any(), anyString(), any());
    }

    @Test
    void given_unknown_project_when_upload_then_prj_001() throws Exception {
        mockMvc.perform(multipart("/api/projects/{id}/materials", "999999999").file(
                        new MockMultipartFile("file", "logo.png", "image/png", PNG_BYTES))
                        .cookie(new Cookie("aiplatform_session", SESSION_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001));
        verify(environmentBackend, never()).execWithStdin(any(), anyString(), any());
    }

    // ---------- 写入通道故障：如实暴露（WSP_002） ----------

    @Test
    void given_write_fails_when_upload_then_wsp_002() throws Exception {
        when(environmentBackend.execWithStdin(any(WorkspaceHandle.class), anyString(), any()))
                .thenReturn(new ExecResult("", "no space left on device", 1));

        performAsUser(upload("logo.png", PNG_BYTES))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void given_receipt_mismatch_when_upload_then_wsp_002() throws Exception {
        // stat 成功但回执与字节数不符：防御性如实暴露（成功路径不可达，防回归）
        givenWriteSucceedsReturning("999");

        performAsUser(upload("logo.png", PNG_BYTES))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(1002));
    }

    // ---------- 越权：既有隔离口径（会话闸，单账号 v1） ----------

    @Test
    void given_no_session_when_upload_then_401() throws Exception {
        mockMvc.perform(upload("logo.png", PNG_BYTES))
                .andExpect(status().isUnauthorized());
    }
}
