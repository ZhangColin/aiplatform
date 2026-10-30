package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeProductionHandoffResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.ProductionHandoff;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.Operator;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产承接域集成测试（#338 验收：只建一次〔唯一约束＋事务判定〕/两态推进
 * 留痕/信息更新整组替换留痕/需用户补充标志置位——应用服务 → 聚合 → 落库 →
 * 唯一索引全真，以库内真实行为为准；承接域纯 project BC 内闭环，无跨 BC mock）。
 * 开工判定触发链（订单转已支付＋同刻建承接）归 KickoffAppServiceTest（order
 * BC 侧跨 BC 同事务面）。
 */
@IntegrationTest
class ProductionHandoffAppServiceTest {

    /** 每次换新工作区号（一项目一工作区——测试直建项目不撞真实工作区）。 */
    private static final AtomicLong WORKSPACE_SEQ = new AtomicLong(973400L);
    private static final Operator OPERATOR = new Operator("700100", "运营·小刘");

    @Autowired
    private ProductionHandoffAppService appService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> projectIds = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        // 承接与留痕行经 FK 级联随项目清（prj_ 表不随 DELETE FROM ord_orders 级联——
        // 跨 BC 测试类 tearDown 亦须显式清本表，见 KickoffAppServiceTest）
        for (Long projectId : projectIds) {
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
    }

    // ---------- 只建一次：事务内判定＋唯一索引兜底 ----------

