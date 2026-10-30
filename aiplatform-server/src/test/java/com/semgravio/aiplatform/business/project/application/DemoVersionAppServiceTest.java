package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.project.application.dto.response.DemoVersionSummaryResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * Demo 构建版本（#333「Demo 版本化与确认标记」，spec #320 实施序③）：每轮 Demo
 * 构建收口自动成版——首版随 generated_at 同刻原子落（生成收口）、迭代轮收口
 * 递增成版、每版锚定起跑时已确认 PRD 版本（在途不漂移）；「确认此版本，准备
 * 签约」＝既有版本上打标记（项目单指针、不产生新版本号、换选随移）。集测钉
 * 四件事：收口成版（v1 与 generated_at 同刻）/迭代递增成版＋旧版不动（PRD
 * 关联不变）/确认标记动作（打上/换选/不新增版本行）/守卫组。DB 可观察断言走
 * JdbcTemplate（只测外部行为，表行是本用例的可观察面）。
 */
@IntegrationTest
class DemoVersionAppServiceTest {

    private static final long OWNER = 3897654321098765403L;

    @Autowired
    private DemoVersionAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_demo_versions");
        jdbcTemplate.update("DELETE FROM prj_prd_versions");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 生成收口成版：首版与 generated_at 同刻落 ----------

