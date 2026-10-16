package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdVersionResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdVersionSummaryResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * PRD 确认版本（#330「确认即成版」，spec #320 实施序③）：确认动作把当时工作区
 * PRD 全文拷贝为不可变快照（＋确认时间＋项目内递增整数版本号）落平台库——
 * 切片计划落库同款版本表先例（锚定 PRD 产出事实）；工作区 PRD 仍是活草稿照旧
 * 覆盖写、草稿不编号。集测钉四件事：确认成版（v1 全文落库）/重复确认（无新
 * 草稿＝内容未变）不产生新版本/版本递增（新草稿确认成 v2）/草稿改动不污染
 * 已确认版本（只插不改，历史行不动）。agentscope 薄缝打桩＝PRD 读取 exec 桩
 * （设计过程应用服务集测同款）；DB 可观察断言走 JdbcTemplate（只测外部行为，
 * 表行是本用例的可观察面）。
 */
@IntegrationTest
class PrdVersionAppServiceTest {

    private static final long OWNER = 3897654321098765403L;

    /** 工作区 PRD 桩正文（read 缝返回形：首行 mtime epoch 秒＋余文 markdown）。 */
    private static final String PRD_V1 = "# 巾上添花 PRD\n\n## 功能清单\n\n1. 首页——品牌展示\n";

    /** 新草稿正文（改需求回路的产物形态——确认成 v2 的全文源）。 */
    private static final String PRD_V2 = "# 巾上添花 PRD\n\n## 功能清单\n\n1. 首页——品牌展示\n2. 下单页——微信支付\n";

    /** 带阻碍级缺项的草稿（#331 两级门的拦截面——确认被拦、版本行不落）。 */
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

    /** 仅次要待定的草稿（两段式的放行面——可确认、回执带未解决事项）。 */
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

    /** PRD 直读工作区的执行缝（确认的全文来源＝PRD 读端点同缝）。 */
    @MockitoBean
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 确认即成版：工作区 PRD 全文冻结为 v1 不可变快照 ----------

