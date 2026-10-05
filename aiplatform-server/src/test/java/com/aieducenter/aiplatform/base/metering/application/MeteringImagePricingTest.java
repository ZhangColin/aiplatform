package com.aieducenter.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.aieducenter.aiplatform.base.metering.domain.repository.PriceEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按张计价集成测试（#288 图片生成计量与模型调用并列）：按张事件走 IMAGE 档位行
 * （成本 = 张数 × 事件时点生效单价、token 五档为零）、未配价标注与改价语义对
 * IMAGE 行同一套、token 总量不受按张事件混入。经端口全链路（sink → 落库 → 原生
 * SQL 档位展开换算），事件与单价行走真实库。fixture 用独立 provider
 * {@code imgprov}，与他类测试互不沾。
 */
@IntegrationTest
class MeteringImagePricingTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-03T00:00:00Z");

    private static final String SUBJ = "img-proj";
    private static final String PROVIDER = "imgprov";
    private static final String IMAGE_MODEL = "qwen-image";
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
    void given_image_events_with_price_row_when_aggregate_then_cost_is_images_times_unit_price() {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, IMAGE_MODEL,
                TokenKind.IMAGE, new BigDecimal("0.18"), "CNY", T0, null));

        reportImage("img-evt-1", T1, 2);
        reportImage("img-evt-2", T1, 1);

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 3 张 × ¥0.18 = ¥0.54（按张档位行盖住全部张数，token 五档为零不掺和）
        assertThat(summary.cost()).containsOnlyKeys(CNY);
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.54"));
        // 按张事件不进 token 总量（张与 token 并列不混算）
        assertThat(summary.total()).isEqualTo(TokenUsage.ZERO);
        assertThat(summary.unpriced()).isEmpty();
    }

    @Test
    void given_image_event_without_price_row_when_aggregate_then_unpriced_marks_image_tier() {
        reportImage("img-evt-3", T1, 1);

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 无 IMAGE 行：分量不进 cost（不伪装 0）、按张档进 unpriced 标注
        assertThat(summary.cost()).isEmpty();
        assertThat(summary.unpriced()).hasSize(1);
        assertThat(summary.unpriced().get(0).provider()).isEqualTo(PROVIDER);
        assertThat(summary.unpriced().get(0).model()).isEqualTo(IMAGE_MODEL);
        assertThat(summary.unpriced().get(0).tokenKind()).isEqualTo(TokenKind.IMAGE);
    }

    @Test
    void given_price_change_when_image_events_straddle_boundary_then_each_priced_at_its_time() {
        // 改价 = 关旧行开新行（开行/改价/关行语义对 IMAGE 行零改）：¥0.18 生效 [T0, T2)、¥0.25 自 T2
        PriceEntry oldRow = priceEntryRepository.save(PriceEntry.open(PROVIDER, IMAGE_MODEL,
                TokenKind.IMAGE, new BigDecimal("0.18"), "CNY", T0, null));
        oldRow.close(T2);
        priceEntryRepository.save(oldRow);
        priceEntryRepository.save(PriceEntry.open(PROVIDER, IMAGE_MODEL,
                TokenKind.IMAGE, new BigDecimal("0.25"), "CNY", T2, null));

        reportImage("img-evt-4", T1, 1);  // 旧价时段
        reportImage("img-evt-5", T2, 1);  // 新价时段

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 各按各时点单价：1×0.18 + 1×0.25 = 0.43（历史成本不随改价漂移）
        assertThat(summary.cost()).containsOnlyKeys(CNY);
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("0.43"));
    }

    @Test
    void given_mixed_token_and_image_events_when_aggregate_then_both_dimensions_priced() {
        // 同 provider 不同 model：token 模型走 INPUT 行、图片模型走 IMAGE 行，两量同桶相加
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "chat-model",
                TokenKind.INPUT, new BigDecimal("0.000001"), "CNY", T0, null));
        priceEntryRepository.save(PriceEntry.open(PROVIDER, IMAGE_MODEL,
                TokenKind.IMAGE, new BigDecimal("0.18"), "CNY", T0, null));

        usageEventSink.report(new UsageEvent("tok-evt-1", T1, SUBJ, "run-1", "session-1",
                PROVIDER, "chat-model", Map.of(), new TokenUsage(1_000_000, 0, 0, 0, 0)));
        reportImage("img-evt-6", T1, 1);

        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);

        // 1M token × ¥1/M + 1 张 × ¥0.18 = ¥1.18（token 档位与按张档位同一公式）
        assertThat(summary.cost()).containsOnlyKeys(CNY);
        assertThat(summary.cost().get(CNY)).isEqualByComparingTo(new BigDecimal("1.18"));
        assertThat(summary.total()).isEqualTo(new TokenUsage(1_000_000, 0, 0, 0, 0));
    }

    /** 按张事件上报（token 五档为零、张数进 images；dims 带设计执行体口径）。 */
    private void reportImage(String eventId, Instant ts, long images) {
        usageEventSink.report(new UsageEvent(eventId, ts, SUBJ, "run-1", "session-1",
                PROVIDER, IMAGE_MODEL, Map.of("agentKind", "designer"), TokenUsage.ZERO, images));
    }
}
