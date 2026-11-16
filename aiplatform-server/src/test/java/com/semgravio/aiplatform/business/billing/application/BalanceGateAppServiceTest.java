package com.semgravio.aiplatform.business.billing.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.BalanceGateAppService.Verdict;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 段边界余额门判定集成测试（#537 计费③，ADR-0047 段间硬门段中自由）：试用恒放行
 * （计费边界＝翻转时点，翻转前无门）、付费＋源池&gt;0 放行、付费＋余额&le;0 拦下
 * （携负额快照供提醒卡显负额）、付费＋池缺席的防御 anomaly 视同不放行。门只读
 * 不改——段中自由（结算扣成负照跑）由结算服务本态保证，本判定只在段边界被消费。
 */
@IntegrationTest
class BalanceGateAppServiceTest {

    private static final long PROJECT_ID = 930_021L;

    @Autowired
    private BalanceGateAppService balanceGate;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private PoolTopupAppService topupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    @Test
    void given_trial_project_when_assess_then_pass() {
        assertThat(balanceGate.assess(PROJECT_ID).pass()).isTrue();
    }

    @Test
    void given_paid_with_positive_balance_when_assess_then_pass() {
        topupService.topup(null, PROJECT_ID, 10_000, null, null);
        billingAppService.flipToPaid(PROJECT_ID, FlipGate.INTERCEPT);

        assertThat(balanceGate.assess(PROJECT_ID).pass()).isTrue();
    }

    @Test
    void given_paid_with_zero_balance_when_assess_then_blocked_with_snapshot() {
        topupService.topup(null, PROJECT_ID, 1, null, null);
        billingAppService.flipToPaid(PROJECT_ID, FlipGate.INTERCEPT);
        jdbcTemplate.update("UPDATE bil_pools SET balance = 0 WHERE project_id = ?", PROJECT_ID);

        Verdict verdict = balanceGate.assess(PROJECT_ID);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.blockedBalanceFen()).isZero();
    }

    @Test
    void given_paid_with_negative_balance_when_assess_then_blocked_with_negative_snapshot() {
        topupService.topup(null, PROJECT_ID, 1, null, null);
        billingAppService.flipToPaid(PROJECT_ID, FlipGate.INTERCEPT);
        jdbcTemplate.update("UPDATE bil_pools SET balance = -500 WHERE project_id = ?", PROJECT_ID);

        Verdict verdict = balanceGate.assess(PROJECT_ID);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.blockedBalanceFen()).isEqualTo(-500L);
    }

    @Test
    void given_paid_with_missing_pool_when_assess_then_blocked_defensively() {
        topupService.topup(null, PROJECT_ID, 1, null, null);
        billingAppService.flipToPaid(PROJECT_ID, FlipGate.INTERCEPT);
        jdbcTemplate.update("DELETE FROM bil_pools WHERE project_id = ?", PROJECT_ID);

        Verdict verdict = balanceGate.assess(PROJECT_ID);

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.blockedBalanceFen()).isNull();
    }
}