    @Test
    void given_draft_prd_when_confirm_then_v1_full_text_snapshot_persisted() {
        Long projectId = persistedPrdProject(PRD_V1);

        PrdVersionResponse version = appService.confirm(projectId);

        // 响应即挂点（#332 确认后自动派 Demo run 消费版本号）
        assertThat(version.projectId()).isEqualTo(projectId.toString());
        assertThat(version.versionNo()).isEqualTo(1);
        assertThat(version.content()).isEqualTo(PRD_V1);
        assertThat(version.confirmedAt()).isNotNull();
        // DB 事实：v1 行落库，全文快照＋确认时间＋PRD 产出锚（切片计划版本表同款）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT version_no, content, confirmed_at, prd_produced_at FROM prj_prd_versions"
                        + " WHERE project_id = ?",
                projectId);
        assertThat(row.get("version_no")).isEqualTo(1);
        assertThat(row.get("content")).isEqualTo(PRD_V1);
        assertThat((java.sql.Timestamp) row.get("confirmed_at")).isNotNull();
        assertThat((java.sql.Timestamp) row.get("prd_produced_at")).isNotNull();
    }

    // ---------- 重复确认（无新草稿＝内容未变）不产生新版本 ----------

    @Test
    void given_confirmed_prd_when_confirm_again_without_new_draft_then_no_new_version() {
        Long projectId = persistedPrdProject(PRD_V1);
        appService.confirm(projectId);

        PrdVersionResponse again = appService.confirm(projectId);

        // 幂等返回既有版（重复确认/重复点击天然防重），版本行不增
        assertThat(again.versionNo()).isEqualTo(1);
        assertThat(again.content()).isEqualTo(PRD_V1);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_prd_versions WHERE project_id = ?", Integer.class, projectId);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 版本递增＋草稿改动不污染已确认版本（只插不改） ----------

    @Test
    void given_confirmed_v1_when_draft_changes_then_confirm_makes_v2_and_v1_untouched() {
        Long projectId = persistedPrdProject(PRD_V1);
        appService.confirm(projectId);

        // 新草稿：savePrd 再执行（工作区覆盖写＋产出锚刷新——桩换文、锚随写刷新）
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100099\n" + PRD_V2, "", 0));
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        projectRepository.save(project);

        PrdVersionResponse v2 = appService.confirm(projectId);

        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.content()).isEqualTo(PRD_V2);
        // 版本组两行并存、各自全文；v1 不受草稿改动影响（快照不可变的行为面）
        assertThat(appService.version(projectId, 1).content()).isEqualTo(PRD_V1);
        assertThat(appService.version(projectId, 2).content()).isEqualTo(PRD_V2);
        // v2 行的产出锚＝新草稿代际的 prd_produced_at（谱系随行，锚定 PRD 产出事实）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT prd_produced_at FROM prj_prd_versions WHERE project_id = ? AND version_no = 2",
                projectId);
        assertThat((java.sql.Timestamp) row.get("prd_produced_at"))
                .isEqualTo(java.sql.Timestamp.valueOf(project.getPrdProducedAt()));
        // 清单倒序（最新在前）、轻量形不带全文
        assertThat(appService.versions(projectId))
                .extracting(PrdVersionSummaryResponse::versionNo)
                .containsExactly(2, 1);
    }

    // ---------- 两级门（#331）：阻碍级在场→拦截；仅次要/章缺失→放行 ----------

    @Test
    void given_blocking_pending_when_confirm_then_prj_053_and_no_version_row() {
        Long projectId = persistedPrdProject(PRD_BLOCKING);

        // 门用户不可绕过：阻碍核心流程的缺项须先在对话区补齐——确认拦截
        assertThatThrownBy(() -> appService.confirm(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PRD_CONFIRM_BLOCKING_PENDING.message());
        // 拦截即不成版：版本行零落库
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_prd_versions WHERE project_id = ?", Integer.class, projectId);
        assertThat(count).isZero();
    }

    @Test
    void given_minor_only_pending_when_confirm_then_confirmed_with_unresolved_minor() {
        Long projectId = persistedPrdProject(PRD_MINOR);

        PrdVersionResponse version = appService.confirm(projectId);

        // 仅次要未知→放行成版（两段式摘要层的确认动作本身）
        assertThat(version.versionNo()).isEqualTo(1);
        // 回执带未解决事项（摘要层数据面）：次要条目在场、阻碍级为空
        assertThat(version.readiness().blocking()).isEmpty();
        assertThat(version.readiness().minor()).hasSize(1);
        assertThat(version.readiness().minor().get(0).dimension()).isEqualTo("验收");
        assertThat(version.readiness().minor().get(0).text())
                .isEqualTo("列表默认排序偏好未确认（假设：按创建时间倒序）");
        // 维度态投影：验收维 ⚡（minor）、其余 ✓（ready）——「n/5 维度就绪」供呈现自数
        assertThat(version.readiness().dimensions()).extracting(d -> d.status())
                .containsExactly("ready", "ready", "ready", "ready", "minor");
    }

    @Test
    void given_blocking_resolved_draft_when_confirm_then_passes_gate() {
        // 门拦的是当前草稿：阻碍级补齐（重写为仅次要）后同项目确认放行——补稿回路
        Long projectId = persistedPrdProject(PRD_BLOCKING);
        assertThatThrownBy(() -> appService.confirm(projectId)).isInstanceOf(ApplicationException.class);

        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100099\n" + PRD_MINOR, "", 0));
        PrdVersionResponse version = appService.confirm(projectId);

        assertThat(version.versionNo()).isEqualTo(1);
        assertThat(version.readiness().blocking()).isEmpty();
    }

    @Test
    void given_legacy_prd_without_readiness_chapter_when_confirm_then_fallopen_confirmed() {
        // PRD_V1 无「验收与待确认」章（存量旧「待定项」形）——解析缺失 fail-open 放行
        Long projectId = persistedPrdProject(PRD_V1);

        PrdVersionResponse version = appService.confirm(projectId);

        assertThat(version.versionNo()).isEqualTo(1);
        // fail-open 形：零条目＋全维度就绪
        assertThat(version.readiness().blocking()).isEmpty();
        assertThat(version.readiness().minor()).isEmpty();
        assertThat(version.readiness().dimensions())
                .allSatisfy(state -> assertThat(state.status()).isEqualTo("ready"));
    }

    @Test
    void given_confirmed_version_when_detail_then_readiness_from_snapshot() {
        Long projectId = persistedPrdProject(PRD_MINOR);
        appService.confirm(projectId);

        // 版本详情的维度态来自快照全文（草稿后续改动不污染已确认版本的读面）
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100099\n" + PRD_V2, "", 0));
        assertThat(appService.version(projectId, 1).readiness().minor()).hasSize(1);
        assertThat(appService.version(projectId, 1).readiness().blocking()).isEmpty();
    }

    // ---------- 守卫：未产出 / 已归档 / 项目不存在 / 版本不存在 ----------

    @Test
    void given_no_prd_file_when_confirm_then_prj_015_not_produced() {
        Long projectId = persistedPrdProject(PRD_V1);
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "stat: 无法取文件状态", 1));

        assertThatThrownBy(() -> appService.confirm(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PRD_NOT_PRODUCED.message());
    }

    @Test
    void given_archived_project_when_confirm_then_prj_013_but_versions_readable() {
        Long projectId = persistedPrdProject(PRD_V1);
        appService.confirm(projectId);
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.archive();
        projectRepository.save(project);

        // 归档＝对话区关闭即全停：不再确认
        assertThatThrownBy(() -> appService.confirm(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        // 历史版本照读（归档可追溯）
        assertThat(appService.versions(projectId)).hasSize(1);
        assertThat(appService.version(projectId, 1).content()).isEqualTo(PRD_V1);
    }

    @Test
    void given_missing_project_when_confirm_or_read_then_prj_001() {
        assertThatThrownBy(() -> appService.confirm(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.versions(-1L))
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
        Project project = Project.create("PRD 确认项目", null, null, 971800L, OWNER, null);
        project.markPrdProduced();
        Long projectId = projectRepository.save(project).getId();
        when(workspaceLifecycleAppService.exec(any(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("1756100000\n" + prdMarkdown, "", 0));
        return projectId;
    }
}
