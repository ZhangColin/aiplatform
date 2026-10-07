package com.aieducenter.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.BinaryExecResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.RenderedFileResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignItem;
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignSpec;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.DesignPackages;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignItemRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignSpecRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设计资产包应用面（#297 下单冻结与取件，@IntegrationTest 隔离库＋工作区/
 * 渲染面 mock）：暂存序（规范落盘→帧衍生→清单→选件打包——落选稿结构性不入、
 * 无规范零写入、衍生失败 quietly）、统一部件容器分岔（设计＝纯选件／系统＋
 * 设计＝整树＋选件）、落名命令、冻结件取件三态（在/缺失 PRJ_051/环境故障）。
 * 选件矩阵与命令正本归 {@link com.aieducenter.aiplatform.business.project.domain.model.DesignPackagesTest}，
 * 容器保真（tar 真包成员集）归 {@code DesignPackageAppServiceLiveTest}。
 */
@IntegrationTest
class DesignPackageAppServiceTest {

    private static final long OWNER = 3897654321098765417L;
    private static final long WORKSPACE_ID = 971917L;

    /** 设计线目录清单（find 输出＝绝对路径行；含落选稿/侧车/历史冻结件三类不入者）。 */
    private static final String LISTING = """
            /workspace/design/logo.html
            /workspace/design/logo.png
            /workspace/design/logo.spec.json
            /workspace/design/hero.html
            /workspace/design/hero-v2.html
            /workspace/exports/design-package-000.tar.gz
            """;

    @Autowired
    private DesignPackageAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DesignItemRepository designItems;

    @Autowired
    private DesignSpecRepository designSpecs;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @MockitoBean
    private ProjectRenderAppService renderAppService;

    private long projectSeq = 9719200L;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_design_specs");
        jdbcTemplate.update("DELETE FROM prj_design_items");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    /** 建项目（终点类型随建随切）＋两件定稿（logo 平面类/hero 界面类）。 */
    private Project seedDesignProject(ProjectEndpointType endpointType) {
        Project project = projectRepository.save(
                Project.create("资产包测试", null, WORKSPACE_ID, OWNER));
        project.switchEndpoint(endpointType, null);
        project = projectRepository.save(project);
        LocalDateTime anchor = LocalDateTime.now();
        DesignItem logo = DesignItem.pending(project.getId(), 1, "品牌主 logo", anchor);
        logo.finalize("run-1", "/design/logo.png");
        designItems.save(logo);
        DesignItem hero = DesignItem.pending(project.getId(), 2, "首页 hero", anchor);
        hero.finalize("run-2", "/design/hero.html");
        designItems.save(hero);
        return project;
    }

