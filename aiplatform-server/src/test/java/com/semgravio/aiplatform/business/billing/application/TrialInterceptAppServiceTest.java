package com.semgravio.aiplatform.business.billing.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.BillingAppService.BillingTrack;
import com.semgravio.aiplatform.business.billing.application.TrialInterceptAppService.InterceptCard;
import com.semgravio.aiplatform.business.billing.application.TrialInterceptAppService.PendingHold;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.enums.InterceptLane;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 试用拦截面用例集成测试（#533 计费②门②）：留存（block——新拦取代旧待决）、
 * 卡可回读面（pendingCard——已付费熄灭）、确认扣减＝翻转门②（随卡首次选源/
 * 免选直确认/BILL_010/011/009 前置、已付费竞态幂等、留存落定）。bil_ 表与真实库
 * 交互；弱引用 projectId 直落（billing 不校验项目存在——跨 BC 事实归调用方域）。
 */
@IntegrationTest
class TrialInterceptAppServiceTest {

    private static final long PROJECT_ID = 920_011L;
    private static final long ACCOUNT_ID = 920_111L;

    @Autowired
    private TrialInterceptAppService interceptService;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private PoolTopupAppService topupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_intercepts");
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    @Test
    void given_no_hold_when_pending_card_then_empty() {
        assertThat(interceptService.pendingCard(PROJECT_ID)).isEmpty();
    }

