package com.semgravio.aiplatform.business.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;
import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;
import com.semgravio.aiplatform.base.metering.domain.repository.PriceEntryRepository;
import com.semgravio.aiplatform.business.billing.application.SegmentSettlementAppService.SettlementOutcome;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;
import com.semgravio.aiplatform.business.billing.domain.enums.EntryKind;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.billing.domain.enums.PieceKind;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 段结算用例集成测试（#531 计费①验收面）：付费项目三类结算段收口各落一条扣减行
 * （金额＋用量＋单价快照齐全）、试用项目零扣减行、折算率/单价调整后既有行不漂移
 * （快照断言）、固定单价项按次直记、段中自由（扣成负照落）、段收口幂等（同段址
 * 重试不双扣）、计费边界＝翻转时点（翻转前事件平台担）、未配价/非 CNY 分量不进
 * 用户账。用量与单价走真实计量链路（事件→档位展开→区间匹配），计价单源与平台
 * 成本观测同一账。fixture 独立 provider（chatprov/fixprov），与他类互不沾；事件
 * ts 用翻转后动态时点（计费边界过滤的口径内夹具）。
 */
@IntegrationTest
class SegmentSettlementAppServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");

    private static final String TOKEN_PROVIDER = "chatprov";
    private static final String TOKEN_MODEL = "chat-model";
    private static final String FIXED_PROVIDER = "fixprov";
    private static final String FIXED_MODEL = "web-search";

    private static final long PAID_PROJECT = 922_001L;
    private static final long TRIAL_PROJECT = 922_002L;

    @Autowired
    private SegmentSettlementAppService appService;

    @Autowired
    private PoolTopupAppService topupService;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private BillingEntryRepository entryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
        jdbcTemplate.update(
                "UPDATE bil_params SET param_value = 1 WHERE param_key = 'conversion_rate'");
        jdbcTemplate.update("DELETE FROM met_usage_events");
        jdbcTemplate.update("DELETE FROM met_price_entries WHERE provider IN ('"
                + TOKEN_PROVIDER + "', '" + FIXED_PROVIDER + "')");
    }

    /** 付费项目夹具：项目池入账（即选源）→ 门①翻转（flippedAt＝此刻）。 */
    private void paidProject(long projectId, long amountFen) {
        topupService.topup(null, projectId, amountFen, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.FORK);
    }

    /** token 价行：INPUT ¥1/M、OUTPUT ¥2/M（CNY，自 T0 起敞口）。 */
    private void seedTokenPrices() {
        priceEntryRepository.save(PriceEntry.open(TOKEN_PROVIDER, TOKEN_MODEL,
                TokenKind.INPUT, new BigDecimal("0.000001"), "CNY", T0, null));
        priceEntryRepository.save(PriceEntry.open(TOKEN_PROVIDER, TOKEN_MODEL,
                TokenKind.OUTPUT, new BigDecimal("0.000002"), "CNY", T0, null));
    }

    /** token 事件：1M 输入＋0.5M 输出（= ¥1 + ¥1 = ¥2 = 200 分，rate=1）。 */
    private void reportTokenUsage(String eventId, String runId, long projectId, Instant ts) {
        usageEventSink.report(new UsageEvent(eventId, ts, Long.toString(projectId), runId,
                "session-1", TOKEN_PROVIDER, TOKEN_MODEL, Map.of("agentKind", "main"),
                new TokenUsage(1_000_000, 500_000, 0, 0, 0)));
    }

    @Test
    void given_paid_project_when_three_segment_types_settle_then_one_deduction_row_each() {
        seedTokenPrices();
        paidProject(PAID_PROJECT, 100_000);
        Instant now = Instant.now(); // 翻转后（计费边界内）

        // 三段型各一段（主智能体轮/run 场/设计会话轮），各一条扣减行
        reportTokenUsage("evt-main-1", "turn-run-1", PAID_PROJECT, now);
        reportTokenUsage("evt-run-1", "run-9-1", PAID_PROJECT, now);
        reportTokenUsage("evt-run-2", "run-9-2", PAID_PROJECT, now); // 同段多事件按段汇总
        reportTokenUsage("evt-design-1", "design-run-1", PAID_PROJECT, now);

        SettlementOutcome main = appService.settleSegment(PAID_PROJECT,
                SegmentType.MAIN_TURN, "turn-1", List.of("turn-run-1"));
        SettlementOutcome run = appService.settleSegment(PAID_PROJECT,
                SegmentType.GENERATION_RUN, "run-9", List.of("run-9-1", "run-9-2"));
        SettlementOutcome design = appService.settleSegment(PAID_PROJECT,
                SegmentType.DESIGN_TURN, "design-turn-1", List.of("design-run-1"));

        assertThat(main.settled()).isTrue();
        assertThat(run.settled()).isTrue();
        assertThat(design.settled()).isTrue();

        List<BillingEntry> rows = entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getKind()).isEqualTo(EntryKind.DEDUCTION);
            assertThat(row.getProjectId()).isEqualTo(PAID_PROJECT);
            assertThat(row.getCurrency()).isEqualTo("CNY");
        });
        // 主智能体轮：1M×0.000001 + 0.5M×0.000002 = ¥2 = 200 分
        BillingEntry mainRow = rows.get(0);
        assertThat(mainRow.getSegmentType()).isEqualTo(SegmentType.MAIN_TURN);
        assertThat(mainRow.getSegmentRef()).isEqualTo("turn-1");
        assertThat(mainRow.getAmount()).isEqualTo(-200L);
        assertThat(main.amountFen()).isEqualTo(200L);
        // run 场：两事件按段汇总成一条（同额 ¥4 = 400 分）
        assertThat(rows.get(1).getSegmentType()).isEqualTo(SegmentType.GENERATION_RUN);
        assertThat(rows.get(1).getAmount()).isEqualTo(-400L);
        assertThat(rows.get(2).getSegmentType()).isEqualTo(SegmentType.DESIGN_TURN);
        assertThat(rows.get(2).getAmount()).isEqualTo(-200L);

        // 快照齐全：折算率＋计价线（用量/单价/币种）——JSON 往返数值经字符串回读
        assertThat(mainRow.getSnapshot()).isNotNull();
        assertThat(new BigDecimal(mainRow.getSnapshot().get("rate").toString()))
                .isEqualByComparingTo(BigDecimal.ONE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) mainRow.getSnapshot().get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.get("kind")).isEqualTo("INPUT");
            assertThat(((Number) line.get("quantity")).longValue()).isEqualTo(1_000_000L);
            assertThat(new BigDecimal(line.get("unitPrice").toString()))
                    .isEqualByComparingTo("0.000001");
        });

        // 池扣减到位（100_000 - 200〔主轮〕- 400〔run〕- 200〔设计轮〕）
        assertThat(main.balanceAfter()).isEqualTo(100_000L - 200L);
        assertThat(run.balanceAfter()).isEqualTo(100_000L - 600L);
        assertThat(design.balanceAfter()).isEqualTo(100_000L - 800L);
    }

    @Test
    void given_trial_project_when_settle_then_zero_deduction_rows_and_pool_untouched() {
        seedTokenPrices();
        reportTokenUsage("evt-trial-1", "run-trial-1", TRIAL_PROJECT, T0);

        SettlementOutcome outcome = appService.settleSegment(TRIAL_PROJECT,
                SegmentType.MAIN_TURN, "turn-1", List.of("run-trial-1"));
        SettlementOutcome fixed = appService.recordFixedItem(TRIAL_PROJECT,
                FIXED_PROVIDER, FIXED_MODEL, PieceKind.UNIT, 1, "search-evt-1");

        // 试用项目一切智能体调用零扣减行（行不落、池不动——翻转前平台担）
        assertThat(outcome.settled()).isFalse();
        assertThat(outcome.skippedReason()).isEqualTo("TRIAL");
        assertThat(fixed.settled()).isFalse();
        assertThat(entryRepository.findByProjectIdAndKindOrderByIdAsc(
                TRIAL_PROJECT, EntryKind.DEDUCTION)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_entries", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bil_pools", Integer.class)).isZero();
    }

    @Test
    void given_settled_segment_when_settle_same_ref_again_then_idempotent_skip() {
        seedTokenPrices();
        paidProject(PAID_PROJECT, 100_000);
        reportTokenUsage("evt-idem-1", "run-idem", PAID_PROJECT, Instant.now());
        appService.settleSegment(PAID_PROJECT, SegmentType.MAIN_TURN, "turn-idem",
                List.of("run-idem"));

        // 段收口重试/重放（同段型同段址）：first-write-wins 不双扣
        SettlementOutcome retry = appService.settleSegment(PAID_PROJECT,
                SegmentType.MAIN_TURN, "turn-idem", List.of("run-idem"));

        assertThat(retry.settled()).isFalse();
        assertThat(retry.skippedReason()).isEqualTo("ALREADY_SETTLED");
        assertThat(entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION)).hasSize(1);
        // 库侧段幂等唯一索引在位（并发兜底）
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uk_bil_entries_segment'",
                Integer.class);
        assertThat(indexCount).isEqualTo(1);
    }

    @Test
    void given_events_before_flip_when_settle_then_billing_boundary_filters_them() {
        seedTokenPrices();
        // 翻转前的事件（ts 在 flippedAt 之前）＋翻转后的事件各一
        topupService.topup(null, PAID_PROJECT, 100_000, null, null);
        reportTokenUsage("evt-pre-flip", "run-pre", PAID_PROJECT, Instant.now().minusSeconds(3_600));
        billingAppService.flipToPaid(PAID_PROJECT, FlipGate.INTERCEPT);
        reportTokenUsage("evt-post-flip", "run-post", PAID_PROJECT, Instant.now());

        SettlementOutcome outcome = appService.settleSegment(PAID_PROJECT,
                SegmentType.MAIN_TURN, "turn-boundary", List.of("run-pre", "run-post"));

        // 计费边界＝翻转时间点：翻转前事件＝平台获客成本不进用户账，只结算翻转后
        assertThat(outcome.settled()).isTrue();
        assertThat(outcome.amountFen()).isEqualTo(200L); // 仅 post-flip 一条事件 ¥2
        BillingEntry row = entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) row.getSnapshot().get("lines");
        // 单事件的分线（INPUT 1M＋OUTPUT 0.5M）；pre-flip 事件的同形线未混入
        assertThat(lines).hasSize(2);
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.get("kind")).isEqualTo("INPUT");
            assertThat(((Number) line.get("quantity")).longValue()).isEqualTo(1_000_000L);
        });
        assertThat(lines).anySatisfy(line -> {
            assertThat(line.get("kind")).isEqualTo("OUTPUT");
            assertThat(((Number) line.get("quantity")).longValue()).isEqualTo(500_000L);
        });
    }

    @Test
    void given_settled_rows_when_price_and_rate_change_then_existing_rows_do_not_drift() {
        seedTokenPrices();
        paidProject(PAID_PROJECT, 1_000_000);

        reportTokenUsage("evt-before-1", "run-before", PAID_PROJECT, Instant.now());
        appService.settleSegment(PAID_PROJECT, SegmentType.MAIN_TURN, "turn-before",
                List.of("run-before"));
        BillingEntry before = entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION).get(0);
        long beforeAmount = -before.getAmount();
        var beforeSnapshotLines = before.getSnapshot().get("lines");
        var beforeRate = before.getSnapshot().get("rate");

        // 调参：改价（关旧行开新行×10，边界＝此刻）＋折算率 1→2
        Instant changeAt = Instant.now();
        var oldRow = priceEntryRepository.findByProviderAndModelAndTokenKindOrderByEffectiveFromAsc(
                TOKEN_PROVIDER, TOKEN_MODEL, TokenKind.OUTPUT).get(0);
        oldRow.close(changeAt);
        priceEntryRepository.save(oldRow);
        priceEntryRepository.save(PriceEntry.open(TOKEN_PROVIDER, TOKEN_MODEL,
                TokenKind.OUTPUT, new BigDecimal("0.00002"), "CNY", changeAt, null));
        billingAppService.adjustParam("conversion_rate", new BigDecimal("2"), null);

        reportTokenUsage("evt-after-1", "run-after", PAID_PROJECT, changeAt.plusSeconds(1));
        appService.settleSegment(PAID_PROJECT, SegmentType.MAIN_TURN, "turn-after",
                List.of("run-after"));
        List<BillingEntry> rows = entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION);
        BillingEntry after = rows.get(1);

        // 既有行不漂移：金额、快照里的单价与折算率保持结算时点值
        assertThat(before.getAmount()).isEqualTo(-beforeAmount);
        assertThat(before.getSnapshot().get("lines")).isEqualTo(beforeSnapshotLines);
        assertThat(before.getSnapshot().get("rate")).isEqualTo(beforeRate);
        // 新行吃新价新率：input 1M×0.000001×2 + output 0.5M×0.00002×2 = ¥2 + ¥20 = ¥22
        assertThat(after.getAmount()).isEqualTo(-2_200L);
    }

    @Test
    void given_fixed_priced_item_when_record_then_direct_entry_without_rate_and_idempotent() {
        priceEntryRepository.save(PriceEntry.open(FIXED_PROVIDER, FIXED_MODEL,
                TokenKind.UNIT, new BigDecimal("0.02"), "CNY", T0, null));
        paidProject(PAID_PROJECT, 100_000);
        billingAppService.adjustParam("conversion_rate", new BigDecimal("3"), null); // 固定项不乘率

        SettlementOutcome outcome = appService.recordFixedItem(PAID_PROJECT,
                FIXED_PROVIDER, FIXED_MODEL, PieceKind.UNIT, 3, "search-evt-9");
        SettlementOutcome replay = appService.recordFixedItem(PAID_PROJECT,
                FIXED_PROVIDER, FIXED_MODEL, PieceKind.UNIT, 3, "search-evt-9");

        // 按次直记：3 件 × ¥0.02 = ¥0.06 = 6 分（不乘折算率、不等段收口）
        assertThat(outcome.settled()).isTrue();
        assertThat(outcome.amountFen()).isEqualTo(6L);
        // 同锚点重放幂等（段址＝发生锚点）
        assertThat(replay.settled()).isFalse();
        assertThat(replay.skippedReason()).isEqualTo("ALREADY_SETTLED");
        List<BillingEntry> rows = entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION);
        assertThat(rows).hasSize(1);
        BillingEntry row = rows.get(0);
        assertThat(row.getSegmentType()).isEqualTo(SegmentType.FIXED_ITEM);
        assertThat(row.getSegmentRef()).isEqualTo("search-evt-9");
        assertThat(row.getAmount()).isEqualTo(-6L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) row.getSnapshot().get("lines");
        assertThat(((Number) lines.get(0).get("quantity")).longValue()).isEqualTo(3L);
        assertThat(new BigDecimal(lines.get(0).get("unitPrice").toString()))
                .isEqualByComparingTo("0.02");
    }

    @Test
    void given_fixed_item_without_price_row_when_record_then_skipped_unpriced() {
        paidProject(PAID_PROJECT, 100_000);

        SettlementOutcome outcome = appService.recordFixedItem(PAID_PROJECT,
                FIXED_PROVIDER, FIXED_MODEL, PieceKind.UNIT, 1, "search-evt-x");

        // 无生效单价行：不伪装 0 价直记（平台吸收、unpriced 观测面可见）
        assertThat(outcome.settled()).isFalse();
        assertThat(outcome.skippedReason()).isEqualTo("UNPRICED");
        assertThat(entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION)).isEmpty();
    }

    @Test
    void given_unpriced_token_usage_when_settle_then_no_row() {
        // 有用量、无单价行（未开价）→ 段收口零金额不落行
        paidProject(PAID_PROJECT, 100_000);
        reportTokenUsage("evt-noprice-1", "run-noprice", PAID_PROJECT, Instant.now());

        SettlementOutcome outcome = appService.settleSegment(PAID_PROJECT,
                SegmentType.GENERATION_RUN, "run-noprice", List.of("run-noprice"));

        assertThat(outcome.settled()).isFalse();
        assertThat(outcome.skippedReason()).isEqualTo("NO_PRICED_USAGE");
        assertThat(entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION)).isEmpty();
    }

    @Test
    void given_non_cny_priced_usage_when_settle_then_only_cny_billed_rest_snapshotted() {
        // 同段两模型：CNY 模型照算；USD 模型不进用户账（快照 unconvertedLines 留痕）
        seedTokenPrices();
        priceEntryRepository.save(PriceEntry.open(TOKEN_PROVIDER, "usd-model",
                TokenKind.OUTPUT, new BigDecimal("0.000003"), "USD", T0, null));
        paidProject(PAID_PROJECT, 100_000);
        Instant now = Instant.now();
        usageEventSink.report(new UsageEvent("evt-cny-1", now, Long.toString(PAID_PROJECT),
                "run-mix-1", "session-1", TOKEN_PROVIDER, TOKEN_MODEL, Map.of(),
                new TokenUsage(0, 500_000, 0, 0, 0)));
        usageEventSink.report(new UsageEvent("evt-usd-1", now, Long.toString(PAID_PROJECT),
                "run-mix-2", "session-1", TOKEN_PROVIDER, "usd-model", Map.of(),
                new TokenUsage(0, 500_000, 0, 0, 0)));

        SettlementOutcome outcome = appService.settleSegment(PAID_PROJECT,
                SegmentType.MAIN_TURN, "turn-mix", List.of("run-mix-1", "run-mix-2"));

        // 只结算 CNY 线：0.5M×0.000002 = ¥1 = 100 分；USD 线平台吸收快照留痕
        assertThat(outcome.settled()).isTrue();
        assertThat(outcome.amountFen()).isEqualTo(100L);
        BillingEntry row = entryRepository.findByProjectIdAndKindOrderByIdAsc(
                PAID_PROJECT, EntryKind.DEDUCTION).get(0);
        assertThat(row.getSnapshot().get("unconvertedLines")).isNotNull();
        assertThat((List<?>) row.getSnapshot().get("lines")).hasSize(1);
    }

    @Test
    void given_amount_beyond_balance_when_settle_then_negative_balance_allowed() {
        seedTokenPrices();
        paidProject(PAID_PROJECT, 100); // 池仅 ¥1

        reportTokenUsage("evt-over-1", "run-over", PAID_PROJECT, Instant.now());
        SettlementOutcome outcome = appService.settleSegment(PAID_PROJECT,
                SegmentType.GENERATION_RUN, "run-over", List.of("run-over"));

        // 段中自由：扣成负照落（不查余额、不中断——余额检查只在段边界 #537）
        assertThat(outcome.settled()).isTrue();
        assertThat(outcome.balanceAfter()).isEqualTo(100L - 200L);
        assertThat(topupService.projectPoolOf(PAID_PROJECT))
                .hasValueSatisfying(pool -> assertThat(pool.balance()).isEqualTo(-100L));
    }

    @Test
    void given_invalid_segment_args_when_settle_then_rejected() {
        assertThatThrownBy(() -> appService.settleSegment(PAID_PROJECT,
                SegmentType.FIXED_ITEM, "ref", List.of("run-1")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.SETTLEMENT_SEGMENT_INVALID.message());
        assertThatThrownBy(() -> appService.settleSegment(PAID_PROJECT,
                SegmentType.MAIN_TURN, "ref", List.of()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.SETTLEMENT_SEGMENT_INVALID.message());
        assertThatThrownBy(() -> appService.settleSegment(PAID_PROJECT,
                SegmentType.MAIN_TURN, "ref", null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.SETTLEMENT_SEGMENT_INVALID.message());
        assertThatThrownBy(() -> appService.recordFixedItem(PAID_PROJECT,
                FIXED_PROVIDER, FIXED_MODEL, PieceKind.UNIT, 0, "ref"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(BillingMessage.FIXED_ITEM_INVALID.message());
    }
}
