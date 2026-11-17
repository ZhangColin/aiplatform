package com.semgravio.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.semgravio.aiplatform.base.metering.domain.enums.CostBucket;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;
import com.semgravio.aiplatform.base.metering.domain.model.CostBucketUsage;
import com.semgravio.aiplatform.base.metering.domain.model.GlobalUsageSummary;
import com.semgravio.aiplatform.base.metering.domain.model.SubjectCostSummary;
import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;
import com.semgravio.aiplatform.base.metering.domain.repository.PriceEntryRepository;
import com.semgravio.aiplatform.base.metering.domain.repository.UsageEventAggregations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本观测切片桶聚合测试（#536 计费⑥验收口径）：已翻转 subject 用量按<b>事件
 * 时间戳</b>对切片锚归桶——ts &lt; 锚＝获客成本桶、ts &gt;= 锚＝用户费用桶
 * （翻转时刻本身归用户费用，与计费段结算 {@code e.ts >= flippedAt} 同边界口径）；
 * 未翻转 subject（锚缺席）与空锚集＝全量获客成本桶；两桶互补不重叠、并集＝
 * 全局/行总量；无价分量进桶内 tokens 不进桶内 cost（与 unpriced 面互补）。
 * 锚以裸 Map 直注聚合口（端口供数面另测），事件与单价行走真库全链路。
 *
 * <p>单价/事件 fixture 用独立 provider {@code sliceprov}，与他类测试互不沾；
 * 清单/全局两口径都是全表聚合，事件表前后全清。</p>
 */
