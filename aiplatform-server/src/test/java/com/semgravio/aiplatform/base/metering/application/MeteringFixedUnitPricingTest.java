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
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;
import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageSummary;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.port.UsageEventSink;
import com.semgravio.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.semgravio.aiplatform.base.metering.domain.repository.PriceEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定单价档计价集成测试（#531 计费①，ADR-0047 固定单价项计量面）：固定件事件
 * 走 UNIT 档位行（成本 = 件数 × 事件时点生效单价、token 五档为零）、未配价标注
 * 对 UNIT 行同一套、token 总量不受固定件事件混入。先例＝
 * {@code MeteringImagePricingTest}（#288 按张档同构）。经端口全链路（sink → 落库
 * → 原生 SQL 档位展开换算），事件与单价行走真实库。fixture 用独立 provider
 * {@code fixprov}（联网搜索供数方形态），与他类测试互不沾。
 */
@IntegrationTest
class MeteringFixedUnitPricingTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-02T00:00:00Z");

    private static final String SUBJ = "fix-proj";
    private static final String PROVIDER = "fixprov";
    private static final String SEARCH_MODEL = "web-search";
    private static final Currency CNY = Currency.getInstance("CNY");

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private UsageQueryPort usageQueryPort;

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
    void given_fixed_unit_events_with_price_row_when_aggregate_then_cost_is_units_times_unit_price() {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, SEARCH_MODEL,
                TokenKind.UNIT, new BigDecimal("0.02"), "CNY", T0, null));

        reportFixed("fix-evt-1", T1, 1);
        reportFixed("fix-evt-2", T1, 3);

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 4 件 × ¥0.02 = ¥0.08（固定档位行盖住全部件数，token 五档为零不掺和）
        assertThat(summary.cost()).containsOnlyKeys(CNY);
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.08"));
        // 固定件事件不进 token 总量（件与 token 并列不混算）
        assertThat(summary.total()).isEqualTo(TokenUsage.ZERO);
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_fixed_unit_event_without_price_row_when_aggregate_then_unpriced_marks_unit_tier() {
        reportFixed("fix-evt-3", T1, 2);

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 无 UNIT 行：分量不进 cost（不伪装 0）、固定档进 unpriced 标注
        assertThat(summary.cost()).isEmpty();
        assertThat(summary.unpriced()).hasSize(1);
        assertThat(summary.unpriced().get(0).provider()).isEqualTo(PROVIDER);
        assertThat(summary.unpriced().get(0).model()).isEqualTo(SEARCH_MODEL);
        assertThat(summary.unpriced().get(0).tokenKind()).isEqualTo(TokenKind.UNIT);
    }

    @Test
    void given_mixed_token_and_fixed_events_when_aggregate_then_both_dimensions_priced() {
        // 同 provider 不同 model：token 模型走 OUTPUT 行、搜索模型走 UNIT 行，两量同桶相加
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "chat-model",
                TokenKind.OUTPUT, new BigDecimal("0.000002"), "CNY", T0, null));
        priceEntryRepository.save(PriceEntry.open(PROVIDER, SEARCH_MODEL,
                TokenKind.UNIT, new BigDecimal("0.02"), "CNY", T0, null));

        usageEventSink.report(new UsageEvent("tok-evt-1", T1, SUBJ, "run-1", "session-1",
                PROVIDER, "chat-model", Map.of("agentKind", "main"),
                new TokenUsage(0, 500_000, 0, 0, 0)));
        reportFixed("fix-evt-4", T1, 2);

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 0.5M 输出 × ¥2/M + 2 件 × ¥0.02 = ¥1.04（token 档位与固定档位同一公式）
        assertThat(summary.cost()).containsOnlyKeys(CNY);
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("1.04"));
        assertThat(summary.total()).isEqualTo(new TokenUsage(0, 500_000, 0, 0, 0));
    }

    /** 固定件事件上报（token 五档为零、件数进 units；dims 带主智能体口径——联网搜索是主智能体工具）。 */
    private void reportFixed(String eventId, Instant ts, long units) {
        usageEventSink.report(new UsageEvent(eventId, ts, SUBJ, "run-1", "session-1",
                PROVIDER, SEARCH_MODEL, Map.of("agentKind", "main"), TokenUsage.ZERO, 0, units));
    }
}
