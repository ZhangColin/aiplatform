package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceReclaimAction;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.ReclaimScope;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.ThreeCleanCheck;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceReclaimActionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 一键回收留痕面（#490，服务缝——销毁内核 {@link WorkspaceLifecycleAppService}
 * 以 mock 收口〔归其自测〕，聚焦「销毁事实 → 留痕行/回执」的组报告面）：
 * 工作区级回收＝destroy 同内核事实＋append-only 留痕（照 #489 wsp_sweep_actions
 * 先例：成功落痕、未成不留痕）；项目级＝组合方（business）完成级联后的事实落痕
 * （跨 BC 事实递入，照 WorkspaceScanFact 先例）。
 */
@IntegrationTest
class WorkspaceReclaimAppServiceTest {

    private static final long WS_ID = 740101L;
    private static final long PROJECT_ID = 740201L;

    private static final Operator OPERATOR = new Operator("900201", "运营·回收员");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 14, 0);

    /** 干净三清事实（探查成形、三面全清）。 */
    private static final ThreeCleanCheck CLEAN =
            ThreeCleanCheck.of(ContainerState.ABSENT, false, false);

    @Autowired
    private WorkspaceReclaimAppService appService;

    @Autowired
    private WorkspaceReclaimActionRepository actionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 销毁内核 mock（#485 面归 WorkspaceLifecycleAppServiceTest 与 live 类）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService lifecycle;

    @AfterEach
    void tearDown() {
        actionRepository.deleteAll();
    }

    @Test
    void given_clean_destroy_when_reclaim_workspace_then_facts_returned_and_trace_row() {
        when(lifecycle.destroyForReclaim(anyString())).thenReturn(CLEAN);

        ThreeCleanCheck check = appService.reclaimWorkspace(
                Long.toString(WS_ID), PROJECT_ID, true, OPERATOR, NOW);

        assertThat(check.allClean()).isTrue();
        List<WorkspaceReclaimAction> traces = actionRepository.findAll();
        assertThat(traces).hasSize(1);
        WorkspaceReclaimAction trace = traces.get(0);
        assertThat(trace.getScope()).isEqualTo(ReclaimScope.WORKSPACE);
        assertThat(trace.getWorkspaceId()).isEqualTo(WS_ID);
        assertThat(trace.getProjectId()).as("所属项目锚随留痕（审计面；不动项目面）")
                .isEqualTo(PROJECT_ID);
        assertThat(trace.getProjectRowClean()).as("不动项目面＝NULL（非清非残留）").isNull();
        assertThat(trace.isRunsStopped()).isTrue();
        assertThat(trace.isContainerClean()).isTrue();
        assertThat(trace.isVolumeClean()).isTrue();
        assertThat(trace.isRegistryRowClean()).isTrue();
        assertThat(trace.isProbed()).isTrue();
        assertThat(trace.getOperatorId()).isEqualTo("900201");
        assertThat(trace.getOperatorName()).isEqualTo("运营·回收员");
        assertThat(trace.getReclaimedAt()).isEqualTo(NOW);
        // 落库 code 钉死（BaseEnumConverter——与 V65 迁移注释同基：2=工作区级），
        // 防 ORDINAL 漂移错位（SQL 审计对账面）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scope FROM wsp_reclaim_actions", Integer.class)).isEqualTo(2);
    }

    @Test
    void given_destroy_failure_when_reclaim_workspace_then_no_trace_row() {
        // 未成动作不留痕（照 WorkspaceAction/sweep WSP_002 口径）：销毁失败上抛，
        // 留痕行不落（管理员重试是新一行动作）
        when(lifecycle.destroyForReclaim(anyString()))
                .thenThrow(new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED));

        assertThatThrownBy(() -> appService.reclaimWorkspace(
                Long.toString(WS_ID), null, false, OPERATOR, NOW))
                .isInstanceOf(ApplicationException.class);
        assertThat(actionRepository.findAll()).isEmpty();
    }

    @Test
    void given_missing_workspace_when_reclaim_workspace_then_wsp_001_and_no_trace() {
        // 寻址守卫在销毁内核（WSP_001 含畸形 id 口径）——回收面不另设守卫
        when(lifecycle.destroyForReclaim("404"))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));

        assertThatThrownBy(() -> appService.reclaimWorkspace("404", null, false, OPERATOR, NOW))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("工作区不存在");
        assertThat(actionRepository.findAll()).isEmpty();
    }

    @Test
    void given_project_cascade_facts_when_record_project_reclaim_then_trace_row() {
        // 项目级回收＝组合方（business）执行删除级联，事实递入落痕：四面＋scope=PROJECT
        appService.recordProjectReclaim(PROJECT_ID, WS_ID, true, CLEAN, true,
                OPERATOR, NOW);

        List<WorkspaceReclaimAction> traces = actionRepository.findAll();
        assertThat(traces).hasSize(1);
        WorkspaceReclaimAction trace = traces.get(0);
        assertThat(trace.getScope()).isEqualTo(ReclaimScope.PROJECT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT scope FROM wsp_reclaim_actions", Integer.class))
                .as("落库 code＝1（项目级），与 V65 注释同基").isEqualTo(1);
        assertThat(trace.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(trace.getWorkspaceId()).isEqualTo(WS_ID);
        assertThat(trace.getProjectRowClean()).isTrue();
        assertThat(trace.isRunsStopped()).isTrue();
        assertThat(trace.allClean()).as("四面全清判据一源在聚合").isTrue();
    }

    @Test
    void given_unprobed_destroy_when_record_project_reclaim_then_faces_not_clean() {
        // 销毁未全成（尽力级联失败/探查未成）：三面状态未知不冒充清——报告如实红
        appService.recordProjectReclaim(PROJECT_ID, WS_ID, false,
                ThreeCleanCheck.unprobed(), true, OPERATOR, NOW);

        WorkspaceReclaimAction trace = actionRepository.findAll().get(0);
        assertThat(trace.isProbed()).isFalse();
        assertThat(trace.isContainerClean()).isFalse();
        assertThat(trace.isVolumeClean()).isFalse();
        assertThat(trace.isRegistryRowClean()).isFalse();
        assertThat(trace.allClean()).isFalse();
    }
}