@IntegrationTest
class MeteringSliceBucketAggregationTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-03T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-07-04T00:00:00Z");

    /** 已翻转 subject（锚＝T2）与未翻转 subject（锚缺席）。 */
    private static final String FLIP = "slice-flip";
    private static final String TRIAL = "slice-trial";

    private static final String PROVIDER = "sliceprov";
    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    private UsageEventAggregations usageEventAggregations;

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM met_usage_events");
        jdbcTemplate.update("DELETE FROM met_price_entries WHERE provider = '" + PROVIDER + "'");
    }

    @Test
    void given_anchor_when_events_straddle_then_bucketed_by_event_ts() {
        price("m-usd", "0.000001", "USD");
        report("evt-before", T1, FLIP, "m-usd", 1000);  // ts < 锚 → 获客成本
        report("evt-at-anchor", T2, FLIP, "m-usd", 500); // ts == 锚 → 用户费用（边界归后桶）
        report("evt-after", T3, FLIP, "m-usd", 1500);   // ts > 锚 → 用户费用
        report("evt-trial", T1, TRIAL, "m-usd", 2000);  // 无锚 → 全量获客成本

        GlobalUsageSummary global = usageEventAggregations.aggregateGlobal(null, null,
                Map.of(FLIP, T2));

        // 全局两桶互补不重叠：获客＝1000+2000=3000（$0.003）、用户费用＝500+1500=2000（$0.002）
        assertThat(global.byBucket()).hasSize(2);
        CostBucketUsage acquisition = bucketOf(global.byBucket(), CostBucket.ACQUISITION);
        assertThat(acquisition.tokens()).isEqualTo(new TokenUsage(3000, 0, 0, 0, 0));
        assertThat(acquisition.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.003"));
        CostBucketUsage userFee = bucketOf(global.byBucket(), CostBucket.USER_FEE);
        assertThat(userFee.tokens()).isEqualTo(new TokenUsage(2000, 0, 0, 0, 0));
        assertThat(userFee.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.002"));
        // 并集＝全局口径
        assertThat(global.total()).isEqualTo(new TokenUsage(5000, 0, 0, 0, 0));
        assertThat(global.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.005"));

        // 行级同口径：翻转行两桶、试用行单桶全量获客
        Map<String, SubjectCostSummary> bySubject = subjectMap(
                usageEventAggregations.aggregateSubjectCosts(null, null, Map.of(FLIP, T2)));
        assertThat(bySubject.get(FLIP).byBucket()).hasSize(2);
        assertThat(bucketOf(bySubject.get(FLIP).byBucket(), CostBucket.ACQUISITION).tokens())
                .isEqualTo(new TokenUsage(1000, 0, 0, 0, 0));
        assertThat(bucketOf(bySubject.get(FLIP).byBucket(), CostBucket.USER_FEE).tokens())
                .isEqualTo(new TokenUsage(2000, 0, 0, 0, 0));
        assertThat(bySubject.get(TRIAL).byBucket()).hasSize(1);
        CostBucketUsage trialBucket = bySubject.get(TRIAL).byBucket().get(0);
        assertThat(trialBucket.bucket()).isEqualTo(CostBucket.ACQUISITION);
        assertThat(trialBucket.tokens()).isEqualTo(new TokenUsage(2000, 0, 0, 0, 0));
        assertThat(trialBucket.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.002"));
    }

    @Test
    void given_empty_or_missing_anchor_when_query_then_all_acquisition_bucket() {
        price("m-usd", "0.000001", "USD");
        report("evt-a", T1, FLIP, "m-usd", 1000);
        report("evt-b", T3, TRIAL, "m-usd", 2000);

        // 空锚集：全量获客成本桶（无翻转行环境/锚集外的兜底口径）
        GlobalUsageSummary global = usageEventAggregations.aggregateGlobal(null, null, Map.of());
        assertThat(global.byBucket()).hasSize(1);
        assertThat(global.byBucket().get(0).bucket()).isEqualTo(CostBucket.ACQUISITION);
        assertThat(global.byBucket().get(0).tokens()).isEqualTo(new TokenUsage(3000, 0, 0, 0, 0));
        assertThat(global.byBucket().get(0).cost().get(USD))
                .isEqualByComparingTo(new BigDecimal("0.003"));

        // 锚集不覆盖的 subject（缺席＝未翻转）全量落获客成本桶
        Map<String, SubjectCostSummary> bySubject = subjectMap(
                usageEventAggregations.aggregateSubjectCosts(null, null, Map.of("other", T2)));
        assertThat(bySubject.get(FLIP).byBucket()).hasSize(1);
        assertThat(bySubject.get(FLIP).byBucket().get(0).bucket()).isEqualTo(CostBucket.ACQUISITION);
        assertThat(bySubject.get(FLIP).byBucket().get(0).tokens())
                .isEqualTo(new TokenUsage(1000, 0, 0, 0, 0));
    }

    @Test
    void given_unpriced_usage_when_query_then_bucket_tokens_include_all_cost_priced_only() {
        // 锚后事件无价：用户费用桶 tokens 照含（用量归桶不看价）、cost 空（无价分量
        // 不伪装 0——与 unpriced 观测面互补，桶内口径与全局 cost 同律）
        price("m-usd", "0.000001", "USD");
        report("evt-priced-before", T1, FLIP, "m-usd", 1000);
        report("evt-unpriced-after", T3, FLIP, "m-none", new TokenUsage(700, 300, 0, 0, 0));

        GlobalUsageSummary global = usageEventAggregations.aggregateGlobal(null, null,
                Map.of(FLIP, T2));

        CostBucketUsage acquisition = bucketOf(global.byBucket(), CostBucket.ACQUISITION);
        assertThat(acquisition.tokens()).isEqualTo(new TokenUsage(1000, 0, 0, 0, 0));
        assertThat(acquisition.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.001"));
        CostBucketUsage userFee = bucketOf(global.byBucket(), CostBucket.USER_FEE);
        assertThat(userFee.tokens()).isEqualTo(new TokenUsage(700, 300, 0, 0, 0));
        assertThat(userFee.cost()).isEmpty();
    }

    @Test
    void given_window_when_query_then_buckets_scoped_to_window() {
        price("m-usd", "0.000001", "USD");
        report("evt-before", T1, FLIP, "m-usd", 1000);
        report("evt-at-anchor", T2, FLIP, "m-usd", 500);
        report("evt-after", T3, FLIP, "m-usd", 1500);

        // [T0, T2)：锚后事件全在窗外 → 用户费用桶缺席（用量驱动，不伪装 0）
        GlobalUsageSummary windowed = usageEventAggregations.aggregateGlobal(T0, T2,
                Map.of(FLIP, T2));
        assertThat(windowed.byBucket()).hasSize(1);
        assertThat(windowed.byBucket().get(0).bucket()).isEqualTo(CostBucket.ACQUISITION);
        assertThat(windowed.byBucket().get(0).tokens()).isEqualTo(new TokenUsage(1000, 0, 0, 0, 0));

        // 行级同窗口收窄
        Map<String, SubjectCostSummary> bySubject = subjectMap(
                usageEventAggregations.aggregateSubjectCosts(T0, T2, Map.of(FLIP, T2)));
        assertThat(bySubject.get(FLIP).byBucket()).hasSize(1);
        assertThat(bySubject.get(FLIP).byBucket().get(0).bucket()).isEqualTo(CostBucket.ACQUISITION);
    }

    // ---------- 夹具 ----------

    private void price(String model, String unitPrice, String currency) {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, model,
                TokenKind.INPUT, new BigDecimal(unitPrice), currency, T0, null));
    }

    private void report(String eventId, Instant ts, String subject, String model, long inputTokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, subject, "run-1", "session-1",
                PROVIDER, model, Map.of(), new TokenUsage(inputTokens, 0, 0, 0, 0)));
    }

    private void report(String eventId, Instant ts, String subject, String model,
                        TokenUsage tokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, subject, "run-1", "session-1",
                PROVIDER, model, Map.of(), tokens));
    }

    private static CostBucketUsage bucketOf(List<CostBucketUsage> buckets, CostBucket bucket) {
        return buckets.stream()
                .filter(usage -> usage.bucket() == bucket)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺桶 " + bucket));
    }

    private static Map<String, SubjectCostSummary> subjectMap(List<SubjectCostSummary> rows) {
        return rows.stream().collect(Collectors.toMap(
                SubjectCostSummary::subject, row -> row));
    }
}
