package com.semgravio.aiplatform.business.billing.application;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.BillingAppService.BillingTrack;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计费态用例集成测试（#531 计费①）：两轨判定单读点（落库可查、四消费面同源
 * 接口形态）、扣减源一次性选定、付费状态翻转门①/门②（服务层语义）与不可逆、
 * 全局折算率参数。bil_ 表与真实库交互；弱引用 projectId 直落（billing 不校验
 * 项目存在——跨 BC 事实归调用方域）。
 */
@IntegrationTest
class BillingAppServiceTest {

    private static final long PROJECT_ID = 920_001L;
    private static final long PROJECT_ID_2 = 920_002L;
    private static final long ACCOUNT_ID = 920_101L;

    @Autowired
    private BillingAppService appService;

    @Autowired
    private PoolTopupAppService topupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
        // 折算率回种值（迁移种子=1；本类只调不删，防御性复位防跨类污染）
        jdbcTemplate.update(
                "UPDATE bil_params SET param_value = 1 WHERE param_key = 'conversion_rate'");
    }

    @Test
    void given_no_billing_row_when_track_then_trial_with_no_source() {
        BillingTrack track = appService.trackOf(PROJECT_ID);

        // 行缺席＝试用开局（扣减源未设＋未翻转＝试用——两轨判定单读点）
        assertThat(track.trial()).isTrue();
        assertThat(track.paid()).isFalse();
        assertThat(track.deductionSource()).isNull();
        assertThat(track.sourcePoolId()).isNull();
        assertThat(track.sourcePoolBalance()).isNull();
        assertThat(track.sourcePoolPositive()).isFalse();
    }

    @Test
    void given_source_selected_when_track_then_source_visible_but_still_trial() {
        appService.selectSource(PROJECT_ID, DeductionSource.PROJECT, null);

        BillingTrack track = appService.trackOf(PROJECT_ID);

        // 源选定≠付费：池未建（未入账）余额面为 null
        assertThat(track.trial()).isTrue();
        assertThat(track.deductionSource()).isEqualTo(DeductionSource.PROJECT);
        assertThat(track.sourcePoolId()).isNull();
        assertThat(track.sourcePoolBalance()).isNull();
    }

    @Test
    void given_source_selected_when_user_selects_again_then_rejected() {
        appService.selectSource(PROJECT_ID, DeductionSource.PROJECT, null);

        assertThatThrownBy(() -> appService.selectSource(PROJECT_ID, DeductionSource.ACCOUNT, ACCOUNT_ID))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.SOURCE_ALREADY_SELECTED.message());
    }

    @Test
    void given_account_source_without_account_then_rejected() {
        assertThatThrownBy(() -> appService.selectSource(PROJECT_ID, DeductionSource.ACCOUNT, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.SOURCE_ACCOUNT_REQUIRED.message());
    }

    @Test
    void given_flip_without_source_then_rejected() {
        assertThatThrownBy(() -> appService.flipToPaid(PROJECT_ID, FlipGate.FORK))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.FLIP_SOURCE_REQUIRED.message());
    }

    @Test
    void given_source_but_empty_pool_when_flip_then_balance_gate_rejects() {
        appService.selectSource(PROJECT_ID, DeductionSource.PROJECT, null);

        // 源池未入账（余额面缺席）＝余额不为正：充值引导语义
        assertThatThrownBy(() -> appService.flipToPaid(PROJECT_ID, FlipGate.FORK))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.FLIP_BALANCE_NOT_POSITIVE.message());
        // 翻转失败不留痕：行未翻转（事务回滚）
        assertThat(appService.trackOf(PROJECT_ID).trial()).isTrue();
    }

    @Test
    void given_source_pool_negative_when_flip_then_balance_gate_rejects() {
        topupService.topup(null, PROJECT_ID, 1_000, null, null); // 入账即选源
        jdbcTemplate.update("UPDATE bil_pools SET balance = -1 WHERE project_id = ?", PROJECT_ID);

        assertThatThrownBy(() -> appService.flipToPaid(PROJECT_ID, FlipGate.INTERCEPT))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.FLIP_BALANCE_NOT_POSITIVE.message());
    }

    @Test
    void given_project_pool_topped_up_when_flip_via_gate1_then_paid_irreversible() {
        topupService.topup(null, PROJECT_ID, 10_000, null, null); // 入账即选源（项目池）

        appService.flipToPaid(PROJECT_ID, FlipGate.FORK);

        BillingTrack track = appService.trackOf(PROJECT_ID);
        assertThat(track.paid()).isTrue();
        assertThat(track.trial()).isFalse();
        assertThat(track.flippedAt()).isNotNull();
        assertThat(track.flipGate()).isEqualTo(FlipGate.FORK);
        assertThat(track.deductionSource()).isEqualTo(DeductionSource.PROJECT);
        assertThat(track.sourcePoolBalance()).isEqualTo(10_000L);
        assertThat(track.sourcePoolPositive()).isTrue();

        // 不可逆：同门/异门再翻一律拒绝
        assertThatThrownBy(() -> appService.flipToPaid(PROJECT_ID, FlipGate.FORK))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.FLIP_ALREADY_PAID.message());
        assertThatThrownBy(() -> appService.flipToPaid(PROJECT_ID, FlipGate.INTERCEPT))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.FLIP_ALREADY_PAID.message());
    }

    @Test
    void given_account_source_funded_when_flip_via_gate2_then_paid() {
        // 门②（拦截卡确认扣减）同语义：账户源＋账户池有余额
        appService.selectSource(PROJECT_ID_2, DeductionSource.ACCOUNT, ACCOUNT_ID);
        topupService.topup(ACCOUNT_ID, null, 5_000, null, null);

        appService.flipToPaid(PROJECT_ID_2, FlipGate.INTERCEPT);

        BillingTrack track = appService.trackOf(PROJECT_ID_2);
        assertThat(track.paid()).isTrue();
        assertThat(track.flipGate()).isEqualTo(FlipGate.INTERCEPT);
        assertThat(track.deductionSource()).isEqualTo(DeductionSource.ACCOUNT);
        assertThat(track.sourcePoolBalance()).isEqualTo(5_000L);
    }

    @Test
    void given_user_selected_when_backoffice_reselects_then_row_traces_operator() {
        appService.selectSource(PROJECT_ID, DeductionSource.ACCOUNT, ACCOUNT_ID);

        appService.reselectSourceByBackoffice(PROJECT_ID, DeductionSource.PROJECT, null,
                new Operator("9111111111", "运营丙"));

        var row = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_account_id, source_operator_id, source_operator_name"
                        + " FROM bil_project_billing WHERE project_id = ?", PROJECT_ID);
        assertThat(row.get("deduction_source")).isEqualTo(2); // PROJECT
        assertThat(row.get("source_account_id")).isNull();
        assertThat(row.get("source_operator_id")).isEqualTo("9111111111");
        assertThat(row.get("source_operator_name")).isEqualTo("运营丙");
    }

    @Test
    void given_seeded_rate_when_read_then_one_and_adjust_takes_effect() {
        // 迁移种子：conversion_rate = 1
        assertThat(appService.conversionRate()).isEqualByComparingTo(BigDecimal.ONE);

        appService.adjustParam("conversion_rate", new BigDecimal("1.5"),
                new Operator("9222222222", "运营丁"));

        assertThat(appService.conversionRate()).isEqualByComparingTo(new BigDecimal("1.5"));
        var row = jdbcTemplate.queryForMap(
                "SELECT param_value, operator_id FROM bil_params WHERE param_key = 'conversion_rate'");
        assertThat(((BigDecimal) row.get("param_value")).doubleValue()).isEqualTo(1.5d);
        assertThat(row.get("operator_id")).isEqualTo("9222222222");
    }

    @Test
    void given_unknown_param_or_bad_value_when_adjust_then_rejected() {
        // 键不存在＝应用层拦（BILL_015）；值非法＝聚合守卫拦（BILL_016）
        assertThatThrownBy(() -> appService.adjustParam("no_such_key", BigDecimal.ONE, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.PARAM_KEY_UNKNOWN.message());
        assertThatThrownBy(() -> appService.adjustParam("conversion_rate", BigDecimal.ZERO, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.PARAM_VALUE_INVALID.message());
    }
}
