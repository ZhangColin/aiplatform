package com.semgravio.aiplatform.business.billing.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.BalanceReminderAppService.BalanceReminderCard;
import com.semgravio.aiplatform.business.billing.application.BalanceReminderAppService.ResumableHold;
import com.semgravio.aiplatform.business.billing.domain.enums.BalanceHoldLane;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 余额提醒面用例集成测试（#537 计费③，ADR-0047 段间硬门）：留存（hold——新拦
 * 取代旧待决）、卡可回读面（pendingCard——余额随读点现取显负额）、充值回正放行
 * 认领（claimPassable——入账后过门认领落定已放行并返回恢复载荷；未回正保持待决）、
 * 恢复受理失败退场（discard）。bil_ 表与真实库交互；弱引用 projectId 直落。
 */
@IntegrationTest
class BalanceReminderAppServiceTest {

    private static final long PROJECT_ID = 930_031L;

    @Autowired
    private BalanceReminderAppService reminderService;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private PoolTopupAppService topupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_balance_holds");
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    @Test
    void given_no_hold_when_pending_card_then_empty() {
        assertThat(reminderService.pendingCard(PROJECT_ID)).isEmpty();
    }

    @Test
    void given_hold_when_pending_card_then_card_with_balance_snapshot() {
        BalanceReminderCard card = reminderService.hold(PROJECT_ID, BalanceHoldLane.OPINION,
                "把报表加个导出", List.of(Map.of("attachmentType", "image",
                        "name", "ref.png", "path", "materials/ref.png")), "run-b1");

        BalanceReminderCard pending = reminderService.pendingCard(PROJECT_ID).orElseThrow();

        assertThat(pending.holdId()).isEqualTo(card.holdId());
        assertThat(pending.lane()).isEqualTo("OPINION");
        assertThat(pending.prompt()).isEqualTo("把报表加个导出");
        assertThat(pending.runId()).isEqualTo("run-b1");
        assertThat(pending.sourcePoolBalance()).isNull(); // 试用无池——读面如实
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT lane, prompt, status, run_id FROM bil_balance_holds WHERE project_id = ?",
                PROJECT_ID);
        assertThat(row.get("lane")).isEqualTo(1);
        assertThat(row.get("prompt")).isEqualTo("把报表加个导出");
        assertThat(row.get("status")).isEqualTo(1);
        assertThat(row.get("run_id")).isEqualTo("run-b1");
    }

    @Test
    void given_second_hold_when_pending_then_first_superseded() {
        reminderService.hold(PROJECT_ID, BalanceHoldLane.OPINION, "旧输入", null, "run-old");
        BalanceReminderCard latest = reminderService.hold(PROJECT_ID, BalanceHoldLane.INQUIRY,
                "新输入", null, "run-new");

        BalanceReminderCard pending = reminderService.pendingCard(PROJECT_ID).orElseThrow();
        assertThat(pending.holdId()).isEqualTo(latest.holdId());
        assertThat(pending.lane()).isEqualTo("INQUIRY");
        Integer superseded = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bil_balance_holds WHERE project_id = ? AND status = 3",
                Integer.class, PROJECT_ID);
        assertThat(superseded).isEqualTo(1);
    }

    @Test
    void given_hold_and_balance_still_negative_when_claim_passable_then_empty_and_pending_kept() {
        toPaidWithBalance(-500);
        reminderService.hold(PROJECT_ID, BalanceHoldLane.OPINION, "输入", null, "run-x");

        assertThat(reminderService.claimPassable()).isEmpty();

        assertThat(reminderService.pendingCard(PROJECT_ID)).isPresent();
    }

    @Test
    void given_hold_and_balance_back_positive_when_claim_passable_then_claimed_with_payload() {
        toPaidWithBalance(-500);
        reminderService.hold(PROJECT_ID, BalanceHoldLane.OPINION,
                "把报表加个导出", null, "run-y");
        // 直改池值回正（不经 topup——topup 会发入账事件触发自动认领排空，
        // 本测只验 claimPassable 认领面语义）
        toBalance(10_000);

        List<ResumableHold> claimed = reminderService.claimPassable();

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).lane()).isEqualTo(BalanceHoldLane.OPINION);
        assertThat(claimed.get(0).prompt()).isEqualTo("把报表加个导出");
        assertThat(claimed.get(0).runId()).isEqualTo("run-y");
        assertThat(claimed.get(0).holdId()).isNotBlank();
        // 认领落定已放行（行保留＝审计面）——卡可回熄灭
        assertThat(reminderService.pendingCard(PROJECT_ID)).isEmpty();
        Integer resumed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bil_balance_holds WHERE project_id = ? AND status = 2",
                Integer.class, PROJECT_ID);
        assertThat(resumed).isEqualTo(1);
    }

    @Test
    void given_other_project_hold_when_claim_passable_then_other_kept_pending() {
        toPaidWithBalance(-500);
        toPaidWithBalance(PROJECT_ID + 1, -500);
        reminderService.hold(PROJECT_ID, BalanceHoldLane.OPINION, "输入A", null, "run-a");
        reminderService.hold(PROJECT_ID + 1, BalanceHoldLane.OPINION, "输入B", null, "run-b");

        // 只给本项目回正（直改池值——不经 topup，同认领面语义）：他项目待决保持
        toBalance(10_000);
        List<ResumableHold> claimed = reminderService.claimPassable();

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).prompt()).isEqualTo("输入A");
        assertThat(reminderService.pendingCard(PROJECT_ID + 1)).isPresent();
    }

    @Test
    void given_pending_when_discard_then_superseded() {
        BalanceReminderCard card = reminderService.hold(PROJECT_ID, BalanceHoldLane.OPINION,
                "输入", null, "run-z");

        reminderService.discard(card.holdId());

        assertThat(reminderService.pendingCard(PROJECT_ID)).isEmpty();
    }

    /** 付费项目＋指定余额（负余额软模型——测试直接置池值，不经结算）。 */
    private void toPaidWithBalance(long balanceFen) {
        toPaidWithBalance(PROJECT_ID, balanceFen);
    }

    private void toPaidWithBalance(long projectId, long balanceFen) {
        topupService.topup(null, projectId, 1, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT);
        jdbcTemplate.update("UPDATE bil_pools SET balance = ? WHERE project_id = ?",
                balanceFen, projectId);
    }

    /** 直改本项目池余额（不发入账事件——认领面单测的隔离位）。 */
    private void toBalance(long balanceFen) {
        jdbcTemplate.update("UPDATE bil_pools SET balance = ? WHERE project_id = ?",
                balanceFen, PROJECT_ID);
    }
}