    private void stubExec() {
        when(workspaceLifecycleAppService.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(1, WorkspaceExecCommand.class).command();
                    if (command.startsWith("find")) {
                        return new ExecResultResponse(LISTING, "", 0);
                    }
                    return new ExecResultResponse("", "", 0);
                });
        when(workspaceLifecycleAppService.execWithStdin(anyString(),
                any(WorkspaceExecCommand.class), any()))
                .thenReturn(new ExecResultResponse("123", "", 0));
    }

    @Test
    void given_design_project_with_spec_when_stage_then_members_and_commands_exact() {
        Project project = seedDesignProject(ProjectEndpointType.DESIGN);
        designSpecs.save(DesignSpec.tokensOf(project.getId(), "/design/hero.html",
                "run-2", Map.of("--primary", "#166534")));
        stubExec();
        when(renderAppService.renderHtmlPng(any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new RenderedFileResponse(
                        "exports/hero-1280x800.png", 1234L));

        DesignPackageAppService.StagedPackage staged = appService.stageForOrder(project.getId());

        // 暂存路径＝项目名暂存件（落库前无名可定格）
        assertThat(staged.stagingPath()).isEqualTo(DesignPackages.stagingPathOf(project.getId()));
        assertThat(staged.workspaceId()).isEqualTo(Long.toString(WORKSPACE_ID));
        // 规范文件落盘：正本直陈正文（#296 派发物化同一物）经 stdin 写面
        ArgumentCaptor<WorkspaceExecCommand> writeCaptor =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService).execWithStdin(eq(Long.toString(WORKSPACE_ID)),
                writeCaptor.capture(), any());
        assertThat(writeCaptor.getValue().command())
                .isEqualTo(ProjectFiles.stdinWriteCommand("DESIGN.md"));
        // 帧衍生：界面类定稿稿逐张渲（1280×800 与下载图同律）；平面类位图正身无衍生面
        verify(renderAppService).renderHtmlPng(project.getId(), "design/hero.html",
                "exports/hero-1280x800.png", 1280, 800);
        verify(renderAppService, never()).renderHtmlPng(any(), eq("design/logo.png"),
                anyString(), anyInt(), anyInt());
        // 打包命令正本：选件面成员清单＝DESIGN.md＋选定稿双形态＋帧衍生，
        // 落选稿（hero-v2）/规范侧车（logo.spec.json）/历史冻结件结构性不入
        ArgumentCaptor<WorkspaceExecCommand> tarCaptor =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, times(2))
                .exec(eq(Long.toString(WORKSPACE_ID)), tarCaptor.capture());
        assertThat(tarCaptor.getAllValues().get(1).command())
                .isEqualTo(DesignPackages.tarCommand(staged.stagingPath(),
                        List.of("DESIGN.md", "design/logo.html", "design/logo.png",
                                "design/hero.html", "exports/hero-1280x800.png"),
                        false));
    }

    @Test
    void given_no_spec_when_stage_then_no_rules_file_and_drafts_only() {
        Project project = seedDesignProject(ProjectEndpointType.DESIGN);
        stubExec();
        when(renderAppService.renderHtmlPng(any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("chromium 不可用"));

        DesignPackageAppService.StagedPackage staged = appService.stageForOrder(project.getId());

        // 无规范＝零写入一等断言（包内无 DESIGN.md 如实）；衍生渲染失败 quietly
        // （衍生可再生，稿回落 HTML 形态入包，不反噬下单）
        verify(workspaceLifecycleAppService, never()).execWithStdin(anyString(), any(), any());
        ArgumentCaptor<WorkspaceExecCommand> tarCaptor =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, times(2))
                .exec(eq(Long.toString(WORKSPACE_ID)), tarCaptor.capture());
        assertThat(tarCaptor.getAllValues().get(1).command())
                .isEqualTo(DesignPackages.tarCommand(staged.stagingPath(),
                        List.of("design/logo.html", "design/logo.png", "design/hero.html"),
                        false));
    }

    @Test
    void given_derivative_already_present_when_stage_then_no_redundant_render() {
        Project project = seedDesignProject(ProjectEndpointType.DESIGN);
        stubExec();
        String listingWithDerivative = LISTING + "/workspace/exports/hero-1280x800.png\n";
        when(workspaceLifecycleAppService.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenAnswer(invocation -> new ExecResultResponse(listingWithDerivative, "", 0));

        appService.stageForOrder(project.getId());

        // 在场衍生跳过渲染（确定性渲染同果，不重复花渲染）；成员照含衍生
        verify(renderAppService, never()).renderHtmlPng(any(), anyString(), anyString(),
                anyInt(), anyInt());
    }

    @Test
    void given_system_design_project_when_stage_then_unified_container_with_tree() {
        Project project = seedDesignProject(ProjectEndpointType.SYSTEM_DESIGN);
        stubExec();
        when(renderAppService.renderHtmlPng(any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("渲不阻断"));

        DesignPackageAppService.StagedPackage staged = appService.stageForOrder(project.getId());

        // 系统＋设计＝统一部件容器：find 剪枝整树选件＋设计部件显式追加；
        // DESIGN.md 随源码整树面入包（不重复列显式成员）
        ArgumentCaptor<WorkspaceExecCommand> tarCaptor =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService, times(2))
                .exec(eq(Long.toString(WORKSPACE_ID)), tarCaptor.capture());
        String command = tarCaptor.getAllValues().get(1).command();
        assertThat(command).isEqualTo(DesignPackages.tarCommand(staged.stagingPath(),
                List.of("design/logo.html", "design/logo.png", "design/hero.html"), true));
        assertThat(command).contains(" -o -path ./design -o -path ./exports ").contains("--null -T -");
    }

    @Test
    void given_tar_failure_when_stage_then_environment_error_zero_residue() {
        Project project = seedDesignProject(ProjectEndpointType.DESIGN);
        when(workspaceLifecycleAppService.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(1, WorkspaceExecCommand.class).command();
                    return command.startsWith("find")
                            ? new ExecResultResponse(LISTING, "", 0)
                            : new ExecResultResponse("", "tar: 写盘失败", 2);
                });

        assertThatThrownBy(() -> appService.stageForOrder(project.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
    }

    @Test
    void given_listing_docker_failure_when_stage_then_environment_error_not_silent_empty() {
        // 清单命令体自带 find 级容错（|| true），非 0 只能是 docker 层故障——如实炸，
        // 不静默空清单（空清单会冻出空包）
        Project project = seedDesignProject(ProjectEndpointType.DESIGN);
        when(workspaceLifecycleAppService.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "docker: 容器不在", 126));

        assertThatThrownBy(() -> appService.stageForOrder(project.getId()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
        verify(renderAppService, never()).renderHtmlPng(any(), anyString(), anyString(),
                anyInt(), anyInt());
    }

    @Test
    void given_staged_when_seal_then_atomic_rename_to_order_package() {
        DesignPackageAppService.StagedPackage staged =
                new DesignPackageAppService.StagedPackage("971917", "exports/.design-package-1.staging.tar.gz");
        when(workspaceLifecycleAppService.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "", 0));

        appService.sealForOrder(staged, 99L);

        ArgumentCaptor<WorkspaceExecCommand> captor =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycleAppService).exec(eq("971917"), captor.capture());
        assertThat(captor.getValue().command()).isEqualTo(
                DesignPackages.sealCommand(staged.stagingPath(), DesignPackages.packagePathOf(99L)));
    }

    @Test
    void given_frozen_package_when_read_then_bytes_or_absent_or_env_failure() {
        Project project = seedDesignProject(ProjectEndpointType.DESIGN);
        byte[] bytes = {0x1f, (byte) 0x8b, 0x08, 't', 'a', 'r'};
        when(workspaceLifecycleAppService.execBinary(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new BinaryExecResponse(bytes, "", 0));

        assertThat(appService.frozenPackage(project.getId(), 99L)).isEqualTo(bytes);

        // 冻结件缺失：不以空产物顶替（PRJ_051）；其余非 0＝环境故障如实
        when(workspaceLifecycleAppService.execBinary(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new BinaryExecResponse(new byte[0], "cat: 无此文件", 1));
        assertThatThrownBy(() -> appService.frozenPackage(project.getId(), 99L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.DESIGN_PACKAGE_NOT_FOUND.message());
        when(workspaceLifecycleAppService.execBinary(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new BinaryExecResponse(new byte[0], "docker: 容器不在", 126));
        assertThatThrownBy(() -> appService.frozenPackage(project.getId(), 99L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
    }

    @Test
    void given_missing_project_when_stage_or_read_then_not_found() {
        assertThatThrownBy(() -> appService.stageForOrder(990001L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.frozenPackage(990001L, 99L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }
}