    @Test
    void given_kickoff_when_create_then_scheduled_row_with_system_creation_trail() {
        Long projectId = newProject();

        ProductionHandoff handoff = appService.createForKickoff(projectId, 900100L);

        // 库内事实：待安排生产起步、挂项目挂订单、承接字段全空（未录入不虚构）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT project_id, order_id, status, contact, next_step, progress_note,"
                        + " estimated_start_at FROM prj_production_handoffs WHERE id = ?",
                handoff.getId());
        assertThat(row.get("project_id")).isEqualTo(projectId);
        assertThat(row.get("order_id")).isEqualTo(900100L);
        assertThat(row.get("status")).isEqualTo(1);
        assertThat(row.get("contact")).isNull();
        assertThat(row.get("estimated_start_at")).isNull();
        // 创建留痕行：action=1、from=to=1（非迁移）、依据含双门禁公式、系统操作者
        Map<String, Object> trail = jdbcTemplate.queryForMap(
                "SELECT action, from_status, to_status, basis, operator_id, operator_name"
                        + " FROM prj_production_handoff_transitions WHERE handoff_id = ?",
                handoff.getId());
        assertThat(trail.get("action")).isEqualTo(1);
        assertThat(trail.get("from_status")).isEqualTo(trail.get("to_status"));
        assertThat((String) trail.get("basis")).contains("开工");
        assertThat(trail.get("operator_id")).isEqualTo("system");
    }

    @Test
    void given_existing_handoff_when_create_again_then_idempotent_single_row() {
        // 「只建一次」服务端判定腿：重复触发（双触发点竞态的后到者常态）幂等返回
        // 既有行——不重复建单、不异常
        Long projectId = newProject();
        ProductionHandoff first = appService.createForKickoff(projectId, 900100L);

        ProductionHandoff second = appService.createForKickoff(projectId, 900999L);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_production_handoffs WHERE project_id = ?",
                Integer.class, projectId)).isEqualTo(1);
    }

    // ---------- 两态后台人工推进：留痕 append-only ----------

    @Test
    void given_scheduled_when_advance_then_in_production_with_trail() {
        Long projectId = newProject();
        appService.createForKickoff(projectId, 900100L);

        BackofficeProductionHandoffResponse advanced =
                appService.advance(projectId, "排期落定，今日开工", OPERATOR);

        assertThat(advanced.status().getCode()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM prj_production_handoffs WHERE project_id = ?",
                Integer.class, projectId)).isEqualTo(2);
        // 留痕两行 append-only：创建在前、推进在后（from=1 to=2、依据/操作者随行落）
        List<Map<String, Object>> trail = jdbcTemplate.queryForList(
                "SELECT action, from_status, to_status, basis, operator_name"
                        + " FROM prj_production_handoff_transitions h"
                        + " JOIN prj_production_handoffs p ON p.id = h.handoff_id"
                        + " WHERE p.project_id = ? ORDER BY h.id",
                projectId);
        assertThat(trail).hasSize(2);
        assertThat(trail.get(1).get("action")).isEqualTo(2);
        assertThat(trail.get(1).get("from_status")).isEqualTo(1);
        assertThat(trail.get(1).get("to_status")).isEqualTo(2);
        assertThat(trail.get(1).get("basis")).isEqualTo("排期落定，今日开工");
        assertThat(trail.get(1).get("operator_name")).isEqualTo("运营·小刘");
    }

    @Test
    void given_in_production_when_advance_again_then_prj_056() {
        Long projectId = newProject();
        appService.createForKickoff(projectId, 900100L);
        appService.advance(projectId, "排期落定，今日开工", OPERATOR);

        // 两态单向、生产中是稳态：再推进无迁移边（PRJ_056），零多余留痕行。
        // 聚合守卫抛 DomainException（应用层透传）——同 ContractAppServiceTest 非法迁移口径
        assertThatThrownBy(() -> appService.advance(projectId, "再推一次", OPERATOR))
                .hasMessageContaining(ProjectMessage.PRODUCTION_ACTION_NOT_ALLOWED.message());
        assertThat(trailCount(projectId)).isEqualTo(2);
    }

    // ---------- 信息更新：整组替换＋非迁移留痕 ----------

    @Test
    void given_handoff_when_update_then_fields_replaced_with_update_trail() {
        Long projectId = newProject();
        appService.createForKickoff(projectId, 900100L);
        LocalDateTime estimated = LocalDateTime.of(2026, 10, 20, 9, 0);

        BackofficeProductionHandoffResponse updated = appService.update(projectId,
                "李经理（微信 ligoods）", "准备服务器环境与域名备案", "已完成技术方案拆解，本周进入开发",
                estimated, OPERATOR, "首次排期与联系人录入");

        assertThat(updated.contact()).isEqualTo("李经理（微信 ligoods）");
        assertThat(updated.nextStep()).isEqualTo("准备服务器环境与域名备案");
        assertThat(updated.estimatedStartAt()).isEqualTo(estimated);
        assertThat(updated.status().getCode()).isEqualTo(1); // 信息更新不迁移状态
        // 非迁移留痕行：action=3、from=to
        Map<String, Object> trail = jdbcTemplate.queryForMap(
                "SELECT action, from_status, to_status FROM prj_production_handoff_transitions h"
                        + " JOIN prj_production_handoffs p ON p.id = h.handoff_id"
                        + " WHERE p.project_id = ? ORDER BY h.id DESC LIMIT 1",
                projectId);
        assertThat(trail.get("action")).isEqualTo(3);
        assertThat(trail.get("from_status")).isEqualTo(trail.get("to_status"));

        // 整组替换：空值清空（预计开始时间清空＝回待安排呈现），留痕再追加
        appService.update(projectId, null, null, "进展顺利，主流程已通", null, OPERATOR, "进展更新");
        BackofficeProductionHandoffResponse cleared = appService.detailForBackoffice(projectId);
        assertThat(cleared.contact()).isNull();
        assertThat(cleared.estimatedStartAt()).isNull();
        assertThat(cleared.progressNote()).isEqualTo("进展顺利，主流程已通");
        assertThat(trailCount(projectId)).isEqualTo(3);
    }

    @Test
    void given_missing_basis_or_operator_or_overlong_when_actions_then_rejected() {
        Long projectId = newProject();
        appService.createForKickoff(projectId, 900100L);

        assertThatThrownBy(() -> appService.advance(projectId, "  ", OPERATOR))
                .hasMessageContaining(ProjectMessage.PRODUCTION_BASIS_REQUIRED.message());
        assertThatThrownBy(() -> appService.advance(projectId, "依据", null))
                .hasMessageContaining(ProjectMessage.PRODUCTION_OPERATOR_REQUIRED.message());
        assertThatThrownBy(() -> appService.update(projectId, "x".repeat(201), null, null,
                null, OPERATOR, "依据"))
                .hasMessageContaining(ProjectMessage.PRODUCTION_FIELD_TOO_LONG.message());
        assertThatThrownBy(() -> appService.update(projectId, null, null, null, null,
                OPERATOR, "x".repeat(1001)))
                .hasMessageContaining(ProjectMessage.PRODUCTION_FIELD_TOO_LONG.message());
        assertThat(trailCount(projectId)).isEqualTo(1); // 被拒动作零留痕行（创建行仍在）
    }

    // ---------- 读面：后台详情 404 / 跨 BC 事实形状 ----------

    @Test
    void given_no_handoff_when_detail_then_prj_055() {
        Long projectId = newProject();
        assertThatThrownBy(() -> appService.detailForBackoffice(projectId))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(ProjectMessage.PRODUCTION_HANDOFF_NOT_FOUND.message());
    }

    @Test
    void given_handoff_when_facts_then_cross_bc_shape_and_absent_null() {
        assertThat(appService.handoffFactsOf(newProject())).isNull(); // 未建承接＝缺席态

        Long projectId = newProject();
        appService.createForKickoff(projectId, 900100L);
        ProductionHandoffAppService.ProductionHandoffFacts scheduled = appService.handoffFactsOf(projectId);
        assertThat(scheduled.inProduction()).isFalse();
        assertThat(scheduled.status()).isEqualTo(1);

        appService.advance(projectId, "排期落定，今日开工", OPERATOR);
        ProductionHandoffAppService.ProductionHandoffFacts producing = appService.handoffFactsOf(projectId);
        assertThat(producing.inProduction()).isTrue();
        assertThat(producing.statusName()).isEqualTo("生产中");
    }

    // ---------- 需用户补充：项目级标志置位/清零 ----------

    @Test
    void given_project_when_set_needs_user_supplement_then_flag_roundtrip() {
        Long projectId = newProject();

        appService.setNeedsUserSupplement(projectId, true);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT needs_user_supplement FROM prj_projects WHERE id = ?",
                Boolean.class, projectId)).isTrue();

        appService.setNeedsUserSupplement(projectId, false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT needs_user_supplement FROM prj_projects WHERE id = ?",
                Boolean.class, projectId)).isFalse();
    }

    // ---------- 夹具（真链路：直接落库驱动到目标态） ----------

    private Long newProject() {
        Project project = projectRepository.save(Project.create("生产承接项目" + WORKSPACE_SEQ.get(),
                null, null, WORKSPACE_SEQ.incrementAndGet(), 1L, null));
        projectIds.add(project.getId());
        return project.getId();
    }

    private int trailCount(Long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_production_handoff_transitions h"
                        + " JOIN prj_production_handoffs p ON p.id = h.handoff_id"
                        + " WHERE p.project_id = ?",
                Integer.class, projectId);
    }
}
