package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.Instant;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.BinaryExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图片 raw 直出端到端（#283 点看图片；#293 起扩设计稿 HTML 伺服——稿伺服通道，
 * @IntegrationTest 隔离库＋EnvironmentBackend 假面）：真过滤链（BFF 会话绑定 →
 * ApiAuth 闸）→ 真应用/查询服务 → aiplatform_test 真库。契约面：真实 content-type
 * ＋ 原始字节 inline 直出（非 ApiResponse 信封，先例＝源码包端点）、容器命令带
 * 三段守卫与 25 MiB 上限字面量、稿 HTML 的帧 CSP（禁脚本、放行样式与内嵌图）；
 * 守卫面：非伺服面扩展名 4038（含非 design 锚定 HTML——伺服面只认图片与设计稿
 * HTML）/ 超限 4022 / 不存在 4021 / 非可浏览 4020（判定层拒绝、docker 零触达）；
 * 越权面＝既有会话闸（无会话 401，单账号 v1 口径）。点看放行与拒收分界
 * （ADR-0027）：同一图片路径 raw 放行（字节原样、含 NUL 无碍）、files/content
 * 照旧 NUL 拒收（PRJ_023 语义保留给真二进制非图片件）。
 */
@IntegrationTest
@AutoConfigureMockMvc
class ProjectFileRawServeTest {

    /** PNG 魔数＋载荷（含 NUL——二进制字节原样直出的钉子）。 */
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

