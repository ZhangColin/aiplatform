package com.semgravio.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.semgravio.aiplatform.base.metering.domain.aggregate.TimeBandRule;
import com.semgravio.aiplatform.base.metering.domain.enums.Band;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;
import com.semgravio.aiplatform.base.metering.domain.model.GlobalUsageSummary;
import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.model.UsageSummary;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;
import com.semgravio.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.semgravio.aiplatform.base.metering.domain.repository.PriceEntryRepository;
import com.semgravio.aiplatform.base.metering.domain.repository.TimeBandRuleRepository;
import com.semgravio.aiplatform.base.metering.domain.repository.UsageEventAggregations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读时归段选价集成测试（#499，ADR-0044 峰谷 3/4，缝 B 真库边界）：事件 ts → 通道
 * ts 时点生效时段规则 → 归带（峰/谷）→ 选对应带单价行。经端口/仓储全链路真库
 * （sink → 落库 → 原生 SQL 归段换算），先例 {@link MeteringCostAggregationTest} 族。
 *
 * <p>归带口径（种子脚本头注＋ADR-0044）：通道无生效规则行＝不看时段（NULL 带行
 * 照旧匹配，存量语义）；命中窗口行＝该行带；有规则行但无窗口命中＝补带（声明了
 * 峰窗→谷、只声明谷窗→峰——DeepSeek 峰窗显式声明、其余谷）；按张档不受 band
 * 影响（只匹配 NULL 带行）。</p>
 *
 * <p>夹具通道 {@code bandprov}（DeepSeek 型：工作日两峰窗）与 {@code plainprov}
 * （无规则行）独立自持，收尾全清，与他类测试互不沾。</p>
 */
@IntegrationTest
class MeteringBandCostAggregationTest {

    /** 规则/单价生效起点（DeepSeek 分价日）。 */
    private static final Instant R0 = Instant.parse("2026-08-16T00:00:00Z");

    /** 周一（工作日，峰窗按 UTC 分钟判定）。 */
    private static final Instant MON = Instant.parse("2026-11-09T00:00:00Z");
    /** 周六（周末全天谷）。 */
    private static final Instant SAT = Instant.parse("2026-11-14T00:00:00Z");

    private static final String SUBJ = "band-proj";
    private static final String BANDPROV = "bandprov";
    private static final String PLAINPROV = "plainprov";
    private static final Currency CNY = Currency.getInstance("CNY");

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private UsageQueryPort usageQueryPort;