    @Test
    void given_ungenerated_project_when_close_on_generation_then_v1_persisted_with_generated_at() {
        Long projectId = persistedProject();

        appService.closeOnGeneration(projectId, "r-demo-1", /* prdVersionNo= */ 1);

        // 首版成版：整数递增 v1＋起跑时 PRD 版本锚＋收口 run 锚
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT version_no, prd_version_no, run_id FROM prj_demo_versions"
                        + " WHERE project_id = ?",
                projectId);
        assertThat(row.get("version_no")).isEqualTo(1);
        assertThat(row.get("prd_version_no")).isEqualTo(1);
        assertThat(row.get("run_id")).isEqualTo("r-demo-1");
        // 原子同刻：generated_at 与 v1 同事务落位（要么同落要么同缺）
        assertThat(generatedAt(projectId)).isNotNull();
    }

    @Test
    void given_v1_persisted_when_close_on_generation_again_then_no_second_version() {
        // 补落位路径幂等：generated_at 已随首版同刻落，再调不产生第二版
        Long projectId = persistedProject();

        appService.closeOnGeneration(projectId, "r-demo-1", 1);
        appService.closeOnGeneration(projectId, "r-demo-2", 1);

        assertThat(versionCount(projectId)).isEqualTo(1);
    }

    // ---------- 迭代成版链：递增成版＋旧版 PRD 关联不变 ----------

    @Test
    void given_v1_when_close_on_iteration_then_v2_and_v1_anchor_untouched() {
        Long projectId = persistedProject();
        appService.closeOnGeneration(projectId, "r-demo-1", /* PRD v1 起跑 */ 1);

        // 迭代轮起跑时 PRD 已确认 v2（改需求回路后）——新成版锚 v2
        appService.closeOnIteration(projectId, "r-demo-2", 2);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version_no, prd_version_no FROM prj_demo_versions"
                        + " WHERE project_id = ? ORDER BY version_no",
                projectId);
        // 迭代成版链：v1→v2 各版 PRD 关联正确
        assertThat(rows).extracting(r -> r.get("version_no")).containsExactly(1, 2);
        assertThat(rows.get(0).get("prd_version_no")).isEqualTo(1);
        assertThat(rows.get(1).get("prd_version_no")).isEqualTo(2);
    }

    @Test
    void given_no_confirmed_prd_when_close_then_null_anchor() {
        // 无确认版项目（legacy 已生成/设计类起跑）成版：锚空——不虚构关联
        // （旧码先落 generated_at 的存量项目由迭代收口自愈成 v1）
        Long projectId = persistedProject();
        appService.closeOnGeneration(projectId, "r-demo-1", null);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT version_no, prd_version_no FROM prj_demo_versions WHERE project_id = ?",
                projectId);
        assertThat(row.get("version_no")).isEqualTo(1);
        assertThat(row.get("prd_version_no")).isNull();
    }

    // ---------- 确认标记：打上/换选/不产生新版本号 ----------

    @Test
    void given_versions_when_confirm_then_marker_on_project_row_without_new_version() {
        Long projectId = persistedProject();
        appService.closeOnGeneration(projectId, "r-demo-1", 1);
        appService.closeOnIteration(projectId, "r-demo-2", 1);

        DemoVersionSummaryResponse confirmed = appService.confirm(projectId, 1);

        // 标记落项目单指针（既有版本上打标记，不产生新版本号）
        assertThat(confirmed.versionNo()).isEqualTo(1);
        assertThat(confirmed.confirmedAt()).isNotNull();
        Map<String, Object> project = jdbcTemplate.queryForMap(
                "SELECT confirmed_demo_version_no, confirmed_demo_at FROM prj_projects WHERE id = ?",
                projectId);
        assertThat(project.get("confirmed_demo_version_no")).isEqualTo(1);
        assertThat((java.sql.Timestamp) project.get("confirmed_demo_at")).isNotNull();
        assertThat(versionCount(projectId)).isEqualTo(2);
    }

    @Test
    void given_confirmed_v1_when_confirm_v2_then_marker_moves() {
        Long projectId = persistedProject();
        appService.closeOnGeneration(projectId, "r-demo-1", 1);
        appService.closeOnIteration(projectId, "r-demo-2", 2);
        appService.confirm(projectId, 1);

        DemoVersionSummaryResponse moved = appService.confirm(projectId, 2);

        // 换确认版本＝旧标记移新标记：指针随移、版本行不增
        assertThat(moved.versionNo()).isEqualTo(2);
        Integer marker = jdbcTemplate.queryForObject(
                "SELECT confirmed_demo_version_no FROM prj_projects WHERE id = ?", Integer.class,
                projectId);
        assertThat(marker).isEqualTo(2);
        assertThat(versionCount(projectId)).isEqualTo(2);
        // 清单投影随指针：仅 v2 行带确认时间
        List<DemoVersionSummaryResponse> versions = appService.versions(projectId);
        assertThat(versions).extracting(DemoVersionSummaryResponse::versionNo)
                .containsExactly(2, 1);
        assertThat(versions.get(0).confirmedAt()).isNotNull();
        assertThat(versions.get(1).confirmedAt()).isNull();
    }

    // ---------- 守卫：版本不存在 / 已归档 / 项目不存在 ----------

    @Test
    void given_no_such_version_when_confirm_then_prj_054() {
        Long projectId = persistedProject();
        appService.closeOnGeneration(projectId, "r-demo-1", 1);

        assertThatThrownBy(() -> appService.confirm(projectId, 7))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.DEMO_VERSION_NOT_FOUND.message());
    }

    @Test
    void given_archived_project_when_confirm_then_prj_013_but_versions_readable() {
        Long projectId = persistedProject();
        appService.closeOnGeneration(projectId, "r-demo-1", 1);
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.archive();
        projectRepository.save(project);

        // 归档＝对话区关闭即全停：不再确认标记（历史版本照读，归档可追溯）
        assertThatThrownBy(() -> appService.confirm(projectId, 1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_ALREADY_ARCHIVED.message());
        assertThat(appService.versions(projectId)).hasSize(1);
    }

    @Test
    void given_missing_project_when_confirm_or_list_then_prj_001() {
        assertThatThrownBy(() -> appService.confirm(-1L, 1))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
        assertThatThrownBy(() -> appService.versions(-1L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PROJECT_NOT_FOUND.message());
    }

    // ---------- 辅件 ----------

    private Long persistedProject() {
        Project project = Project.create("Demo 版本项目", null, null, 971900L, OWNER, null);
        return projectRepository.save(project).getId();
    }

    private java.sql.Timestamp generatedAt(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT generated_at FROM prj_projects WHERE id = ?",
                java.sql.Timestamp.class, projectId);
    }

    private int versionCount(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_demo_versions WHERE project_id = ?", Integer.class,
                projectId);
    }
}