    @Test
    void given_block_when_pending_card_then_card_with_source_state() {
        InterceptCard card = interceptService.block(PROJECT_ID, InterceptLane.OPINION,
                "把主色调改成绿色", List.of(Map.of("attachmentType", "image",
                        "name", "ref.png", "path", "materials/ref.png")), "run-i1");

        Optional<InterceptCard> pending = interceptService.pendingCard(PROJECT_ID);

        assertThat(pending).isPresent();
        assertThat(pending.get().interceptId()).isEqualTo(card.interceptId());
        assertThat(pending.get().lane()).isEqualTo("OPINION");
        assertThat(pending.get().prompt()).isEqualTo("把主色调改成绿色");
        assertThat(pending.get().sourceSelected()).isFalse();
        assertThat(pending.get().deductionSource()).isNull();
        assertThat(pending.get().sourcePoolBalance()).isNull();
        assertThat(pending.get().runId()).isEqualTo("run-i1");
        // 落库可查：待决行在位（附件载荷原样）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT lane, prompt, status, run_id FROM bil_intercepts WHERE project_id = ?",
                PROJECT_ID);
        assertThat(row.get("lane")).isEqualTo(1);
        assertThat(row.get("prompt")).isEqualTo("把主色调改成绿色");
        assertThat(row.get("status")).isEqualTo(1);
        assertThat(row.get("run_id")).isEqualTo("run-i1");
    }

    @Test
    void given_second_block_when_pending_then_first_superseded() {
        interceptService.block(PROJECT_ID, InterceptLane.OPINION, "旧输入", null, "run-old");
        InterceptCard latest = interceptService.block(PROJECT_ID, InterceptLane.FALLBACK,
                "新输入", null, "run-new");

        InterceptCard pending = interceptService.pendingCard(PROJECT_ID).orElseThrow();
        assertThat(pending.interceptId()).isEqualTo(latest.interceptId());
        assertThat(pending.lane()).isEqualTo("FALLBACK");
        // 旧待决被取代（行保留＝审计面）
        Integer superseded = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bil_intercepts WHERE project_id = ? AND status = 3",
                Integer.class, PROJECT_ID);
        assertThat(superseded).isEqualTo(1);
    }

    @Test
    void given_project_paid_when_pending_card_then_empty() {
        interceptService.block(PROJECT_ID, InterceptLane.OPINION, "输入", null, "run-x");
        topupService.topup(null, PROJECT_ID, 10_000, null, null); // 入账即选源（项目池）
        billingAppService.flipToPaid(PROJECT_ID, FlipGate.INTERCEPT);

        assertThat(interceptService.pendingCard(PROJECT_ID)).isEmpty();
    }

    @Test
    void given_no_hold_when_require_pending_then_bill_017() {
        assertThatThrownBy(() -> interceptService.requirePending(PROJECT_ID))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.INTERCEPT_NOT_PENDING.message());
    }

    @Test
    void given_hold_without_source_when_confirm_without_pick_then_bill_010() {
        interceptService.block(PROJECT_ID, InterceptLane.OPINION, "输入", null, "run-c1");

        assertThatThrownBy(() -> interceptService.confirm(PROJECT_ID, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.FLIP_SOURCE_REQUIRED.message());
        // 前置拒：留存仍待决（卡可回）
        assertThat(interceptService.pendingCard(PROJECT_ID)).isPresent();
        assertThat(billingAppService.trackOf(PROJECT_ID).trial()).isTrue();
    }

    @Test
    void given_hold_when_confirm_project_source_with_empty_pool_then_bill_011() {
        interceptService.block(PROJECT_ID, InterceptLane.OPINION, "输入", null, "run-c2");

        // 随卡选项目源＝本项目余额：池未入账（余额非正）＝充值引导语义，翻转拒绝
        assertThatThrownBy(() -> interceptService.confirm(PROJECT_ID, DeductionSource.PROJECT, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.FLIP_BALANCE_NOT_POSITIVE.message());
        assertThat(interceptService.pendingCard(PROJECT_ID)).isPresent();
        assertThat(billingAppService.trackOf(PROJECT_ID).trial()).isTrue();
    }

    @Test
    void given_hold_when_confirm_account_source_without_login_then_bill_009() {
        interceptService.block(PROJECT_ID, InterceptLane.OPINION, "输入", null, "run-c3");

        assertThatThrownBy(() -> interceptService.confirm(PROJECT_ID, DeductionSource.ACCOUNT, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(BillingMessage.SOURCE_ACCOUNT_REQUIRED.message());
    }

    @Test
    void given_backoffice_topped_project_pool_when_confirm_without_pick_then_flip_via_gate2() {
        // 后台已充项目池（入账即选源，前台零呈现）——免登录直接确认（销售演示动线）
        topupService.topup(null, PROJECT_ID, 10_000, null, null);
        interceptService.block(PROJECT_ID, InterceptLane.OPINION, "把字号调大",
                List.of(Map.of("attachmentType", "image", "name", "a.png", "path", "materials/a.png")),
                "run-c4");

        PendingHold hold = interceptService.confirm(PROJECT_ID, null, null);

        BillingTrack track = billingAppService.trackOf(PROJECT_ID);
        assertThat(track.paid()).isTrue();
        assertThat(track.flipGate()).isEqualTo(FlipGate.INTERCEPT);
        assertThat(track.deductionSource()).isEqualTo(DeductionSource.PROJECT);
        // 留存落定已确认并回传载荷（恢复受理面：原文＋附件不重输）
        assertThat(hold.lane()).isEqualTo(InterceptLane.OPINION);
        assertThat(hold.prompt()).isEqualTo("把字号调大");
        assertThat(hold.attachments()).hasSize(1);
        assertThat(hold.runId()).isEqualTo("run-c4");
        Integer confirmed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bil_intercepts WHERE project_id = ? AND status = 2",
                Integer.class, PROJECT_ID);
        assertThat(confirmed).isEqualTo(1);
        // 再确认＝无待决（BILL_017）——留存已消费
        assertThatThrownBy(() -> interceptService.confirm(PROJECT_ID, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.INTERCEPT_NOT_PENDING.message());
    }

    @Test
    void given_hold_when_confirm_with_account_source_then_flip_with_account_snapshot() {
        // 账户源先充正（flipToPaid 的 BILL_011 源池余额非正前置对两源同口径）
        topupService.topup(ACCOUNT_ID, null, 10_000, null, null);
        interceptService.block(PROJECT_ID, InterceptLane.FALLBACK, "谢谢", null, "run-c5");

        PendingHold hold = interceptService.confirm(PROJECT_ID, DeductionSource.ACCOUNT, ACCOUNT_ID);

        BillingTrack track = billingAppService.trackOf(PROJECT_ID);
        assertThat(track.paid()).isTrue();
        assertThat(track.deductionSource()).isEqualTo(DeductionSource.ACCOUNT);
        assertThat(hold.lane()).isEqualTo(InterceptLane.FALLBACK); // 兜底留存：恢复走定型轻引导
    }

    @Test
    void given_paid_project_when_confirm_then_idempotent_resume_payload() {
        // 他路翻转（门①）竞态：跳过翻转、只落定留存并回传载荷（受理仍恢复）
        topupService.topup(null, PROJECT_ID, 10_000, null, null);
        billingAppService.flipToPaid(PROJECT_ID, FlipGate.FORK);
        interceptService.block(PROJECT_ID, InterceptLane.OPINION, "输入", null, "run-c6");

        PendingHold hold = interceptService.confirm(PROJECT_ID, null, null);

        assertThat(hold.prompt()).isEqualTo("输入");
        assertThat(billingAppService.trackOf(PROJECT_ID).flipGate()).isEqualTo(FlipGate.FORK);
        assertThat(interceptService.pendingCard(PROJECT_ID)).isEmpty(); // 已确认且已付费
    }
}
