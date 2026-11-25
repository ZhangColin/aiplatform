package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeRetrieval;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectType;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台飞轮三个数统计端点（#643，阶段六-6；父票 #637，ADR-0058）在 {@code #152}
 * seam 上全绿：真签名闸＋真应用服务＋aiplatform_test 真库。
 *
 * <p>钉死面（票面验收）：</p>
 * <ul>
 * <li><b>修改轮数</b>＝提炼处理记录里版本号大于 1 的条数（v1/v2/v3 → 2）；</li>
 * <li><b>没做对轮数</b>＝其中 missed_count 大于 0 的记录条数——同一轮多条只算一轮；</li>
 * <li><b>命中三件</b>＝命中次数（含命中条目的留痕条数）、最近命中时间、是否有命中；
 * 成功 0 条与降级都算「无命中」；</li>
 * <li><b>分组汇总</b>＝有命中／无命中项目数与两组平均修改轮数、平均没做对轮数；</li>
 * <li><b>空数据</b>＝没有版本或没有提炼记录的项目返回 0，不报错；</li>
 * <li><b>项目名快照</b>＝有提炼记录取记录里的项目名快照，无记录回落当前项目名；</li>
 * <li><b>鉴权与只读</b>＝非签名 401；端点不写任何表。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeFlywheelSeamTest {

    private static final Instant HIT_1 = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant HIT_2 = Instant.parse("2026-08-05T00:00:00Z");
    private static final Instant ZERO_HIT = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant DEGRADED = Instant.parse("2026-08-03T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private KnowledgePort knowledgePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 提炼记录主键（跨用例唯一；TSID 形态无关，只是行柄）。 */
    private static final AtomicLong ID_SEQ = new AtomicLong(System.nanoTime());

    @BeforeEach
    void resetTables() {
        jdbcTemplate.update("DELETE FROM knw_experience_distillations");
        jdbcTemplate.update("DELETE FROM knw_retrievals");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 行内三件：修改轮数／没做对轮数／命中 ----------

    @Test
    void given_versions_and_retrievals_when_signed_stats_then_row_counts_and_snapshot_name_correct()
            throws Exception {
        Project project = project("项目甲（改名后）");
        String projectId = project.getId().toString();
        // 提炼记录完整：v1（首版）、v2（改了一轮没做对 0）、v3（同一轮两条没做对）
        plantDistillation(project.getId(), "项目甲", 1, 1, 0);
        plantDistillation(project.getId(), "项目甲", 2, 1, 0);
        plantDistillation(project.getId(), "项目甲", 3, 1, 2);
        // 留痕：两条有命中、一条成功 0 条、一条降级
        plantRetrieval(projectId, HIT_1, KnowledgeRetrieval.of(List.of(
                new KnowledgeHit("PRD", "宠物医院", "PRD·预约", "在线预约医生"))));
        plantRetrieval(projectId, ZERO_HIT, KnowledgeRetrieval.empty());
        plantRetrieval(projectId, DEGRADED, KnowledgeRetrieval.unavailable());
        plantRetrieval(projectId, HIT_2, KnowledgeRetrieval.of(List.of(
                new KnowledgeHit("MODULE", "物流系统", "导出模块", "导出订单"),
                new KnowledgeHit("RULE", "电商系统", "退款规则", "七天无理由"))));

        signedGet("/api/backoffice/flywheel-stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].projectName").value("项目甲"))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].modificationRounds").value(2))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].missedRounds").value(1))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].hitCount").value(2))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].latestHitAt").value("2026-08-05T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].hasHit").value(true));
    }

    @Test
    void given_no_records_when_signed_stats_then_zero_row_and_current_name() throws Exception {
        Project project = project("空项目");
        String projectId = project.getId().toString();

        signedGet("/api/backoffice/flywheel-stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].projectName").value("空项目"))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].modificationRounds").value(0))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].missedRounds").value(0))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].hitCount").value(0))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].latestHitAt").value(hasItem(nullValue())))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].hasHit").value(false));
    }

    @Test
    void given_only_zero_hit_and_degraded_when_signed_stats_then_has_hit_false() throws Exception {
        Project project = project("无命中项目");
        String projectId = project.getId().toString();
        plantRetrieval(projectId, ZERO_HIT, KnowledgeRetrieval.empty());
        plantRetrieval(projectId, DEGRADED, KnowledgeRetrieval.unavailable());

        signedGet("/api/backoffice/flywheel-stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].hitCount").value(0))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].latestHitAt").value(hasItem(nullValue())))
                .andExpect(jsonPath("$.data.items[?(@.projectId == '" + projectId
                        + "')].hasHit").value(false));
    }

    // ---------- 分组汇总 ----------

    @Test
    void given_mixed_projects_when_signed_stats_then_summary_groups_and_averages() throws Exception {
        Project withHit = project("有命中被其他项目");
        plantDistillation(withHit.getId(), "有命中被其他项目", 1, 1, 0);
        plantDistillation(withHit.getId(), "有命中被其他项目", 2, 1, 0);
        plantDistillation(withHit.getId(), "有命中被其他项目", 3, 1, 2);
        plantRetrieval(withHit.getId().toString(), HIT_1, KnowledgeRetrieval.of(List.of(
                new KnowledgeHit("PRD", "来源", "标题", "片段"))));

        Project noHitWithRounds = project("无命中有修改");
        plantDistillation(noHitWithRounds.getId(), "无命中有修改", 1, 2, 0);
        plantDistillation(noHitWithRounds.getId(), "无命中有修改", 2, 1, 1);
        plantRetrieval(noHitWithRounds.getId().toString(), DEGRADED,
                KnowledgeRetrieval.unavailable());

        Project noHitNoRounds = project("无命中无修改");

        signedGet("/api/backoffice/flywheel-stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.withHitProjectCount").value(1))
                .andExpect(jsonPath("$.data.summary.withoutHitProjectCount").value(2))
                .andExpect(jsonPath("$.data.summary.withHitAverageModificationRounds").value(2.0))
                .andExpect(jsonPath("$.data.summary.withHitAverageMissedRounds").value(1.0))
                .andExpect(jsonPath("$.data.summary.withoutHitAverageModificationRounds")
                        .value(0.5))
                .andExpect(jsonPath("$.data.summary.withoutHitAverageMissedRounds").value(0.5))
                .andExpect(jsonPath("$.data.total").value("3"));
    }

    @Test
    void given_no_projects_at_all_when_signed_stats_then_zero_summary_not_error() throws Exception {
        signedGet("/api/backoffice/flywheel-stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value("0"))
                .andExpect(jsonPath("$.data.summary.withHitProjectCount").value(0))
                .andExpect(jsonPath("$.data.summary.withoutHitProjectCount").value(0))
                .andExpect(jsonPath("$.data.summary.withHitAverageModificationRounds").value(0.0))
                .andExpect(jsonPath("$.data.summary.withoutHitAverageMissedRounds").value(0.0));
    }

    // ---------- 分页 ----------

    @Test
    void given_many_projects_when_signed_stats_paged_then_rows_paginate_summary_stays_global()
            throws Exception {
        Project first = project("分页项目一");
        Project second = project("分页项目二");
        Project third = project("分页项目三");
        // 项目行序＝项目 id 倒序（新项目在前）
        signedGet("/api/backoffice/flywheel-stats?size=2&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.total").value("3"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.items[0].projectId").value(third.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].projectId").value(second.getId().toString()))
                .andExpect(jsonPath("$.data.summary.withoutHitProjectCount").value(3));
        signedGet("/api/backoffice/flywheel-stats?size=2&page=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].projectId").value(first.getId().toString()));
        signedGet("/api/backoffice/flywheel-stats?size=2&page=9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value("3"));
    }

    // ---------- 鉴权与只读 ----------

    @Test
    void given_no_signature_when_signed_stats_then_401() throws Exception {
        mockMvc.perform(get("/api/backoffice/flywheel-stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    @Test
    void given_stats_read_when_signed_then_no_table_written() throws Exception {
        Project project = project("只读项目");
        plantDistillation(project.getId(), "只读项目", 1, 2, 0);
        plantDistillation(project.getId(), "只读项目", 2, 1, 1);
        plantRetrieval(project.getId().toString(), HIT_1, KnowledgeRetrieval.of(List.of(
                new KnowledgeHit("PRD", "来源", "标题", "片段"))));
        long distillationsBefore = count("knw_experience_distillations");
        long retrievalsBefore = count("knw_retrievals");
        long projectsBefore = count("prj_projects");

        signedGet("/api/backoffice/flywheel-stats").andExpect(status().isOk());

        assertThat(count("knw_experience_distillations")).isEqualTo(distillationsBefore);
        assertThat(count("knw_retrievals")).isEqualTo(retrievalsBefore);
        assertThat(count("prj_projects")).isEqualTo(projectsBefore);
    }

    // ---------- 夹具 ----------

    private Project project(String name) {
        return projectRepository.save(Project.create(name, ProjectType.WEBSITE,
                ID_SEQ.incrementAndGet(), null, null));
    }

    private void plantDistillation(long projectId, String projectName, int versionNo,
            int status, int missedCount) {
        jdbcTemplate.update("""
                INSERT INTO knw_experience_distillations
                    (id, project_id, project_name, version_no, commit_hash, status,
                     opinion_count, missed_count, candidate_count, batch_id)
                VALUES (?, ?, ?, ?, NULL, ?, 0, ?, 0, ?)
                """, ID_SEQ.incrementAndGet(), projectId, projectName, versionNo, status,
                missedCount, "batch-" + versionNo);
    }

    /** 经端口写一条留痕并锚定检索时间（同 #639 seam 先例）。 */
    private void plantRetrieval(String projectId, Instant recordedAt,
            KnowledgeRetrieval retrieval) {
        knowledgePort.recordRetrieval(projectId, retrieval);
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM knw_retrievals WHERE project_id = ?", Long.class, projectId);
        jdbcTemplate.update("UPDATE knw_retrievals SET created_at = ? WHERE id = ?",
                Timestamp.from(recordedAt), id);
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private ResultActions signedGet(String pathWithQuery) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null));
    }
}
