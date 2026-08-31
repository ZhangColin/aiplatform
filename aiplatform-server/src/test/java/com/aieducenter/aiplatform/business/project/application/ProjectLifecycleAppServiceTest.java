package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.CartisanException;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目生命周期用例：建项目 = 工作区副作用 → 一事务 Project（占位名）→ SSE
 * workspace-created → 自动跑 BA；删除真删级联 + workspace-destroyed；归档/改名的
 * 聚合不变量。Docker 链路在 WorkspaceLifecycleAppServiceTest（mock 工作区服务，
 * 聚焦编排）。
 */
@SpringBootTest
class ProjectLifecycleAppServiceTest {

    @Autowired
    private ProjectLifecycleAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private BaInterviewAppService baInterviewAppService;

    @MockitoBean
    private PlatformNotificationAppService notificationAppService;

    /** 取名服务 mock（编排只验触发，取名本体见 ProjectNamingAppServiceTest）。 */
    @MockitoBean
    private ProjectNamingAppService namingService;

    /** 知识端口 mock（删除级联清理验证）。 */
    @MockitoBean
    private KnowledgePort knowledgePort;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_request_context_when_create_then_owner_account_id_filled() throws Exception {
        // 归属列：创建时填 RequestContext.userId（=accountId），v1 读路径不过滤
        stubWorkspace("9101", "aiplatform-dev-101");
        stubInterviewAccepted("run-1");

        ProjectCreatedResponse response = RequestContext.runFor(
                new RequestContext(null, null, null, null, 3897654321098765432L,
                        "归属测试", null, null),
                () -> appService.create(new CreateProjectCommand("做一个官网")));

        assertThat(projectRepository.findById(Long.parseLong(response.project().id())))
                .hasValueSatisfying(project -> assertThat(project.getOwnerAccountId())
                        .isEqualTo(3897654321098765432L));
    }

    @Test
    void given_valid_command_when_create_then_workspace_project_sse_and_auto_ba() {
        stubWorkspace("9100", "aiplatform-dev-100");
        stubInterviewAccepted("run-1");

        ProjectCreatedResponse response = appService.create(
                new CreateProjectCommand("做一个官网"));

        // 工作区副作用先行：dev 工作区
        verify(workspaceLifecycleAppService).create(new CreateWorkspaceCommand(EnvKind.DEV));

        // 一事务 Project：创建即落占位名（响应不等取名），类型服务端定
        Long projectId = Long.parseLong(response.project().id());
        assertThat(projectRepository.findById(projectId)).isPresent();
        assertThat(response.project().name()).isEqualTo(Project.PLACEHOLDER_NAME);
        assertThat(projectRepository.findById(projectId)).hasValueSatisfying(
                project -> assertThat(project.getName()).isEqualTo(Project.PLACEHOLDER_NAME));
        assertThat(response.project().type()).isEqualTo(ProjectType.WEBSITE); // 单模板服务端缺省
        assertThat(response.project().workspaceId()).isEqualTo("9100");
        assertThat(response.project().status()).isEqualTo(ProjectStatus.IN_PROGRESS);
        assertThat(response.runId()).isEqualTo("run-1"); // 自动 BA 运行标识随响应返回

        // SSE（副作用落定后）：workspace-created
        ArgumentCaptor<Map<String, Object>> created =
                ArgumentCaptor.forClass(Map.class);
        verify(notificationAppService).publish(eq(ProjectEventTypes.WORKSPACE_CREATED),
                created.capture());
        assertThat(created.getValue())
                .containsEntry("projectId", projectId.toString())
                .containsEntry("projectName", Project.PLACEHOLDER_NAME)
                .containsEntry("container", "aiplatform-dev-100")
                .containsEntry("projectType", "WEBSITE");

        // 异步取名：requirement 为取名输入，触发即返（不等结果）
        verify(namingService).nameAsync(projectId, "做一个官网");

        // 前缀段自动：BA 访谈开场（初始描述即首条对话输入）
        verify(baInterviewAppService).startInterview(projectId, "做一个官网");
    }

