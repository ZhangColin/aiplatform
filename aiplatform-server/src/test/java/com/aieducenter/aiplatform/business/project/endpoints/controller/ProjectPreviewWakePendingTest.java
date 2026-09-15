package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.Instant;

import jakarta.servlet.http.Cookie;

import com.cartisan.core.exception.ApplicationException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSession;
import com.aieducenter.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 唤醒待期的用户面 REST 口径（#170，@IntegrationTest 隔离库 + EnvironmentBackend
 * 假面）：置备/唤醒中的项目预览 = 503 数字业务码 1013（系统启动中）；已生成项目
 * 应用未起服 = 平台拉起后同 1013；未生成项目保持 1012 原口径。前端判错只认数字码
 * （#169 信封口径），本面验数字码上线的端到端信封。
 */
@IntegrationTest
@AutoConfigureMockMvc
class ProjectPreviewWakePendingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    /** 环境后端假面（seam）：探活结果按用例回放，唤醒自愈探查走 mock 默认值。 */
    @MockitoBean
    private EnvironmentBackend environmentBackend;

    @Autowired
    private BffSessionStore sessionStore;

    private static final String SESSION_ID = "preview-wake-test-session";

    private Long projectId;

    /** 全上下文过滤链要求真会话（BffSessionContextFilter 绑定 + ApiAuth 拦截）。 */
    private ResultActions performAsUser(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.cookie(new Cookie("aiplatform_session", SESSION_ID)));
    }

    @BeforeEach
    void seedSession() {
        sessionStore.put(SESSION_ID, new BffSession(
                1L, "唤醒待期测试", null, null, null, Instant.now().plusSeconds(3600)));
    }

    @AfterEach
    void cleanupSession() {
        sessionStore.remove(SESSION_ID);
    }

    @AfterEach
    void cleanup() {
        if (projectId != null) {
            projectRepository.findById(projectId).ifPresent(projectRepository::delete);
            workspaceRepository.findById(900L).ifPresent(workspaceRepository::delete);
        }
    }

    @Test
    void given_provisioning_workspace_when_preview_then_starting_pending_code_1013()
            throws Exception {
        seedProject(false);
        // 置备中记录（唤醒窗口的库内形态）
        workspaceRepository.save(Workspace.registerPending(
                WorkspaceId.of("900"),
                EnvKind.DEV));

        performAsUser(get("/api/projects/" + projectId + "/preview"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(1013))
                .andExpect(jsonPath("$.message").value("系统启动中"));
    }

    @Test
    void given_generated_project_when_app_not_serving_then_app_start_and_starting_code()
            throws Exception {
        seedProject(true);
        workspaceRepository.save(readyWorkspace());
        // 容器在（触碰面探查不动）；应用死（探活回放）——预览面轻路径的既有形态
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenThrow(new ApplicationException(WorkspaceMessage.PREVIEW_NOT_SERVING));

        performAsUser(get("/api/projects/" + projectId + "/preview"))
                // 已生成项目的应用死而复起：平台拉起（mock 后端）+ 待期「系统启动中」
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(1013));
    }

    @Test
    void given_never_generated_project_when_app_not_serving_then_original_1012_kept()
            throws Exception {
        seedProject(false);
        workspaceRepository.save(readyWorkspace());
        when(environmentBackend.containerState(any(WorkspaceHandle.class))).thenReturn(ContainerState.RUNNING);
        when(environmentBackend.exposePort(any(WorkspaceHandle.class), eq(8081)))
                .thenThrow(new ApplicationException(WorkspaceMessage.PREVIEW_NOT_SERVING));

        performAsUser(get("/api/projects/" + projectId + "/preview"))
                // 未生成口径原样：1012（未生成态，不拉起、无静态兜底）
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(1012));
    }

    // ---------- 测试数据 ----------

    private void seedProject(boolean generated) {
        Project project = Project.create("唤醒待期测试", null, 900L, 1L);
        if (generated) {
            project.markGenerated();
        }
        projectId = projectRepository.save(project).getId();
    }

    private Workspace readyWorkspace() {
        Workspace pending = Workspace.registerPending(
                WorkspaceId.of("900"),
                EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(
                WorkspaceHandle.dev(
                        WorkspaceId.of("900"),
                        "ws-900", "previewnet")));
        return pending;
    }
}