    @Autowired
    private UsageEventAggregations usageEventAggregations;

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private TimeBandRuleRepository timeBandRuleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE subject = '" + SUBJ + "'");
        jdbcTemplate.update("DELETE FROM met_price_entries WHERE provider IN ('"
                + BANDPROV + "', '" + PLAINPROV + "')");
        jdbcTemplate.update("DELETE FROM met_time_band_rules WHERE provider = '" + BANDPROV + "'");
    }

    @Test
    void given_peak_windows_when_events_at_window_boundaries_then_banded_by_minute() {
        // DeepSeek 型峰窗：工作日 01:00–04:00＋06:00–10:00 UTC，自 R0 敞口
        seedPeakWindows(R0);
        price(BANDPROV, "m-in", TokenKind.INPUT, Band.PEAK, "0.0000020", R0);
        price(BANDPROV, "m-in", TokenKind.INPUT, Band.OFF_PEAK, "0.0000010", R0);

        report("evt-before-start", at(MON, 5, 59, 59), BANDPROV, "m-in",
                new TokenUsage(1000, 0, 0, 0, 0));   // 05:59:59 → 谷
        report("evt-at-start", at(MON, 6, 0, 0), BANDPROV, "m-in",
                new TokenUsage(1000, 0, 0, 0, 0));   // 06:00:00 窗口起点（含）→ 峰
        report("evt-before-end", at(MON, 9, 59, 59), BANDPROV, "m-in",
                new TokenUsage(1000, 0, 0, 0, 0));   // 09:59:59 → 峰
        report("evt-at-end", at(MON, 10, 0, 0), BANDPROV, "m-in",
                new TokenUsage(1000, 0, 0, 0, 0));   // 10:00:00 窗口终点（不含）→ 谷

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 谷 2×0.001 + 峰 2×0.002 = ¥0.006——起/止分钟判死，含头不含尾
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.006"));
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_weekend_when_event_then_off_peak_all_day() {
        seedPeakWindows(R0);
        price(BANDPROV, "m-in", TokenKind.INPUT, Band.PEAK, "0.0000020", R0);
        price(BANDPROV, "m-in", TokenKind.INPUT, Band.OFF_PEAK, "0.0000010", R0);

        // 周六 07:00：工作日 mask（Mon-Fri）不命中 → 谷（周末全天谷）
        report("evt-sat", at(SAT, 7, 0, 0), BANDPROV, "m-in",
                new TokenUsage(1000, 0, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.001"));
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_no_rule_rows_when_query_then_null_band_rows_match_legacy_semantics() {
        // plainprov 无任何规则行：NULL 带行照旧匹配、带行不误配（不看时段）
        price(PLAINPROV, "m-null", TokenKind.INPUT, null, "0.0000015", R0);
        price(PLAINPROV, "m-banded", TokenKind.INPUT, Band.PEAK, "0.0000020", R0);

        report("evt-null", at(MON, 7, 0, 0), PLAINPROV, "m-null",
                new TokenUsage(1000, 0, 0, 0, 0));
        report("evt-banded", at(MON, 7, 0, 0), PLAINPROV, "m-banded",
                new TokenUsage(1000, 0, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.0015"));
        // 带行在无规则通道不命中 → 进缺价面（band 落空＝不看时段维度）
        assertThat(summary.unpriced()).containsExactly(
                new UsageSummary.UnpricedUsage(PLAINPROV, "m-banded", TokenKind.INPUT, null));
    }

    @Test
    void given_rule_effective_window_switch_when_events_straddle_then_banded_by_event_time() {
        // 规则版本仅 [Nov 1, Nov 8) 生效：切换瞬间两侧事件各按各时点规则面
        Instant f1 = Instant.parse("2026-11-01T00:00:00Z");
        Instant f2 = Instant.parse("2026-11-08T00:00:00Z");
        TimeBandRule window = timeBandRuleRepository.save(
                TimeBandRule.open(BANDPROV, Band.PEAK, 31, 360, 600, f1, null));
        window.close(f2);
        timeBandRuleRepository.save(window);
        // 单价：规则面前 NULL 带行（前后两段）＋规则面内峰/谷带行
        price(BANDPROV, "m-sw", TokenKind.INPUT, null, "0.0000015", R0, f1);
        price(BANDPROV, "m-sw", TokenKind.INPUT, null, "0.0000015", f2);
        price(BANDPROV, "m-sw", TokenKind.INPUT, Band.PEAK, "0.0000020", f1, f2);
        price(BANDPROV, "m-sw", TokenKind.INPUT, Band.OFF_PEAK, "0.0000010", f1, f2);

        Instant monIn = Instant.parse("2026-11-02T00:00:00Z");  // 规则面内周一
        report("evt-before-rules", at(Instant.parse("2026-10-31T00:00:00Z"), 7, 0, 0),
                BANDPROV, "m-sw", new TokenUsage(1000, 0, 0, 0, 0));  // 无规则 → NULL 带
        report("evt-in-peak", at(monIn, 7, 0, 0), BANDPROV, "m-sw",
                new TokenUsage(1000, 0, 0, 0, 0));                     // 规则面内峰窗 → 峰
        report("evt-in-valley", at(monIn, 12, 0, 0), BANDPROV, "m-sw",
                new TokenUsage(1000, 0, 0, 0, 0));                     // 规则面内非窗 → 谷
        report("evt-after-rules", Instant.parse("2026-11-08T00:00:00Z"),
                BANDPROV, "m-sw", new TokenUsage(1000, 0, 0, 0, 0));   // 规则关闭（不含）→ NULL 带

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // NULL 0.0015×2 + 峰 0.002 + 谷 0.001 = ¥0.006——生效区间起含终不含，两侧回落存量语义
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.006"));
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_peak_priced_but_valley_missing_when_valley_events_then_unpriced_with_band() {
        // 峰行有谷行无：谷段事件进缺价面（band=谷），不伪装 0、不阻断聚合
        seedPeakWindows(R0);
        price(BANDPROV, "m-peakonly", TokenKind.INPUT, Band.PEAK, "0.0000020", R0);

        report("evt-peak", at(MON, 7, 0, 0), BANDPROV, "m-peakonly",
                new TokenUsage(1000, 0, 0, 0, 0));
        report("evt-valley", at(MON, 12, 0, 0), BANDPROV, "m-peakonly",
                new TokenUsage(1000, 0, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // token 口径照含两段；金额口径只有峰段（谷段不伪装 0）
        assertThat(summary.total()).isEqualTo(new TokenUsage(2000, 0, 0, 0, 0));
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.002"));
        assertThat(summary.unpriced()).containsExactly(
                new UsageSummary.UnpricedUsage(
                        BANDPROV, "m-peakonly", TokenKind.INPUT, Band.OFF_PEAK));
    }

    @Test
    void given_band_reprice_when_events_straddle_boundary_then_historical_amount_stable() {
        // 峰带改价：关旧峰行开新峰行——历史事件按当时价，不随改价漂移（带内口径同 #29 验收）
        seedPeakWindows(R0);
        Instant repriceAt = Instant.parse("2026-11-10T00:00:00Z");
        PriceEntry oldPeak = priceEntryRepository.save(PriceEntry.open(BANDPROV, "m-rp",
                TokenKind.INPUT, Band.PEAK, new BigDecimal("0.0000020"), "CNY", R0, null));
        oldPeak.close(repriceAt);
        priceEntryRepository.save(oldPeak);
        priceEntryRepository.save(PriceEntry.open(BANDPROV, "m-rp",
                TokenKind.INPUT, Band.PEAK, new BigDecimal("0.0000040"), "CNY", repriceAt, null));
        price(BANDPROV, "m-rp", TokenKind.INPUT, Band.OFF_PEAK, "0.0000010", R0);

        Instant monBefore = Instant.parse("2026-11-09T07:00:00Z");  // 改价前周一峰窗
        Instant tueAfter = Instant.parse("2026-11-10T07:00:00Z");   // 改价后周二峰窗
        report("evt-old-price", monBefore, BANDPROV, "m-rp",
                new TokenUsage(1000, 0, 0, 0, 0));
        report("evt-new-price", tueAfter, BANDPROV, "m-rp",
                new TokenUsage(1000, 0, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.006"));
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_image_tier_when_channel_banded_then_null_band_row_matches_regardless_of_time() {
        // 按张档不受 band 影响：峰段按张事件仍匹配 NULL 带按张行（存量语义零变动）
        seedPeakWindows(R0);
        price(BANDPROV, "m-img", TokenKind.IMAGE, null, "0.0100", R0);

        report("evt-img-peak", at(MON, 7, 0, 0), BANDPROV, "m-img", 5);
        report("evt-img-valley", at(MON, 12, 0, 0), BANDPROV, "m-img", 3);

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 8 张 × ¥0.01 = ¥0.08——按张行不带维度，峰/谷时刻都照配
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.08"));
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_banded_channel_when_global_aggregate_then_by_model_cost_split_by_band() {
        // 聚合输出带峰/谷拆分：分模型金额按带拆（含 NULL 带＝不区分时段分量）
        seedPeakWindows(R0);
        price(BANDPROV, "m-in", TokenKind.INPUT, Band.PEAK, "0.0000020", R0);
        price(BANDPROV, "m-in", TokenKind.INPUT, Band.OFF_PEAK, "0.0000010", R0);
        price(PLAINPROV, "m-plain", TokenKind.INPUT, null, "0.0000015", R0);

        report("evt-peak", at(MON, 7, 0, 0), BANDPROV, "m-in",
                new TokenUsage(1000, 0, 0, 0, 0));
        report("evt-valley", at(MON, 12, 0, 0), BANDPROV, "m-in",
                new TokenUsage(2000, 0, 0, 0, 0));
        report("evt-plain", at(MON, 7, 0, 0), PLAINPROV, "m-plain",
                new TokenUsage(1000, 0, 0, 0, 0));

        GlobalUsageSummary global = usageEventAggregations.aggregateGlobal(null, null);

        // 三行：bandprov 峰/谷各一行＋plainprov NULL 带一行（金额 NUMERIC 标度走 compare）
        assertThat(global.byModelCost()).hasSize(3);
        assertThat(global.byModelCost())
                .anySatisfy(row -> {
                    assertThat(row.provider()).isEqualTo(BANDPROV);
                    assertThat(row.model()).isEqualTo("m-in");
                    assertThat(row.band()).isEqualTo(Band.PEAK);
                    assertThat(row.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.002"));
                })
                .anySatisfy(row -> {
                    assertThat(row.provider()).isEqualTo(BANDPROV);
                    assertThat(row.model()).isEqualTo("m-in");
                    assertThat(row.band()).isEqualTo(Band.OFF_PEAK);
                    assertThat(row.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.002"));
                })
                .anySatisfy(row -> {
                    assertThat(row.provider()).isEqualTo(PLAINPROV);
                    assertThat(row.model()).isEqualTo("m-plain");
                    assertThat(row.band()).isNull();
                    assertThat(row.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.0015"));
                });
    }

    // ---------- fixture ----------

    /** DeepSeek 型峰窗种入：工作日（mask 31）01:00–04:00＋06:00–10:00 UTC，自 from 敞口。 */
    private void seedPeakWindows(Instant from) {
        timeBandRuleRepository.save(TimeBandRule.open(
                BANDPROV, Band.PEAK, 31, 60, 240, from, null));
        timeBandRuleRepository.save(TimeBandRule.open(
                BANDPROV, Band.PEAK, 31, 360, 600, from, null));
    }

    private void price(String provider, String model, TokenKind kind, Band band,
                       String unitPrice, Instant from) {
        priceEntryRepository.save(PriceEntry.open(provider, model, kind, band,
                new BigDecimal(unitPrice), "CNY", from, null));
    }

    private void price(String provider, String model, TokenKind kind, Band band,
                       String unitPrice, Instant from, Instant to) {
        PriceEntry entry = PriceEntry.open(provider, model, kind, band,
                new BigDecimal(unitPrice), "CNY", from, null);
        entry.close(to);
        priceEntryRepository.save(entry);
    }

    private void report(String eventId, Instant ts, String provider, String model,
                        TokenUsage tokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, SUBJ, "run-1", "session-1",
                provider, model, Map.of(), tokens));
    }

    private void report(String eventId, Instant ts, String provider, String model, long images) {
        usageEventSink.report(new UsageEvent(eventId, ts, SUBJ, "run-1", "session-1",
                provider, model, Map.of(), new TokenUsage(0, 0, 0, 0, 0), images));
    }

    private static Instant at(Instant day, int hour, int minute, int second) {
        return day.plusSeconds(hour * 3600L + minute * 60L + second);
    }
}
