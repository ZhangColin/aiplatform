package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceReclaimAppService;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceReclaimAction;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.ReclaimScope;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.ThreeCleanCheck;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceReclaimActionRepository;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeReclaimResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectType;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 一键回收组合面（#490，服务缝——销毁内核 mock 收口〔归其自测与 live〕，聚焦
 * 组合编排）：项目级＝删除级联内核（{@code deleteAndReport}）＋四面报告＋留痕；
 * 工作区级＝在飞停止事实换算递入 base 回收面（照 {@code BackofficeWorkspaceAppService}
 * 组合先例）＋项目其余部分不动。留痕行真库断言（append-only）。
 */
@IntegrationTest
class BackofficeReclaimAppServiceTest {

    private static final long WS_ID = 740301L;
    private static final long WS_ID_ORPHAN = 740302L;

    private static final Operator OPERATOR = new Operator("900301", "运营·回收员");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 15, 0);

    /** 干净三清事实（探查成形、三面全清）。 */
    private static final ThreeCleanCheck CLEAN =
            ThreeCleanCheck.of(ContainerState.ABSENT, false, false);

    @Autowired
    private BackofficeReclaimAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CodingRunTrack codingRunTrack;

    @Autowired
    private WorkspaceReclaimActionRepository reclaimActionRepository;

    /** 销毁内核 mock（#485 面归 WorkspaceLifecycleAppServiceTest 与 live 类）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        reclaimActionRepository.deleteAll();
        if (persistedProjectId != null) {
            codingRunTrack.end(persistedProjectId);   // 在途登记随用例清（真 bean 单例）
        }
        projectRepository.findByWorkspaceId(WS_ID).ifPresent(projectRepository::delete);
        projectRepository.findByWorkspaceId(WS_ID_ORPHAN).ifPresent(projectRepository::delete);
    }

    private Long persistedProjectId;

    // ---------- 项目级：删除＋清残＋四面报告＋留痕 ----------

    @Test
    void given_generating_project_when_reclaim_project_then_runs_stopped_four_faces_clean() {
        // AC「生成中项目回收」＋「项目级回收」：在飞 run 先级联停止（承 #477）再回收，
        // 四面（注册表行/项目行/容器/卷）零残留判据在回执
        persistedProjectId = persistedProject(WS_ID);
        when(workspaceLifecycleAppService.destroyForReclaim(Long.toString(WS_ID)))
                .thenReturn(CLEAN);
        assertThat(codingRunTrack.begin(persistedProjectId)).isTrue();   // 模拟在飞 run

        BackofficeReclaimResponse response =
                appService.reclaimProject(persistedProjectId, OPERATOR, NOW);

        assertThat(codingRunTrack.stopRequested(persistedProjectId))
                .as("在飞 run 被级联停止（无僵尸任务）").isTrue();
        assertThat(response.scope()).isEqualTo("PROJECT");
        assertThat(response.projectId()).isEqualTo(persistedProjectId.toString());
        assertThat(response.workspaceId()).isEqualTo(Long.toString(WS_ID));
        assertThat(response.runsStopped()).isTrue();
        assertThat(response.container()).isEqualTo("CLEAN");
        assertThat(response.volume()).isEqualTo("CLEAN");
        assertThat(response.registryRow()).isEqualTo("CLEAN");
        assertThat(response.projectRow()).isEqualTo("CLEAN");
        assertThat(response.residueFree()).as("四面零残留").isTrue();
        assertThat(response.operatorId()).isEqualTo("900301");
        assertThat(response.reclaimedAt()).isEqualTo(NOW);
        assertThat(projectRepository.findById(persistedProjectId)).as("项目行已删").isEmpty();
        List<WorkspaceReclaimAction> traces = reclaimActionRepository.findAll();
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getScope()).isEqualTo(ReclaimScope.PROJECT);
        assertThat(traces.get(0).getProjectId()).isEqualTo(persistedProjectId);
        assertThat(traces.get(0).allClean()).isTrue();
    }

    @Test
    void given_volume_residue_when_reclaim_project_then_report_red_not_silent() {
        // 残留如实呈报（受理即删：销毁尽力后仍有残留——回执红、归收编通道，不虚报全清）
        persistedProjectId = persistedProject(WS_ID);
        when(workspaceLifecycleAppService.destroyForReclaim(anyString()))
                .thenReturn(ThreeCleanCheck.of(ContainerState.ABSENT, true, false));

        BackofficeReclaimResponse response =
                appService.reclaimProject(persistedProjectId, OPERATOR, NOW);

        assertThat(response.volume()).isEqualTo("RESIDUE");
        assertThat(response.projectRow()).isEqualTo("CLEAN");
        assertThat(response.residueFree()).isFalse();
        assertThat(reclaimActionRepository.findAll().get(0).isVolumeClean()).isFalse();
    }

    @Test
    void given_missing_project_when_reclaim_project_then_prj_001_no_trace() {
        assertThatThrownBy(() -> appService.reclaimProject(-1L, OPERATOR, NOW))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        assertThat(reclaimActionRepository.findAll()).isEmpty();
    }

    // ---------- 工作区级：单台三清，项目其余部分不动 ----------

    @Test
    void given_linked_project_when_reclaim_workspace_then_three_clean_project_untouched() {
        // AC「工作区级回收」：单台工作区三清，项目行与其余部分不动（回执 projectRow
        // ＝UNTOUCHED 如实分示）；所属项目有在飞 run 时先级联停止
        persistedProjectId = persistedProject(WS_ID);
        when(workspaceLifecycleAppService.destroyForReclaim(Long.toString(WS_ID)))
                .thenReturn(CLEAN);
        assertThat(codingRunTrack.begin(persistedProjectId)).isTrue();

        BackofficeReclaimResponse response =
                appService.reclaimWorkspace(Long.toString(WS_ID), OPERATOR, NOW);

        assertThat(response.scope()).isEqualTo("WORKSPACE");
        assertThat(response.projectId()).as("所属项目引用随回执").isEqualTo(
                persistedProjectId.toString());
        assertThat(response.workspaceId()).isEqualTo(Long.toString(WS_ID));
        assertThat(response.container()).isEqualTo("CLEAN");
        assertThat(response.volume()).isEqualTo("CLEAN");
        assertThat(response.registryRow()).isEqualTo("CLEAN");
        assertThat(response.projectRow()).as("项目面不动＝UNTOUCHED（非清非残留）")
                .isEqualTo("UNTOUCHED");
        assertThat(response.residueFree()).isTrue();
        assertThat(response.runsStopped()).isTrue();
        assertThat(codingRunTrack.stopRequested(persistedProjectId)).isTrue();
        assertThat(projectRepository.findById(persistedProjectId))
                .as("项目行不动（处置单点残留而不动整个项目）").isPresent();
        WorkspaceReclaimAction trace = reclaimActionRepository.findAll().get(0);
        assertThat(trace.getScope()).isEqualTo(ReclaimScope.WORKSPACE);
        assertThat(trace.getProjectId()).isEqualTo(persistedProjectId);
        assertThat(trace.getProjectRowClean()).isNull();
    }

    @Test
    void given_lost_workspace_without_project_when_reclaim_workspace_then_reclaimed() {
        // 在册失联裁决出口（#488→#490）：项目行已删（无所属）——工作区三清照收，
        // 回执 projectId 如实 null
        when(workspaceLifecycleAppService.destroyForReclaim(Long.toString(WS_ID_ORPHAN)))
                .thenReturn(CLEAN);

        BackofficeReclaimResponse response =
                appService.reclaimWorkspace(Long.toString(WS_ID_ORPHAN), OPERATOR, NOW);

        assertThat(response.projectId()).isNull();
        assertThat(response.runsStopped()).isFalse();
        assertThat(response.residueFree()).isTrue();
        assertThat(reclaimActionRepository.findAll().get(0).getProjectId()).isNull();
    }

    @Test
    void given_unknown_workspace_when_reclaim_workspace_then_wsp_001_no_trace() {
        when(workspaceLifecycleAppService.destroyForReclaim("404"))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));

        assertThatThrownBy(() -> appService.reclaimWorkspace("404", OPERATOR, NOW))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
        assertThat(reclaimActionRepository.findAll()).isEmpty();
    }

    // ---------- 夹具 ----------

    private Long persistedProject(long workspaceId) {
        // 带账户属主：删除级联尾部 SSE 通知要求归属路由键至少其一（真 EventsAppService）
        Project project = projectRepository.save(Project.create(
                "回收对象", ProjectType.WEBSITE, null, workspaceId, 740401L, null));
        return project.getId();
    }
}
