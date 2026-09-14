package com.aieducenter.aiplatform.base.metering.endpoints.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.metering.domain.repository.PriceEntryRepository;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #161 平台成本读面①在 {@code #152} seam 上全绿：真过滤链（签名闸/会话豁免/
 * 强制拦截器）＋真应用服务＋aiplatform_test 真库。单价夹具用独立 provider
 * {@code backoffice-cost}，与他类测试互不沾（启动种子已随 #165 退役，单价行
 * 一律测试自持夹具供给）。
 *
 * <p>总览/unpriced 是<b>全局聚合</b>（无 subject 过滤），本类对 met_usage_events
 * 全表清理（先例：MeteringCostAggregationTest teardown 同款）——任何残留事件
 * 都会污染全局和数，夹具前后各清一次保证确定性。</p>
 *
 * <p>#161 验收口径逐条钉死：</p>
 * <ul>
 * <li><b>全局总览</b>：跨 subject 聚合、时间窗半开边界（from 含/to 不含）、
 * 跨改价区间事件各按各时点单价（与 bySubject 口径一致）、币种分桶不折算；</li>
 * <li><b>unpriced 警示</b>：用量驱动（窗口内有用量且时点无价才报）、按档位汇总
 * token（只计无价分量）、已配价档位不出现、窗口外用量不报；</li>
 * <li><b>空窗口/无数据</b>：全零 total＋空分桶＋空清单不炸；</li>
 * <li><b>参数负例</b>：from/to 非 ISO-8601 Instant 400 METER_011。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeCostSeamTest {

    /** 夹具 provider/subjects：与他类测试互不沾。 */
    private static final String PROVIDER = "backoffice-cost";
    private static final String SUBJ_A = "cost-subj-a";
    private static final String SUBJ_B = "cost-subj-b";

    /** 夹具时间锚：T0…T4 逐日，窗口/改价边界都以这些整点切。 */
    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-03T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-07-04T00:00:00Z");
    private static final Instant T4 = Instant.parse("2026-07-05T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUsageEvents() {
        // 全局聚合的断言面是全表和数：事件表前后全清（单价行只清本类 provider，
        // 他类测试的单价夹具不动）
        jdbcTemplate.update("DELETE FROM met_usage_events");
        jdbcTemplate.update("DELETE FROM met_price_entries WHERE provider = ?", PROVIDER);
    }

    // ---------- 全局总览：跨 subject 聚合＋币种分桶＋byModel/byAgentKind ----------

    @Test
    void given_priced_usage_across_subjects_when_signed_overview_then_global_buckets()
            throws Exception {
        // 两模型两币种：USD 模型跨两个 subject 用量、CNY 模型单 subject
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-usd",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-cny",
                TokenKind.INPUT, new BigDecimal("0.000002"), "CNY", T0, null));
        report("evt-a1", T1, SUBJ_A, "m-usd", Map.of("agentKind", "main"), 1000);
        report("evt-a2", T1, SUBJ_A, "m-cny", Map.of("agentKind", "executor"), 1000);
        report("evt-b1", T2, SUBJ_B, "m-usd", Map.of(), 3000); // 无维度事件

        // 无窗口（全量）：总览是跨 subject 全局口径
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/overview"), "/api/backoffice/costs/overview", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.from").value(nullValue()))
                .andExpect(jsonPath("$.data.to").value(nullValue()))
                // 总量跨 subject：(1000 + 1000 + 3000) input
                .andExpect(jsonPath("$.data.total.input").value(5000))
                // 币种分桶直读不折算：USD=(1000+3000)×$1/1M=$0.004、CNY=1000×$2/1M=$0.002
                .andExpect(jsonPath("$.data.cost.USD").value(0.004))
                .andExpect(jsonPath("$.data.cost.CNY").value(0.002))
                // 分模型（provider/model 码序）：m-cny 1000、m-usd 4000
                .andExpect(jsonPath("$.data.byModel", hasSize(2)))
                .andExpect(jsonPath("$.data.byModel[0].model").value("m-cny"))
                .andExpect(jsonPath("$.data.byModel[0].tokens.input").value(1000))
                .andExpect(jsonPath("$.data.byModel[1].model").value("m-usd"))
                .andExpect(jsonPath("$.data.byModel[1].tokens.input").value(4000))
                // 分智能体（dims.agentKind 原值、码序）：无维度事件不参与该分桶
                .andExpect(jsonPath("$.data.byAgentKind", hasSize(2)))
                .andExpect(jsonPath("$.data.byAgentKind[0].agentKind").value("executor"))
                .andExpect(jsonPath("$.data.byAgentKind[0].tokens.input").value(1000))
                .andExpect(jsonPath("$.data.byAgentKind[1].agentKind").value("main"))
                .andExpect(jsonPath("$.data.byAgentKind[1].tokens.input").value(1000));
    }

    @Test
    void given_half_open_window_when_overview_then_boundary_events_scoped() throws Exception {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-w",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        // T1=from（含）、T2 窗中、T3=to（不含）、T4 窗外
        report("evt-at-from", T1, SUBJ_A, "m-w", Map.of("agentKind", "main"), 1000);
        report("evt-mid", T2, SUBJ_A, "m-w", Map.of(), 1000);
        report("evt-at-to", T3, SUBJ_A, "m-w", Map.of(), 1000);
        report("evt-beyond", T4, SUBJ_A, "m-w", Map.of(), 1000);

        String path = "/api/backoffice/costs/overview?from=" + T1 + "&to=" + T3;
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/overview")
                                .queryParam("from", T1.toString())
                                .queryParam("to", T3.toString()),
                        path, null))
                .andExpect(status().isOk())
                // [T1, T3) 半开：T1 含、T3 不含——窗内只有前两事件
                .andExpect(jsonPath("$.data.from").value(T1.toString()))
                .andExpect(jsonPath("$.data.to").value(T3.toString()))
                .andExpect(jsonPath("$.data.total.input").value(2000))
                .andExpect(jsonPath("$.data.cost.USD").value(0.002))
                .andExpect(jsonPath("$.data.byModel[0].tokens.input").value(2000));
    }

    @Test
    void given_price_change_mid_window_when_overview_then_each_event_at_its_time()
            throws Exception {
        // 改价 = 关旧行开新行：$1/1M 生效 [T0, T2)，$2/1M 自 T2 起
        PriceEntry oldRow = priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-pc",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        oldRow.close(T2);
        priceEntryRepository.save(oldRow);
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-pc",
                TokenKind.INPUT, new BigDecimal("0.000002"), "USD", T2, null));
        report("evt-old-price", T1, SUBJ_A, "m-pc", Map.of(), 1000);
        report("evt-new-price", T3, SUBJ_B, "m-pc", Map.of(), 1000);

        String path = "/api/backoffice/costs/overview?from=" + T0 + "&to=" + T4;
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/overview")
                                .queryParam("from", T0.toString())
                                .queryParam("to", T4.toString()),
                        path, null))
                .andExpect(status().isOk())
                // 各按各时点单价（跨 subject 同口径）：1000×$1/1M + 1000×$2/1M = $0.003
                .andExpect(jsonPath("$.data.cost.USD").value(0.003))
                .andExpect(jsonPath("$.data.total.input").value(2000));

        // 已配价（含改价区间衔接）→ unpriced 无警示
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/unpriced"), "/api/backoffice/costs/unpriced", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    // ---------- 混合场景：部分有价部分无价，cost 与 unpriced 互补不重叠 ----------

    @Test
    void given_mixed_priced_and_unpriced_when_query_then_cost_and_unpriced_split()
            throws Exception {
        // 只有 m-ok 配了价；m-none 完全无价（input+output 两档有用量）
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-ok",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        report("evt-ok", T1, SUBJ_A, "m-ok", Map.of("agentKind", "main"), 1000);
        report("evt-none", T1, SUBJ_A, "m-none", Map.of("agentKind", "executor"),
                new TokenUsage(700, 300, 0, 0, 0));

        // 总览：已配价分量照算（不因部分缺价阻断），未配价分量不进 cost（不伪装 0）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/overview"), "/api/backoffice/costs/overview", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cost.USD").value(0.001))
                .andExpect(jsonPath("$.data.total.input").value(1700))
                .andExpect(jsonPath("$.data.total.output").value(300))
                .andExpect(jsonPath("$.data.byModel", hasSize(2)));

        // unpriced 警示：只报无价档位并按档位汇总 token；已配价的 m-ok 不出现
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/unpriced"), "/api/backoffice/costs/unpriced", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].provider").value(PROVIDER))
                .andExpect(jsonPath("$.data.items[0].model").value("m-none"))
                .andExpect(jsonPath("$.data.items[0].tokenKind").value(1))
                .andExpect(jsonPath("$.data.items[0].tokenKindName").value("输入"))
                .andExpect(jsonPath("$.data.items[0].tokens").value(700))
                .andExpect(jsonPath("$.data.items[1].tokenKind").value(2))
                .andExpect(jsonPath("$.data.items[1].tokenKindName").value("输出"))
                .andExpect(jsonPath("$.data.items[1].tokens").value(300));
    }

    @Test
    void given_tier_partially_priced_when_unpriced_then_only_unpriced_portion_summed()
            throws Exception {
        // 单价自 T2 起才生效：T1 事件无价、T3 事件有价——同档位只计无价分量
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-late",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T2, null));
        report("evt-before-price", T1, SUBJ_A, "m-late", Map.of(), 1000);
        report("evt-after-price", T3, SUBJ_A, "m-late", Map.of(), 1000);

        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/unpriced"), "/api/backoffice/costs/unpriced", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].model").value("m-late"))
                .andExpect(jsonPath("$.data.items[0].tokenKind").value(1))
                .andExpect(jsonPath("$.data.items[0].tokens").value(1000));

        // 互补不重叠：cost 只含有价的 T3 分量（1000×$1/1M）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/overview"), "/api/backoffice/costs/overview", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cost.USD").value(0.001));
    }

    // ---------- 空窗口/无数据：空结构不炸＋unpriced 窗口收窄 ----------

    @Test
    void given_no_data_or_zero_width_window_when_query_then_empty_structures() throws Exception {
        // 无任何事件：全零 total＋空分桶＋空清单
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/overview"), "/api/backoffice/costs/overview", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total.input").value(0))
                .andExpect(jsonPath("$.data.cost").isEmpty())
                .andExpect(jsonPath("$.data.byModel").isEmpty())
                .andExpect(jsonPath("$.data.byAgentKind").isEmpty());
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/unpriced"), "/api/backoffice/costs/unpriced", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        // 零宽窗（from==to）＋窗口外有未配价用量：同空结构、不报（unpriced 窗口收窄）
        report("evt-outside", T4, SUBJ_A, "m-none", Map.of(), 500);
        String overviewPath = "/api/backoffice/costs/overview?from=" + T2 + "&to=" + T2;
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/overview")
                                .queryParam("from", T2.toString())
                                .queryParam("to", T2.toString()),
                        overviewPath, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total.input").value(0))
                .andExpect(jsonPath("$.data.cost").isEmpty());
        String unpricedPath = "/api/backoffice/costs/unpriced?from=" + T0 + "&to=" + T2;
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/unpriced")
                                .queryParam("from", T0.toString())
                                .queryParam("to", T2.toString()),
                        unpricedPath, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    // ---------- 参数负例：非 ISO-8601 Instant → 400 METER_011（#164 消息泛化） ----------

    @Test
    void given_bad_window_param_when_query_then_400_meter_011() throws Exception {
        String overviewPath = "/api/backoffice/costs/overview?from=not-a-date";
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/overview").queryParam("from", "not-a-date"),
                        overviewPath, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无效的成本查询参数"));

        String unpricedPath = "/api/backoffice/costs/unpriced?to=2026-09-32T00:00:00Z";
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/costs/unpriced")
                                .queryParam("to", "2026-09-32T00:00:00Z"),
                        unpricedPath, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无效的成本查询参数"));
    }

    // ---------- 夹具 ----------

    private void report(String eventId, Instant ts, String subject, String model,
                        Map<String, String> dims, long inputTokens) {
        report(eventId, ts, subject, model, dims, new TokenUsage(inputTokens, 0, 0, 0, 0));
    }

    private void report(String eventId, Instant ts, String subject, String model,
                        Map<String, String> dims, TokenUsage tokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, subject, "run-1", "session-1",
                PROVIDER, model, dims, tokens));
    }
}
