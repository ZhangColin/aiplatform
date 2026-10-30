package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.aggregate.PrdVersion;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * PRD 草稿基准（#334 改需求回路）：「工作区活草稿是否偏离最新已确认版本」的
 * 漂移判定单点——大改小改分流（停派/照旧）与修正 run 起跑基准共用。集测钉：
 * 全文比对（同文不漂移、异文漂移带基准全文）、最新版优先（v1→v2 后以 v2 为
 * 基准）、无确认版恒未漂移（legacy 不虚构确认制）、读失败 fail-closed 按已
 * 漂移返回（两侧消费都安全）。工作区读取 exec 桩＝PRD 读端点同形。
 */
@IntegrationTest
class PrdDraftBaselineTest {

    private static final long OWNER = 3897654321098765417L;

    private static final String PRD_V1 = "# PRD\n\n## 功能清单\n\n1. 首页\n";
    private static final String PRD_V2 = "# PRD\n\n## 功能清单\n\n1. 首页\n2. 下单页\n";
    private static final String PRD_DRAFT_SCOPE = "# PRD\n\n## 功能清单\n\n1. 首页\n2. 下单页\n3. 管理后台\n";

    @Autowired
    private PrdDraftBaseline baseline;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_no_confirmed_version_when_check_then_not_drifted() {
        // 无已确认版（访谈期/legacy）＝无版本制基准：恒未漂移（照旧读工作区正本）
        Long projectId = persistedPrdProject(PRD_DRAFT_SCOPE);

        assertThat(baseline.driftedFrom(projectId)).isEmpty();
    }

    @Test
    void given_draft_equals_confirmed_when_check_then_not_drifted() {
        // 同文不漂移（全文比对正本——锚随 savePrd 刷新不可用作判定）
        Long projectId = persistedPrdProject(PRD_V1);
        seedConfirmedVersion(projectId, 1, PRD_V1);

        assertThat(baseline.driftedFrom(projectId)).isEmpty();
    }

    @Test
    void given_scope_draft_differs_when_check_then_drifted_with_confirmed_baseline() {
        // 灵魂例：范围级新草稿在场（草稿≠已确认版）→ 漂移、基准＝已确认版全文
        // （小迭代基准＝当前已确认版本的判定源）
        Long projectId = persistedPrdProject(PRD_DRAFT_SCOPE);
        seedConfirmedVersion(projectId, 1, PRD_V1);

        Optional<PrdVersion> drifted = baseline.driftedFrom(projectId);

        assertThat(drifted).isPresent();
        assertThat(drifted.orElseThrow().getVersionNo()).isEqualTo(1);
        assertThat(drifted.orElseThrow().getContent()).isEqualTo(PRD_V1);
    }

    @Test
    void given_multiple_versions_when_check_then_latest_is_baseline() {
        // 最新已确认版优先（v1→v2 后草稿对 v2 比对、基准全文＝v2）
        Long projectId = persistedPrdProject(PRD_DRAFT_SCOPE);
        seedConfirmedVersion(projectId, 1, PRD_V1);
        seedConfirmedVersion(projectId, 2, PRD_V2);

        Optional<PrdVersion> drifted = baseline.driftedFrom(projectId);

        assertThat(drifted).isPresent();
        assertThat(drifted.orElseThrow().getVersionNo()).isEqualTo(2);
        assertThat(drifted.orElseThrow().getContent()).isEqualTo(PRD_V2);
    }

    @Test
    void given_draft_unreadable_when_check_then_drifted_fail_closed() {
        // 读失败 fail-closed：按已漂移返回已确认版（停派/以确认版为基准都安全）
        Long projectId = persistedPrdProject(PRD_V1);
        seedConfirmedVersion(projectId, 1, PRD_V1);
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "docker exec 环境故障", 125));

        assertThat(baseline.driftedFrom(projectId)).isPresent();
    }

    // ---------- 辅件 ----------

    /** 已产出 PRD 的项目＋工作区 PRD 读取桩（PRD 读端点同形：stat 首行＋cat 余文）。 */
    private Long persistedPrdProject(String prdMarkdown) {
        Project project = Project.create("草稿基准项目", null, null, 971810L, OWNER, null);
        project.markPrdProduced();
        Long projectId = projectRepository.save(project).getId();
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100000\n" + prdMarkdown, "", 0));
        return projectId;
    }

    private void seedConfirmedVersion(Long projectId, int versionNo, String content) {
        jdbcTemplate.update("""
                INSERT INTO prj_prd_versions (id, project_id, version_no, content, confirmed_at,
                    prd_produced_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, System.nanoTime(), projectId, versionNo, content);
    }
}
