package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.event.ApplicationEventPublisher;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.workspace.application.ConvergenceFace;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceConvergenceAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.GenerationSegmentRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 项目预览与触碰的项目域编排（#170；#196 起触碰/拉起委派收敛模块）：WSP_012 已生成
 * → 平台拉起 + 转 WSP_013 待期；未生成 → 原口径透传；touchProject 走收敛模块
 * TOUCH 面且尽力而为（异常吞掉）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectLifecycleAppServiceTouchTest {

    @Mock
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Mock
    private WorkspaceConvergenceAppService workspaceConvergenceAppService;

    @Mock
    private MainAgentAppService mainAgentAppService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectQueryAppService queryAppService;

    @Mock
    private EventsAppService eventsAppService;

    @Mock
    private ProjectKnowledgeAppService knowledgeAppService;

    @Mock
    private ProjectNamingAppService namingService;

    @Mock
    private ConversationHistoryAppService conversationHistory;

    @Mock
    private GenerationSegmentRepository generationSegments;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void given_generated_project_when_preview_not_serving_then_app_start_requested_and_starting_pending() {
        Project project = project(true);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(workspaceLifecycleAppService.exposePreview("900"))
                .thenThrow(new ApplicationException(WorkspaceMessage.PREVIEW_NOT_SERVING));

        assertThatThrownBy(() -> service().preview(100L))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                // 已生成项目的应用死而复起：平台拉起（8081 应用拉起是平台职责）+ 待期口径
                .isEqualTo(WorkspaceMessage.WORKSPACE_STARTING);
        verify(workspaceConvergenceAppService).requestAppStart(new WorkspaceId(900L));
    }

    @Test
    void given_never_generated_project_when_preview_not_serving_then_original_pending_code_kept() {
        Project project = project(false);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(workspaceLifecycleAppService.exposePreview("900"))
                .thenThrow(new ApplicationException(WorkspaceMessage.PREVIEW_NOT_SERVING));

        // 未生成口径原样：不拉起（恢复到未生成态，无静态兜底）
        assertThatThrownBy(() -> service().preview(100L))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                .isEqualTo(WorkspaceMessage.PREVIEW_NOT_SERVING);
        verify(workspaceConvergenceAppService, never())
                .requestAppStart(any());
    }

    @Test
    void given_workspace_starting_when_preview_then_pending_passthrough_without_app_start() {
        Project project = project(true);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(workspaceLifecycleAppService.exposePreview("900"))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_STARTING));

        // 置备/唤醒待期透传（唤醒已由触碰面触发，预览面不再重复拉起）
        assertThatThrownBy(() -> service().preview(100L))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                .isEqualTo(WorkspaceMessage.WORKSPACE_STARTING);
        verify(workspaceConvergenceAppService, never())
                .requestAppStart(any());
    }

    @Test
    void given_preview_serving_when_preview_then_url_and_sse_event() {
        Project project = project(true);
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project));
        when(workspaceLifecycleAppService.exposePreview("900"))
                .thenReturn(URI.create("http://900.localhost/"));

        ProjectPreviewResponse response = service().preview(100L);

        assertThat(response.url()).isEqualTo("http://900.localhost/");
        verify(eventsAppService).publishNotification(eq(ProjectEventTypes.PREVIEW_READY), any());
    }

    @Test
    void given_existing_project_when_touch_project_then_touch_face_converged_with_generated_flag() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(true)));

        service().touchProject(100L);

        // 收敛模块 TOUCH 面：拨 last-touch + 自愈探查，已生成连带应用拉起意图
        verify(workspaceConvergenceAppService).convergeAsync(
                new WorkspaceId(900L), ConvergenceFace.TOUCH, true);
    }

    @Test
    void given_unknown_project_when_touch_project_then_no_workspace_call_and_no_throw() {
        when(projectRepository.findById(100L)).thenReturn(Optional.empty());

        service().touchProject(100L);   // 项目不存在：静默返回（触发面尽力而为）

        verifyNoInteractions(workspaceConvergenceAppService);
    }

    @Test
    void given_workspace_touch_throws_when_touch_project_then_swallowed() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(false)));
        doThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND))
                .when(workspaceConvergenceAppService).convergeAsync(
                        new WorkspaceId(900L), ConvergenceFace.TOUCH, false);

        service().touchProject(100L);   // 触碰失败不阻断业务请求

        verify(workspaceConvergenceAppService).convergeAsync(
                new WorkspaceId(900L), ConvergenceFace.TOUCH, false);
    }

    // ---------- 测试数据 ----------

    private Project project(boolean generated) {
        Project project = Project.create("测试项目", null, 900L, 1L);
        if (generated) {
            project.markGenerated();
        }
        return project;
    }

    private ProjectLifecycleAppService service() {
        return new ProjectLifecycleAppService(workspaceLifecycleAppService,
                workspaceConvergenceAppService, mainAgentAppService, projectRepository,
                queryAppService, eventsAppService, knowledgeAppService, namingService,
                conversationHistory, generationSegments, transactionTemplate);
    }
}
