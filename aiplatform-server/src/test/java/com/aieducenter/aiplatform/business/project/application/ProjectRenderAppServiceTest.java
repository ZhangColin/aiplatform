package com.aieducenter.aiplatform.business.project.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.web.ErrorCodePrefix;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.RenderedFileResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.WorkspaceRenders;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 位图出口渲染入口（#284）：项目锚定的平台侧可复用面——出稿/下载/导出触发点的
 * 后续接线点。exec 缝打桩（假执行回执），断言命令正本、退出码→错误码归一
 * （1 = 源不在 PRJ_021、渲染失败 PRJ_039 数字码 4039）与字节数回执解析；
 * 容器内真渲染保真归 WorkspaceRendersLiveTest。
 */
@IntegrationTest
class ProjectRenderAppServiceTest {

    @Autowired
    private ProjectRenderAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 渲染执行的容器缝：mock exec 断言命令与出口码口径。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_renderable_html_when_render_html_png_then_command_dispatched_and_size_reported() {
        long workspaceId = 8201L;
        Long projectId = persistedProject(workspaceId).getId();
        when(workspaceLifecycleAppService.exec(eq(Long.toString(workspaceId)), any()))
                .thenReturn(new ExecResultResponse("48213\n", "", 0));

        RenderedFileResponse rendered = appService.renderHtmlPng(projectId,
                "design/poster.html", "design/poster.png", 800, 600);

        // 命令正本逐字（内核契约）＋回执解析（stdout 首行即 PNG 字节数）
        verify(workspaceLifecycleAppService).exec(eq(Long.toString(workspaceId)),
                eq(new WorkspaceExecCommand(WorkspaceRenders.htmlToPngCommand(
                        "design/poster.html", "design/poster.png", 800, 600))));
        assertThat(rendered.path()).isEqualTo("design/poster.png");
        assertThat(rendered.sizeBytes()).isEqualTo(48213L);
    }

    @Test
    void given_vector_source_when_render_svg_png_then_bypass_command_dispatched() {
        long workspaceId = 8202L;
        Long projectId = persistedProject(workspaceId).getId();
        when(workspaceLifecycleAppService.exec(eq(Long.toString(workspaceId)), any()))
                .thenReturn(new ExecResultResponse("9218\n", "", 0));

        RenderedFileResponse rendered = appService.renderSvgPng(projectId,
                "design/logo.svg", "exports/logo.png");

        verify(workspaceLifecycleAppService).exec(eq(Long.toString(workspaceId)),
                eq(new WorkspaceExecCommand(WorkspaceRenders.svgToPngCommand(
                        "design/logo.svg", "exports/logo.png"))));
        assertThat(rendered.path()).isEqualTo("exports/logo.png");
        assertThat(rendered.sizeBytes()).isEqualTo(9218L);
    }

    @Test
    void given_missing_source_when_render_then_file_not_found() {
        Long projectId = persistedProject(8203L).getId();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "not found", 1));

        assertThatThrownBy(() -> appService.renderHtmlPng(projectId,
                "design/gone.html", "design/gone.png", 800, 600))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(ProjectMessage.FILE_NOT_FOUND);
    }

    @Test
    void given_renderer_failure_when_render_then_render_failed_with_numeric_code() {
        Long projectId = persistedProject(8204L).getId();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "HTML 渲染失败: 页面崩溃", 2));

        ApplicationException thrown = catchException(() -> appService.renderSvgPng(projectId,
                "design/logo.svg", "exports/logo.png"));
        // 渲染失败是技术失败（chromium/resvg 未成），数字业务码 4039＝域码 4×1000＋39
        // （错误细节按库惯例随异常参数携带、信封只认码）
        assertThat(thrown.getCodeMessage().code()).isEqualTo("PRJ_039");
        assertThat(ErrorCodePrefix.numericOf(thrown.getCodeMessage())).isEqualTo(4039);
    }

    @Test
    void given_non_viewable_path_when_render_then_path_invalid_before_any_exec() {
        Long projectId = persistedProject(8205L).getId();

        assertThatThrownBy(() -> appService.renderHtmlPng(projectId,
                "data/pg/base.sql", "design/x.png", 800, 600))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(ProjectMessage.FILE_PATH_INVALID);
        verify(workspaceLifecycleAppService, never()).exec(any(), any());
    }

    @Test
    void given_unknown_project_when_render_then_project_not_found() {
        assertThatThrownBy(() -> appService.renderSvgPng(999999999L,
                "design/logo.svg", "exports/logo.png"))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(ProjectMessage.PROJECT_NOT_FOUND);
    }

    @Test
    void given_malformed_receipt_when_render_then_environment_failure_defensively() {
        // exit 0 而 stdout 非字节数回执：渲染器契约破坏，防御性如实暴露（不静默）
        Long projectId = persistedProject(8206L).getId();
        when(workspaceLifecycleAppService.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));

        assertThatThrownBy(() -> appService.renderSvgPng(projectId,
                "design/logo.svg", "exports/logo.png"))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
    }

    private Project persistedProject(long workspaceId) {
        return projectRepository.save(Project
                .create("渲染项目" + workspaceId, ProjectType.WEBSITE, workspaceId, null));
    }

    private ApplicationException catchException(Runnable action) {
        try {
            action.run();
        } catch (ApplicationException e) {
            return e;
        }
        throw new AssertionError("应抛 ApplicationException");
    }
}
