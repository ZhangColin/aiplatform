package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdVersionResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.PrdVersionSummaryResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * PRD 版本表去门化（#462，ADR-0039）——旧「确认即成版/两级门/停驻待确认」整段
 * 退役后，版本行改由 Demo 类 run 起跑自动记行：快照当时工作区 PRD 全文为构建基准
 * （版本号＝构建基准序）＋来源 run_id。集测钉六件事：run 起跑记行（v1 全文＋run_id
 * 落库）/同文重复起跑幂等跳过（全文比对正本，锚不作数）/改稿后起跑记 v2 递增且 v1
 * 快照不可变/PRD 读不出如实跳过不反噬/并发撞唯一约束败者回读幂等/读面（清单倒序、
 * 版本详情快照收敛度投影、归档照读、守卫错误码）。agentscope 薄缝打桩＝PRD 读取
 * exec 桩（{@link ProjectQueryAppService#prd} 同缝）；DB 可观察断言走 JdbcTemplate
 * （只测外部行为，表行是本用例的可观察面）。
 */
@IntegrationTest
class PrdVersionAppServiceTest {

    private static final long OWNER = 3897654321098765403L;

    /** 工作区 PRD 桩正文（read 缝返回形：首行 mtime epoch 秒＋余文 markdown）。 */
    private static final String PRD_V1 = "# 巾上添花 PRD\n\n## 功能清单\n\n1. 首页——品牌展示\n";

    /** 新草稿正文（改需求回路的产物形态——下一起跑记为 v2 的全文源）。 */
    private static final String PRD_V2 = "# 巾上添花 PRD\n\n## 功能清单\n\n1. 首页——品牌展示\n2. 下单页——微信支付\n";

    /** 带阻碍级缺项的草稿（去门化后阻碍级不再拦截记行，解析层恒空——读面断言）。 */
    private static final String PRD_BLOCKING = """
            # 巾上添花 PRD

            ## 功能清单

            1. 首页——品牌展示

            ## 七、验收与待确认

            ### 阻碍核心流程

            1. 【主流程】下单后的发货方式未确认（自提还是快递）

            ### 次要未知

            2. 【验收】列表默认排序偏好未确认（假设：按创建时间倒序）
            """;

    /** 仅次要待定的草稿（回执带未解决事项、维度态投影的解析面）。 */
    private static final String PRD_MINOR = """
            # 巾上添花 PRD

            ## 功能清单

            1. 首页——品牌展示

            ## 七、验收与待确认

            ### 次要未知

            1. 【验收】列表默认排序偏好未确认（假设：按创建时间倒序）
            """;

    @Autowired
    private PrdVersionAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** PRD 直读工作区的执行缝（记行的全文来源＝PRD 读端点同缝）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- run 起跑自动记行：工作区 PRD 全文冻结为 v1 构建基准快照 ----------

    @Test
    void given_prd_when_demo_run_starts_then_v1_full_text_snapshot_with_run_id_persisted() {
        Long projectId = persistedPrdProject(PRD_V1);

        appService.recordOnDemoRunStart(projectId, "run-001");

        // 版本行：构建基准序 v1＋全文快照＋成行时点＋PRD 产出锚＋来源 run_id
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT version_no, content, confirmed_at, prd_produced_at, run_id"
                        + " FROM prj_prd_versions WHERE project_id = ?",
                projectId);
        assertThat(row.get("version_no")).isEqualTo(1);
        assertThat(row.get("content")).isEqualTo(PRD_V1);
        assertThat((java.sql.Timestamp) row.get("confirmed_at")).isNotNull();
        assertThat((java.sql.Timestamp) row.get("prd_produced_at")).isNotNull();
        assertThat(row.get("run_id")).isEqualTo("run-001");
    }

    // ---------- 同文重复起跑幂等跳过（全文比对正本——锚随 savePrd 刷新不作数） ----------

    @Test
    void given_recorded_v1_when_same_text_run_starts_again_then_no_new_version() {
        Long projectId = persistedPrdProject(PRD_V1);
        appService.recordOnDemoRunStart(projectId, "run-001");
        // 同文重存（锚刷新——markPrdProduced 再置位模拟 savePrd 重存事实）
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        projectRepository.save(project);

        appService.recordOnDemoRunStart(projectId, "run-002");

        // 全文未变＝同一构建基准：幂等跳过，版本行不增、首行 run_id 不覆盖
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_prd_versions WHERE project_id = ?", Integer.class, projectId);
        assertThat(count).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_id FROM prj_prd_versions WHERE project_id = ?",
                String.class, projectId)).isEqualTo("run-001");
    }

    // ---------- 构建基准递增＋快照不可变（只插不改，历史行不动） ----------

    @Test
    void given_recorded_v1_when_draft_changes_and_next_run_starts_then_v2_and_v1_untouched() {
        Long projectId = persistedPrdProject(PRD_V1);
        appService.recordOnDemoRunStart(projectId, "run-001");

        // 改稿：savePrd 再执行（工作区覆盖写＋产出锚刷新——桩换文、锚随写刷新）
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100099\n" + PRD_V2, "", 0));
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        projectRepository.save(project);

        appService.recordOnDemoRunStart(projectId, "run-002");

        // 两行并存、各自全文；v1 不受草稿改动影响（快照不可变的行为面）
        assertThat(appService.version(projectId, 1).content()).isEqualTo(PRD_V1);
        PrdVersionResponse v2 = appService.version(projectId, 2);
        assertThat(v2.content()).isEqualTo(PRD_V2);
        // v2 行的产出锚＝新草稿代际的 prd_produced_at（谱系随行，锚定 PRD 产出事实）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT prd_produced_at, run_id FROM prj_prd_versions"
                        + " WHERE project_id = ? AND version_no = 2",
                projectId);
        // #378：按存储精度对账（容差 1µs）——PG TIMESTAMP 微秒、写入侧对纳秒
        // 四舍五入、读取截断对齐会稳定差 0/1µs（实测 776325 vs 776326）；
        // Timestamp.equals 亚毫秒不等而 AssertJ 渲染只到毫秒（看似相同）
        assertThat(((java.sql.Timestamp) row.get("prd_produced_at")).toLocalDateTime())
                .isCloseTo(project.getPrdProducedAt(),
                        org.assertj.core.api.Assertions.within(1, ChronoUnit.MICROS));
        assertThat(row.get("run_id")).isEqualTo("run-002");
        // 清单倒序（最新在前）、轻量形不带全文
        assertThat(appService.versions(projectId))
                .extracting(PrdVersionSummaryResponse::versionNo)
                .containsExactly(2, 1);
    }

    // ---------- PRD 读不出如实跳过：记行是次生事实，不反噬 run ----------

    @Test
    void given_prd_unreadable_when_run_starts_then_skip_without_exception_and_no_row() {
        Long projectId = persistedPrdProject(PRD_V1);
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "stat: 无法取文件状态", 1));

        // 不抛＝run 起跑不被记行反噬；版本缺位由下轮起跑补位
        appService.recordOnDemoRunStart(projectId, "run-001");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_prd_versions WHERE project_id = ?", Integer.class, projectId);
        assertThat(count).isZero();
    }

    @Test
    void given_missing_project_when_run_starts_then_skip_without_exception() {
        // 记录已不存在（边角：删库与 run 起跑竞态）——记行静默跳过
        appService.recordOnDemoRunStart(-1L, "run-001");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_prd_versions", Integer.class)).isZero();
    }

    // ---------- 读面：清单倒序 / 版本详情快照收敛度投影 / 归档照读 / 守卫 ----------

    @Test
    void given_minor_pending_when_run_starts_then_version_readiness_minor_only() {
        Long projectId = persistedPrdProject(PRD_MINOR);

        appService.recordOnDemoRunStart(projectId, "run-001");

        // 去门化后阻碍级不再拦截记行；次要条目照常入回执投影
        PrdVersionResponse version = appService.version(projectId, 1);
        assertThat(version.readiness().blocking()).isEmpty();
        assertThat(version.readiness().minor()).hasSize(1);
        assertThat(version.readiness().minor().get(0).dimension()).isEqualTo("验收");
        // 维度态投影：验收维 ⚡（minor）、其余 ✓（ready）——「n/5 维度就绪」供呈现自数
        assertThat(version.readiness().dimensions()).extracting(d -> d.status())
                .containsExactly("ready", "ready", "ready", "ready", "minor");
    }

    @Test
    void given_blocking_text_when_run_starts_then_recorded_with_blocking_empty() {
        // 去门化语义：阻碍级缺项不再拦记行（#331 两级门退役）——行照落、
        // 阻碍级投影恒空（PrdReadiness 阻碍级产出已删，仅作界标）
        Long projectId = persistedPrdProject(PRD_BLOCKING);

        appService.recordOnDemoRunStart(projectId, "run-001");

        PrdVersionResponse version = appService.version(projectId, 1);
        assertThat(version.content()).isEqualTo(PRD_BLOCKING);
        // 阻碍级不再单独拦截/分组（两级门退役）：条目落入统一待确认池
        assertThat(version.readiness().blocking()).isEmpty();
        assertThat(version.readiness().minor()).hasSize(2);
    }

    @Test
    void given_legacy_prd_without_readiness_chapter_when_run_starts_then_recorded_fail_open() {
        // PRD_V1 无「验收与待确认」章（存量旧「待定项」形）——解析缺失 fail-open
        Long projectId = persistedPrdProject(PRD_V1);

        appService.recordOnDemoRunStart(projectId, "run-001");

        // fail-open 形：零条目＋全维度就绪
        assertThat(appService.version(projectId, 1).readiness().minor()).isEmpty();
        assertThat(appService.version(projectId, 1).readiness().dimensions())
                .allSatisfy(state -> assertThat(state.status()).isEqualTo("ready"));
    }

    @Test
    void given_recorded_version_when_draft_changes_then_detail_readiness_from_snapshot() {
        Long projectId = persistedPrdProject(PRD_MINOR);
        appService.recordOnDemoRunStart(projectId, "run-001");

        // 版本详情的收敛度来自快照全文（草稿后续改动不污染历史版本的读面）
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100099\n" + PRD_V2, "", 0));
        assertThat(appService.version(projectId, 1).readiness().minor()).hasSize(1);
        assertThat(appService.version(projectId, 1).readiness().blocking()).isEmpty();
    }

    @Test
    void given_archived_project_when_read_then_versions_readable() {
        Long projectId = persistedPrdProject(PRD_V1);
        appService.recordOnDemoRunStart(projectId, "run-001");
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.archive();
        projectRepository.save(project);

        // 归档＝对话区关闭即全停，但历史版本照读（可追溯）
        assertThat(appService.versions(projectId)).hasSize(1);
        assertThat(appService.version(projectId, 1).content()).isEqualTo(PRD_V1);
    }

    @Test
    void given_missing_project_when_read_then_prj_001() {
        assertThatThrownBy(() -> appService.versions(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.version(-1L, 1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    @Test
    void given_no_such_version_when_detail_then_prj_052_version_not_found() {
        Long projectId = persistedPrdProject(PRD_V1);

        assertThatThrownBy(() -> appService.version(projectId, 7))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PRD_VERSION_NOT_FOUND.message());
    }

    // ---------- 辅件 ----------

    /** 已产出 PRD 的项目＋工作区 PRD 读取桩（PRD 读端点同形：stat 首行＋cat 余文）。 */
    private Long persistedPrdProject(String prdMarkdown) {
        Project project = Project.create("PRD 版本项目", null, null, 971800L, OWNER, null);
        project.markPrdProduced();
        Long projectId = projectRepository.save(project).getId();
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100000\n" + prdMarkdown, "", 0));
        return projectId;
    }
}
