package com.semgravio.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.semgravio.aiplatform.base.metering.domain.aggregate.TimeBandRule;
import com.semgravio.aiplatform.base.metering.domain.enums.Band;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;
import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.model.UsageSummary;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;
import com.semgravio.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.semgravio.aiplatform.base.metering.domain.repository.PriceEntryRepository;
import com.semgravio.aiplatform.base.metering.domain.repository.TimeBandRuleRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * vendor 计量双维度与单价匹配键迁移（#558，spec #552-T4，ADR-0053 ②）：单价表与
 * 峰谷规则的匹配键自通道键（provider）改挂供应商（vendor）——同 vendor 多账号
 * （通道行）事件共用供应商级定价（新账号行零价格复制）；模型视角聚合
 * （byModel）按 vendor+model 跨账号合并；历史事件（vendor 回填＝provider 同值）
 * 换算结果不漂移。经端口全链路（sink 落库 → 原生 SQL 换算），事件/单价/时段规则
 * 走真实库。
 *
 * <p>fixture 用独立 vendor {@code vtest-ds}（账号行 {@code vtest-ds}/{@code vtest-ds-2}
 * ——deepseek/deepseek-2 主备形态的测试形），与他类测试互不沾。</p>
 */
@IntegrationTest
class MeteringVendorAggregationTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-03T00:00:00Z");

    /** 供应商（＝单价/时段规则匹配键）。 */
    private static final String VENDOR = "vtest-ds";
    /** 账号 A 通道行（vendor 同值——V79 历史回填形态）。 */
    private static final String ACCT_A = "vtest-ds";
    /** 账号 B 通道行（vendor ≠ 通道键——新账号行零价格复制形态）。 */
    private static final String ACCT_B = "vtest-ds-2";

    private static final String SUBJ = "vendor-proj";
    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private UsageQueryPort usageQueryPort;

    @Autowired
    private BackofficeCostAppService costAppService;

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private TimeBandRuleRepository timeBandRuleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE vendor LIKE 'vtest-ds%'");
        jdbcTemplate.update("DELETE FROM met_price_entries WHERE provider = '" + VENDOR + "'");
        jdbcTemplate.update("DELETE FROM met_time_band_rules WHERE provider = '" + VENDOR + "'");
    }

    @Test
    void given_vendor_price_rows_when_events_from_two_accounts_then_both_priced_and_merged_by_model() {
        // 供应商级单价行一组（只挂 vendor，不复制到账号 B 通道键）
        priceEntryRepository.save(PriceEntry.open(VENDOR, "model-x",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));

        // 账号 A 事件＝历史回填形态（vendor 与 provider 同值）——迁移前后换算结果
        // 一致（不漂移）的锚腿；账号 B 事件＝vendor ≠ 通道键（failover 切备账号后
        // 按实际命中通道行归因的形态）
        report("evt-acct-a", T1, ACCT_A, VENDOR, "model-x", new TokenUsage(1000, 0, 0, 0, 0));
        report("evt-acct-b", T2, ACCT_B, VENDOR, "model-x", new TokenUsage(3000, 0, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 两账号分量都按 vendor 命中同组单价行：1000×$1/M + 3000×$1/M = $0.004
        assertThat(summary.cost()).containsOnlyKeys(USD);
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.004"));
        // 账号 B 事件无缺价状态（供应商定价覆盖——新账号行零价格复制）
        assertThat(summary.unpriced()).isEmpty();
        // 模型视角聚合（vendor+model）：跨账号同名模型合并为一桶
        assertThat(summary.byModel()).containsExactly(new UsageSummary.ModelUsage(
                VENDOR, "model-x", new TokenUsage(4000, 0, 0, 0, 0)));
    }

    @Test
    void given_no_price_rows_when_events_from_backup_account_then_unpriced_lists_vendor_key() {
        // 运营补价面（unpriced 警示）按 vendor 列键——不是账号通道键：补价动作的
        // 匹配对象＝供应商级单价行
        report("evt-acct-b", T1, ACCT_B, VENDOR, "model-y", new TokenUsage(100, 0, 0, 0, 0));

        var unpriced = costAppService.unpriced(null, null);

        assertThat(unpriced.items()).singleElement()
                .satisfies(tier -> {
                    assertThat(tier.provider()).isEqualTo(VENDOR);
                    assertThat(tier.model()).isEqualTo("model-y");
                    assertThat(tier.tokenKind()).isEqualTo(TokenKind.INPUT.getCode());
                });
    }

    @Test
    void given_vendor_band_rules_when_event_from_backup_account_then_banded_via_vendor() {
        // 峰谷时段规则同样挂 vendor：全周全天峰窗（dayMask 127、00:00–24:00）——
        // 任意 ts 归峰带
        timeBandRuleRepository.save(TimeBandRule.open(VENDOR, Band.PEAK, 127, 0, 1440, T0, null));
        priceEntryRepository.save(PriceEntry.open(VENDOR, "model-z",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        priceEntryRepository.save(PriceEntry.open(VENDOR, "model-z", TokenKind.INPUT, Band.PEAK,
                new BigDecimal("0.000002"), "USD", T0, null));

        // 账号 B 事件经 vendor 命中峰带价（有规则行后 NULL 带行不再参与匹配）
        report("evt-acct-b-peak", T1, ACCT_B, VENDOR, "model-z", new TokenUsage(1000, 0, 0, 0, 0));

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 峰带价 $2/M × 1000 = $0.002（不是 NULL 带行 $1/M 的 $0.001）
        assertThat(summary.cost()).containsOnlyKeys(USD);
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.002"));
        assertThat(summary.unpriced()).isEmpty();
    }

    /** 直报事件（vendor 显式——模型边界的计量归因腿）。 */
    private void report(String eventId, Instant ts, String provider, String vendor, String model,
            TokenUsage tokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, SUBJ, "run-" + eventId, "session-1",
                provider, vendor, model, null, tokens, 0, 0));
    }
}