    @Test
    void given_blank_requirement_when_create_then_default_kickoff_prompt_and_no_naming() {
        stubWorkspace("9101", "aiplatform-dev-101");
        stubInterviewAccepted("run-2");

        ProjectCreatedResponse response = appService.create(
                new CreateProjectCommand(" "));

        assertThat(response.project().type()).isEqualTo(ProjectType.WEBSITE); // 服务端缺省
        // 空需求描述 → 缺省开场提示（对话展开起点）；取名守卫在命名服务内
        //（blank 不发起轻调用，见 ProjectNamingAppServiceTest）
        verify(baInterviewAppService).startInterview(
                Long.parseLong(response.project().id()), RolePreset.DEFAULT_KICKOFF_PROMPT);
    }

    @Test
    void given_auto_ba_failure_when_create_then_project_kept_and_run_id_absent() {
        stubWorkspace("9102", "aiplatform-dev-102");
        when(baInterviewAppService.startInterview(any(), any()))
                .thenThrow(new RuntimeException("对话智能体不可用"));

        ProjectCreatedResponse response = appService.create(
                new CreateProjectCommand(null));

        // BA 起跑失败不回滚建项目（项目已成立，runId 缺席表达起跑未成）
        assertThat(response.runId()).isNull();
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void given_unarchived_when_archive_then_archived_at_set_and_derived_archived() {
        Long projectId = persistedProject("9400");

        ProjectDetailResponse response = appService.archive(projectId);

        // 单向终点落定：archived_at 入库，派生状态归档优先
        assertThat(jdbcTemplate.queryForObject(
                "SELECT archived_at FROM prj_projects WHERE id = ?", java.sql.Timestamp.class,
                projectId)).isNotNull();
        assertThat(response.status()).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(response.statusName()).isEqualTo("已归档");
        assertThat(response.archived()).isTrue();
        // 归档不清工作区
        verify(workspaceLifecycleAppService, never()).destroy(anyString());
    }

    @Test
    void given_archived_when_archive_again_then_prj_013() {
        Long projectId = persistedProject("9401");
        appService.archive(projectId);

        // 聚合不变量抛 DomainException（CartisanException 统一映射 409）
        assertThatThrownBy(() -> appService.archive(projectId))
                .isInstanceOf(CartisanException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
    }

    @Test
    void given_missing_project_when_archive_then_prj_001() {
        assertThatThrownBy(() -> appService.archive(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_project_when_rename_then_name_persisted_and_detail_returned() {
        // 改名端点的用例面：名称后改（占位/生成名/已具名均可），详情同构返回
        Long projectId = persistedProject("9410");

        ProjectDetailResponse response = appService.rename(projectId, "品牌官网");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM prj_projects WHERE id = ?", String.class, projectId))
                .isEqualTo("品牌官网");
        assertThat(response.name()).isEqualTo("品牌官网");
        // 单账号 v1：改名不设状态限制、不发射 SSE（REST 响应即触达）
        verify(notificationAppService, never()).publish(any(), any());
    }

    @Test
    void given_archived_when_rename_then_succeeds() {
        // 归档项目照样可改名（改名非生命周期动作，无单向终点语义）
        Long projectId = persistedProject("9411");
        appService.archive(projectId);

        ProjectDetailResponse response = appService.rename(projectId, "归档后的名字");

        assertThat(response.name()).isEqualTo("归档后的名字");
        assertThat(response.archived()).isTrue();
    }

    @Test
    void given_blank_name_when_rename_then_prj_005() {
        // 空白拒绝在聚合（PRJ_005，与建项目同口径——长度上限归命令层校验）
        Long projectId = persistedProject("9412");

        assertThatThrownBy(() -> appService.rename(projectId, " "))
                .isInstanceOf(CartisanException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NAME_BLANK.message());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM prj_projects WHERE id = ?", String.class, projectId))
                .isEqualTo("删除对象"); // 拒绝后原名不动
    }

    @Test
    void given_missing_project_when_rename_then_prj_001() {
        assertThatThrownBy(() -> appService.rename(-1L, "任意名"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_workspace_when_source_package_then_bytes_returned() {
        Long projectId = persistedProject("9500");
        byte[] tarball = {0x1f, (byte) 0x8b, 0x08};
        when(workspaceLifecycleAppService.packSource("9500")).thenReturn(tarball);

        byte[] bytes = appService.sourcePackage(projectId);

        // 交付物字节流来自项目 dev 工作区（文件名/HTTP 头归 REST 层）
        assertThat(bytes).containsExactly(tarball);
    }

    @Test
    void given_missing_project_when_source_package_then_prj_001() {
        assertThatThrownBy(() -> appService.sourcePackage(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_project_when_delete_then_workspace_destroyed_rows_gone_sse_emitted() {
        Long projectId = persistedProject("9200");

        appService.delete(projectId);

        // 真删级联：工作区销毁（容器/网络/卷）+ prj_* 行删除 + knw_chunks 级联清理
        verify(workspaceLifecycleAppService).destroy("9200");
        verify(knowledgePort).purgeByProject(projectId.toString());
        verifyNoRows();
        verify(notificationAppService).publish(eq(ProjectEventTypes.WORKSPACE_DESTROYED),
                eq(Map.of("projectId", projectId.toString())));
    }

    @Test
    void given_workspace_destroy_failure_when_delete_then_rows_deleted_anyway() {
        Long projectId = persistedProject("9201");
        org.mockito.Mockito.doThrow(new RuntimeException("docker down"))
                .when(workspaceLifecycleAppService).destroy(anyString());

        appService.delete(projectId);

        // 物理销毁失败不阻断记录删除（真删级联优先，物理残留可重试）
        verifyNoRows();
        verify(notificationAppService).publish(eq(ProjectEventTypes.WORKSPACE_DESTROYED),
                eq(Map.of("projectId", projectId.toString())));
    }

    @Test
    void given_missing_project_when_delete_then_prj_001() {
        assertThatThrownBy(() -> appService.delete(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_project_when_preview_then_url_exposed_and_sse_preview_ready() throws Exception {
        Long projectId = persistedProject("9300");
        when(workspaceLifecycleAppService.exposePreview("9300"))
                .thenReturn(new URI("http://localhost:30080"));

        ProjectPreviewResponse response =
                appService.preview(projectId);

        // 端口真实暴露（docker publish 先行）→ 返回可访问 URL
        assertThat(response.url()).isEqualTo("http://localhost:30080");
        // SSE preview-ready（projectId + url）
        verify(notificationAppService).publish(eq(ProjectEventTypes.PREVIEW_READY),
                eq(Map.of("projectId", projectId.toString(), "url", "http://localhost:30080")));
    }

    // ---------- 测试数据 ----------

    /** BA 访谈编排桩：接受即回 runId（编排细节见 BaInterviewAppServiceTest）。 */
    private void stubInterviewAccepted(String runId) {
        when(baInterviewAppService.startInterview(any(), any()))
                .thenReturn(new BaInterviewAppService.InterviewRun(runId));
    }

    private void stubWorkspace(String workspaceId, String containerName) {
        when(workspaceLifecycleAppService.create(any())).thenReturn(new WorkspaceResponse(
                workspaceId, EnvKind.DEV, "开发环境", containerName, "net-x", 14096, 18081,
                ProvisioningStatus.READY, "就绪", null, List.of(), LocalDateTime.now()));
    }

    private Long persistedProject(String workspaceId) {
        Project project =
                projectRepository.save(Project
                        .create("删除对象", ProjectType.WEBSITE,
                                Long.parseLong(workspaceId), null));
        return project.getId();
    }

    private void verifyNoRows() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prj_projects", Long.class))
                .isZero();
    }
}
