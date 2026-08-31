package com.aieducenter.aiplatform.business.project.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.metering.application.MeteringAppService;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目读侧：详情与列表的派生状态（归档 > 进行中）+ 状态过滤（active/archived）；
 * usage = 总量 + 平台成本（币种分桶 + 未配价标注）+ 分模型 + 分角色；PRD 直读
 * 工作区（文件是事实源）。
 */
@SpringBootTest
class ProjectQueryAppServiceTest {

    @Autowired
    private ProjectQueryAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 计量查询缝：mock 用例本体（MeteringLocalAdapter 是 sink+query 双端口 bean，整替会断 sink 注入）。 */
    @MockitoBean
    private MeteringAppService meteringAppService;

    /** PRD 直读工作区的执行缝：mock exec 断言命令与出口码口径。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 详情 ----------

    @Test
    void given_any_project_when_detail_then_fields_and_derived_status() {
        Long projectId = persistedProject(8001L, "详情项目").getId();

        ProjectDetailResponse response = appService.detail(projectId);

        assertThat(response.id()).isEqualTo(projectId.toString());
        assertThat(response.name()).isEqualTo("详情项目");
        assertThat(response.type()).isEqualTo(ProjectType.WEBSITE);
        assertThat(response.workspaceId()).isEqualTo("8001");
        assertThat(response.status()).isEqualTo(ProjectStatus.IN_PROGRESS);
        assertThat(response.archived()).isFalse();
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull(); // 审计列随建置位（列表卡「更新于」事实源）
    }

    @Test
    void given_archived_project_when_detail_then_derived_archived() {
        Project archived = persistedProject(8002L, "归档项目");
        archived.archive();
        projectRepository.save(archived);

        ProjectDetailResponse response = appService.detail(archived.getId());

        assertThat(response.status()).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(response.statusName()).isEqualTo("已归档");
        assertThat(response.archived()).isTrue();
    }

    @Test
    void given_missing_project_when_detail_then_prj_001() {
        assertThatThrownBy(() -> appService.detail(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 列表：状态过滤 ----------

    @Test
    void given_no_filter_when_list_then_desc_order_with_derived_status() {
        projectRepository.save(Project
                .create("老项目", ProjectType.WEBSITE, 1L, null));
        projectRepository.save(Project
                .create("新项目", ProjectType.ECOMMERCE, 2L, null));

        List<ProjectResponse> list = appService.list(null);

        assertThat(list).extracting(ProjectResponse::name)
                .containsExactly("新项目", "老项目");
        assertThat(list).extracting(ProjectResponse::status)
                .containsExactly(ProjectStatus.IN_PROGRESS, ProjectStatus.IN_PROGRESS);
    }

    @Test
    void given_mixed_projects_when_list_active_then_only_unarchived() {
        Long active = persistedProject(8101L, "在办项目").getId();
        Project archived = persistedProject(8103L, "归档项目");
        archived.archive();
        projectRepository.save(archived);

        assertThat(appService.list(ProjectStatusFilter.ACTIVE)).extracting(ProjectResponse::id)
                .containsExactly(active.toString());
    }

    @Test
    void given_mixed_projects_when_list_archived_then_only_archived() {
        persistedProject(8104L, "在办项目");
        Project archived = persistedProject(8105L, "归档项目");
        archived.archive();
        projectRepository.save(archived);

        assertThat(appService.list(ProjectStatusFilter.ARCHIVED)).extracting(ProjectResponse::id)
                .containsExactly(archived.getId().toString());
        assertThat(appService.list(ProjectStatusFilter.ARCHIVED))
                .allMatch(project -> project.status() == ProjectStatus.ARCHIVED);
    }

    // 非法过滤 code 的 400 PRJ_014 口径在端点层（ProjectControllerTest 兜底测试）；
    // 枚举签名后应用层不再可能收到非法值。

    // ---------- usage ----------

    @Test
    void given_usage_events_when_usage_then_total_cost_unpriced_by_model_by_role() {
        Long projectId = persistedProject(8201L, "用量项目").getId();
        TokenUsage tokens = new TokenUsage(100, 200, 30, 0, 0);
        when(meteringAppService.bySubject(eq(projectId.toString()), any(), any()))
                .thenReturn(new UsageSummary(projectId.toString(), null, null, tokens,
                        Map.of(Currency.getInstance("USD"), new BigDecimal("0.003"),
                                Currency.getInstance("CNY"), new BigDecimal("1.5")),
                        List.of(new UsageSummary.UnpricedUsage("testprov", "m-none",
                                TokenKind.INPUT)),
                        List.of(new UsageSummary.ModelUsage("deepseek", "deepseek-v4-pro",
                                tokens)),
                        List.of(new UsageSummary.DimUsage("role", "BA", tokens),
                                new UsageSummary.DimUsage("role", "NAMING", tokens),
                                new UsageSummary.DimUsage("role", "DEV",
                                        new TokenUsage(1, 2, 0, 0, 0)))));

        ProjectUsageResponse response = appService.usage(projectId);

        assertThat(response.projectId()).isEqualTo(projectId.toString());
        assertThat(response.total()).isEqualTo(tokens); // 总量
        // 平台成本：Currency → 币种码字符串键，按键序稳定（CNY < USD）
        assertThat(response.cost().keySet()).containsExactly("CNY", "USD");
        assertThat(response.cost().get("USD")).isEqualByComparingTo(new BigDecimal("0.003"));
        assertThat(response.cost().get("CNY")).isEqualByComparingTo(new BigDecimal("1.5"));
        // 未配价标注：档位枚举 + 展示名随附
        assertThat(response.unpriced()).containsExactly(
                new ProjectUsageResponse.UnpricedUsage("testprov", "m-none",
                        TokenKind.INPUT, "输入"));
        assertThat(response.byModel()).hasSize(1); // 分模型
        assertThat(response.byModel().get(0).model()).isEqualTo("deepseek-v4-pro");
        // 分角色 = dims.role 维度（preset 带展示名，用途标记 roleLabel 为 null）
        assertThat(response.byRole()).extracting(ProjectUsageResponse.RoleUsage::role)
                .containsExactly("BA", "NAMING", "DEV");
        assertThat(response.byRole()).extracting(ProjectUsageResponse.RoleUsage::roleLabel)
                .containsExactly("需求分析师", null, null);
    }

    @Test
    void given_no_events_when_usage_then_all_zero_not_error() {
        Long projectId = persistedProject(8203L, "空用量项目").getId();
        when(meteringAppService.bySubject(eq(projectId.toString()), any(), any())).thenReturn(
                new UsageSummary(projectId.toString(), null, null,
                        new TokenUsage(0, 0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of()));

        ProjectUsageResponse response = appService.usage(projectId);

        assertThat(response.total().input()).isZero(); // 无事件全零而非错误（端口契约）
        assertThat(response.cost()).isEmpty();
        assertThat(response.unpriced()).isEmpty();
        assertThat(response.byModel()).isEmpty();
        assertThat(response.byRole()).isEmpty();
    }

    @Test
    void given_missing_project_when_usage_then_prj_001() {
        assertThatThrownBy(() -> appService.usage(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- PRD 读（直读工作区，文件是事实源） ----------

    @Test
    void given_workspace_prd_file_when_prd_then_content_and_file_mtime_returned() {
        Long projectId = persistedProject(8401L, "PRD 项目").getId();
        when(workspaceLifecycleAppService.exec(eq("8401"), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100000\n# 官网 PRD\n\n目标：三页官网。\n",
                        "", 0));

        PrdResponse response = appService.prd(projectId);

        // 一次 exec 取齐 mtime（stat 首行 epoch 秒）+ 正文（cat 余文），路径 = 产物单一事实
        assertThat(response.projectId()).isEqualTo(projectId.toString());
        assertThat(response.content()).isEqualTo("# 官网 PRD\n\n目标：三页官网。\n");
        assertThat(response.updatedAt()).isEqualTo(Instant.ofEpochSecond(1756100000));
        verify(workspaceLifecycleAppService).exec(eq("8401"), eq(new WorkspaceExecCommand(
                "test -f '/workspace/" + ProjectArtifacts.PRD + "' && stat -c %Y "
                        + "'/workspace/" + ProjectArtifacts.PRD + "' && cat "
                        + "'/workspace/" + ProjectArtifacts.PRD + "'")));
    }

    @Test
    void given_no_prd_file_when_prd_then_prj_015_not_produced() {
        Long projectId = persistedProject(8402L, "PRD 项目").getId();
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "stat: 无法取文件状态", 1));

        // 文件缺（test -f 失败，退出码 1）= 未产出口径：404 PRJ_015（前端区分「还没产出」）
        assertThatThrownBy(() -> appService.prd(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PRD_NOT_PRODUCED.message());
    }

    @Test
    void given_dead_container_when_prd_then_wsp_002_environment_fault() {
        Long projectId = persistedProject(8403L, "PRD 项目").getId();
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "docker: No such container", 125));

        // docker exec 自身失败（125/126）≠ 未产出：环境故障照抛（WSP_002）
        assertThatThrownBy(() -> appService.prd(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED.message());
    }

    @Test
    void given_missing_project_when_prd_then_prj_001() {
        assertThatThrownBy(() -> appService.prd(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_prd_status_bit_marked_when_saved_then_queryable_after_reload() {
        // 「PRD 已产出」状态位可查询（成果区长出判据）：置位落库往返不丢
        Project project = persistedProject(8404L, "状态位项目");
        project.markPrdProduced();
        projectRepository.save(project);

        assertThat(projectRepository.findById(project.getId()).orElseThrow()
                .getPrdProducedAt()).isNotNull();
        assertThat(projectRepository.findById(
                persistedProject(8405L, "未置位项目").getId()).orElseThrow()
                .getPrdProducedAt()).isNull(); // 未置位项目仍为 NULL
    }

    // ---------- 测试数据 ----------

    private Project persistedProject(long workspaceId, String name) {
        return projectRepository.save(Project
                .create(name, ProjectType.WEBSITE, workspaceId, null));
    }
}