    /** docker 依赖收口（raw 正本＝容器内字节直读，形制同 #163 seam）。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    private static final String SESSION_ID = "file-raw-test-session";

    private Long projectId;
    private long workspaceSeq = 930100L;

    /** 全上下文过滤链要求真会话（BffSessionContextFilter 绑定 + ApiAuth 拦截）。 */
    private ResultActions performAsUser(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.cookie(new Cookie("aiplatform_session", SESSION_ID)));
    }

    @BeforeEach
    void seed() {
        sessionStore.put(SESSION_ID, new BffSession(
                1L, "图片直出测试", null, null, null, Instant.now().plusSeconds(3600)));
        Workspace ready = Workspace.registerPending(
                WorkspaceId.of(Long.toString(workspaceSeq)), EnvKind.DEV);
        ready.complete(WorkspaceProvision.of(WorkspaceHandle.dev(
                WorkspaceId.of(Long.toString(workspaceSeq)),
                "ws-" + workspaceSeq, "previewnet")));
        workspaceRepository.save(ready);
        projectId = projectRepository.save(
                Project.create("图片直出测试", null, workspaceSeq, 1L)).getId();
    }

    @AfterEach
    void cleanup() {
        sessionStore.remove(SESSION_ID);
        if (projectId != null) {
            projectRepository.findById(projectId).ifPresent(projectRepository::delete);
            workspaceRepository.findById(workspaceSeq).ifPresent(workspaceRepository::delete);
        }
    }

    private String rawUrl(String path) {
        return "/api/projects/" + projectId + "/files/raw?path=" + path;
    }

    // ---------- 契约：真实 content-type + 原始字节 inline 直出 ----------

    @Test
    void given_workspace_image_when_raw_then_real_content_type_and_raw_bytes_inline()
            throws Exception {
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(PNG_BYTES, "", 0));

        byte[] body = performAsUser(get(rawUrl("materials/ref.png")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"ref.png\""))
                .andReturn().getResponse().getContentAsByteArray();

        // 原始字节原样（含 NUL——非文本通道、非 JSON 信封）
        assertThat(body).containsExactly(PNG_BYTES);
        // 容器命令＝ProjectFiles.rawInlineCommand 正本：三段守卫 + 25 MiB 上限字面量
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(environmentBackend).execBinary(any(WorkspaceHandle.class), command.capture());
        assertThat(command.getValue())
                .isEqualTo(ProjectFiles.rawInlineCommand("materials/ref.png"))
                .contains(String.valueOf(ProjectFiles.MAX_RAW_IMAGE_BYTES));
    }

    @Test
    void given_svg_when_raw_then_svg_content_type_with_script_face_locked() throws Exception {
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes();
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(svg, "", 0));

        performAsUser(get(rawUrl("design/logo.svg")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml"))
                // SVG 同源直出的脚本面收口（直开 URL 时 CSP 禁脚本）＋ MIME 防混淆
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void given_design_draft_html_when_raw_then_html_content_type_frame_csp() throws Exception {
        // 稿伺服通道（#293 设计稿画布固定画幅帧取件）：design/ 锚定 HTML 直出
        // text/html（带 charset——中文稿直开不乱码）；CSP 禁脚本（default-src
        // 'none'——设计稿是帧不是网站）但放行内嵌样式与内嵌图（呈现面可达）
        byte[] html = "<!DOCTYPE html><html><body><h1>首页稿</h1></body></html>".getBytes();
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(html, "", 0));

        byte[] body = performAsUser(get(rawUrl("design/home-1.html")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=utf-8"))
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"home-1.html\""))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).containsExactly(html);
        // 与图片同命令正本（命令体与内容无关，判定归查询服务）
        verify(environmentBackend).execBinary(any(WorkspaceHandle.class),
                contains("design/home-1.html"));
    }

    @Test
    void given_non_design_html_when_raw_then_prj_038_without_workspace_touch() throws Exception {
        // 伺服面只认 design/ 锚定：应用源码 HTML 不经 raw 面（文本走 content 端点）
        performAsUser(get(rawUrl("src/index.html")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4038))
                .andExpect(jsonPath("$.message").value("该文件暂不支持在线查看"));
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    // ---------- 守卫：上界 / 不存在 / 非图片 / 非可浏览 ----------

    @Test
    void given_image_over_limit_when_raw_then_prj_022_envelope() throws Exception {
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(new byte[0], "", 2));

        // 超图片查看上限（容器侧 cat 前拦截 exit 2）：如实报错（非信封错误面走信封）
        performAsUser(get(rawUrl("materials/huge.png")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4022))
                .andExpect(jsonPath("$.message").value("文件太大，暂不支持在线查看"));
    }

    @Test
    void given_missing_image_when_raw_then_prj_021() throws Exception {
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(new byte[0], "", 1));

        performAsUser(get(rawUrl("materials/gone.png")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4021))
                .andExpect(jsonPath("$.message").value("文件不存在"));
    }

    @Test
    void given_non_image_extension_when_raw_then_prj_038_without_workspace_touch()
            throws Exception {
        performAsUser(get(rawUrl("src/app.ts")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4038))
                .andExpect(jsonPath("$.message").value("该文件暂不支持在线查看"));

        // 判定层拒绝：docker 边界零触达
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    @Test
    void given_secret_or_escaping_path_when_raw_then_prj_020_without_workspace_touch()
            throws Exception {
        for (String path : new String[] {".env", "../escape.png", "data/x.png"}) {
            performAsUser(get(rawUrl(path)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(4020))
                    .andExpect(jsonPath("$.message").value("该文件不在可浏览范围"));
        }
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    @Test
    void given_unknown_project_when_raw_then_prj_001() throws Exception {
        mockMvc.perform(get("/api/projects/999999999/files/raw?path=materials/ref.png")
                        .cookie(new Cookie("aiplatform_session", SESSION_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
        verify(environmentBackend, never()).execBinary(any(WorkspaceHandle.class), anyString());
    }

    // ---------- 越权：既有隔离口径（会话闸，单账号 v1） ----------

    @Test
    void given_no_session_when_raw_then_401() throws Exception {
        // 非授权访问被既有口径拦住：无会话 cookie → ApiAuth 闸 401（与文件区其余端点同闸）
        mockMvc.perform(get("/api/projects/" + projectId + "/files/raw?path=materials/ref.png"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 点看放行与拒收分界（ADR-0027：图片放行、NUL 拒收保留） ----------

    @Test
    void given_same_image_path_when_content_then_nul_still_rejected_but_raw_passes()
            throws Exception {
        // 文本通道照旧：图片（含 NUL 的二进制）走 files/content 仍 PRJ_023 如实拒收
        when(environmentBackend.exec(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new ExecResult("8\nab\0cd\n", "", 0));
        performAsUser(get("/api/projects/" + projectId + "/files/content")
                        .param("path", "materials/ref.png"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4023))
                .andExpect(jsonPath("$.message").value("该文件不是文本文件，暂不支持在线查看"));

        // 图片通道放行：同一路径 raw 直出（点看判定对图片放行的分界钉子）
        when(environmentBackend.execBinary(any(WorkspaceHandle.class), anyString()))
                .thenReturn(new BinaryExecResult(PNG_BYTES, "", 0));
        byte[] body = performAsUser(get(rawUrl("materials/ref.png")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).containsExactly(PNG_BYTES);
    }
}
