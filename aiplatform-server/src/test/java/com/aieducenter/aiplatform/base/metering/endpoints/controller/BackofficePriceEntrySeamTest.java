package com.aieducenter.aiplatform.base.metering.endpoints.controller;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.aieducenter.aiplatform.base.metering.domain.repository.PriceEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单价表管理写口在 {@code #152} seam 上全绿：真过滤链（签名闸/会话豁免/
 * 强制拦截器）＋真应用服务＋aiplatform_test 真库。单价夹具用独立 provider
 * {@code backoffice-prov}，与启动种子无关（种子已随 #165 退役——初始化走
 * 幂等签名脚本，本类断言面不受其影响）。
 *
 * <p>#160 三操作＋#165 开行＋校验＋留痕在本类一链钉死：</p>
 * <ul>
 * <li><b>行清单</b>：含现行与历史行、provider/model 过滤、生效起点倒序、
 * 分页与非法分页值 METER_009；</li>
 * <li><b>开行</b>（#165 种子脚本通道）：空键首行可开、effectiveFrom 可回溯
 * （种子敞口 2026-01-01 覆盖存量事件）；重叠校验同改价口径；</li>
 * <li><b>原子改价</b>：关行＋开新行同事务落库（JdbcTemplate）——重叠/守卫负例
 * 下两行都不动；未来生效起点经聚合口径验证（窗口前旧价、窗口后新价）；
 * 操作者两列落新行（无头落空）；</li>
 * <li><b>停用</b>：即时关行不接新行，此后窗口用量进 unpriced（聚合口径验证）；
 * 停用操作者落被关行（停用唯一落点）；预发布行停用钳成空区间（从未生效）。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficePriceEntrySeamTest {

    /** 夹具 provider/subject：与种子行、他类测试互不沾。 */
    private static final String PROVIDER = "backoffice-prov";
    private static final String SUBJ = "backoffice-cost-subj";
    private static final Currency USD = Currency.getInstance("USD");

    /** 夹具时间锚：历史区间起点（远过去）／历史区间终点（过去）。 */
    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-01T00:00:00Z");

    /** 操作者测试身份：admin 侧管理员的 TSID＋昵称样例（同订单 seam 口径）。 */
    private static final String OPERATOR_ID = "700160";
    private static final String OPERATOR_NAME = "运营·单价管理员";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private UsageQueryPort usageQueryPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE subject = ?", SUBJ);
        jdbcTemplate.update("DELETE FROM met_price_entries WHERE provider = ?", PROVIDER);
    }

    // ---------- 行清单：含历史行＋过滤＋排序＋分页 ----------

    @Test
    void given_rows_with_history_when_signed_list_then_current_and_history_with_filters()
            throws Exception {
        // m-a 有改价史（历史行＋当前行），m-b 只有当前行；库植行＝存量形制（操作者空）
        PriceEntry historical = plantClosedRow("m-a", T0, T2, "0.000001");
        PriceEntry current = plantOpenRow("m-a", T2, "0.000002");
        plantOpenRow("m-b", T0, "0.000003");

        // provider 过滤：全量三行（种子 deepseek 行被排除在断言面外）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/price-entries").queryParam("provider", PROVIDER),
                        "/api/backoffice/price-entries?provider=" + PROVIDER, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.total").value("3"));

        // provider+model 过滤：仅 m-a 两行，生效起点倒序（当前行在前）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/price-entries")
                                .queryParam("provider", PROVIDER)
                                .queryParam("model", "m-a"),
                        "/api/backoffice/price-entries?provider=" + PROVIDER + "&model=m-a", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.total").value("2"))
                .andExpect(jsonPath("$.data.items[0].id").value(current.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].tokenKind").value(1))
                .andExpect(jsonPath("$.data.items[0].tokenKindName").value("输入"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value("0.000002"))
                .andExpect(jsonPath("$.data.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.data.items[0].effectiveFrom").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].effectiveTo").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].operatorId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].id").value(historical.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].effectiveFrom").value("2026-07-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[1].effectiveTo").value("2026-08-01T00:00:00Z"));

        // 未命中 model：空清单
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/price-entries")
                                .queryParam("provider", PROVIDER)
                                .queryParam("model", "m-none"),
                        "/api/backoffice/price-entries?provider=" + PROVIDER + "&model=m-none",
                        null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));
    }

    @Test
    void given_three_rows_when_paging_then_walked_and_bad_page_param_400() throws Exception {
        plantClosedRow("m-p", T0, T2, "0.000001");
        plantOpenRow("m-p", T2, "0.000002");
        plantOpenRow("m-p2", T0, "0.000003");

        // 分页行走：page 1 size 2 → 2 行 total 3；page 2 → 余 1 行
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/price-entries")
                                .queryParam("provider", PROVIDER)
                                .queryParam("size", "2"),
                        "/api/backoffice/price-entries?provider=" + PROVIDER + "&size=2", null))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total").value("3"));
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/price-entries")
                                .queryParam("provider", PROVIDER)
                                .queryParam("size", "2").queryParam("page", "2"),
                        "/api/backoffice/price-entries?provider=" + PROVIDER + "&size=2&page=2",
                        null))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.page").value(2));

        // 非法分页值：400 METER_009（绑定失败兜底，同 ORD_010 形制）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/price-entries")
                                .queryParam("provider", PROVIDER).queryParam("page", "abc"),
                        "/api/backoffice/price-entries?provider=" + PROVIDER + "&page=abc", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无效的单价行过滤参数"));
    }

    // ---------- 开行（#165：空键首行——种子脚本通道） ----------

    @Test
    void given_no_row_for_key_when_signed_open_with_operator_then_row_persisted()
            throws Exception {
        // 空键首行：写口唯一化到管理 API 后唯一的初始插入通道（种子脚本经此开行）。
        // effectiveFrom 可回溯——种子口径 2026-01-01 敞口覆盖存量事件
        String body = "{\"provider\":\"" + PROVIDER + "\",\"model\":\"m-o1\",\"tokenKind\":1,"
                + "\"unitPrice\":0.00000132,\"currency\":\"USD\","
                + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/price-entries")
                                .contentType(MediaType.APPLICATION_JSON).content(body),
                        "/api/backoffice/price-entries", body)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value(PROVIDER))
                .andExpect(jsonPath("$.data.model").value("m-o1"))
                .andExpect(jsonPath("$.data.tokenKind").value(1))
                .andExpect(jsonPath("$.data.unitPrice").value("0.00000132"))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.effectiveFrom").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.effectiveTo").value(nullValue()))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME));

        // 库内事实（JdbcTemplate）：敞口行落库、操作者两列落值
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT unit_price, effective_from, effective_to, operator_id, operator_name "
                        + "FROM met_price_entries WHERE provider = ? AND model = ?",
                PROVIDER, "m-o1");
        assertThat((BigDecimal) row.get("unit_price"))
                .isEqualByComparingTo(new BigDecimal("0.00000132"));
        assertThat(((Timestamp) row.get("effective_from")).toInstant())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(row.get("effective_to")).isNull();
        assertThat(row.get("operator_id")).isEqualTo(OPERATOR_ID);
        assertThat(row.get("operator_name")).isEqualTo(OPERATOR_NAME);
    }

    @Test
    void given_open_without_operator_headers_then_operator_null() throws Exception {
        // 无头落空口径（同改价）：种子脚本不传操作者头 → 种入行操作者两列 NULL
        String body = "{\"provider\":\"" + PROVIDER + "\",\"model\":\"m-o2\",\"tokenKind\":3,"
                + "\"unitPrice\":0.000000014,\"currency\":\"USD\"}"; // 无 effectiveFrom＝即时

        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/price-entries")
                                .contentType(MediaType.APPLICATION_JSON).content(body),
                        "/api/backoffice/price-entries", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveFrom").isNotEmpty())
                .andExpect(jsonPath("$.data.operatorId").value(nullValue()));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT effective_from, operator_id, operator_name FROM met_price_entries "
                        + "WHERE provider = ? AND model = ?",
                PROVIDER, "m-o2");
        assertThat(((Timestamp) row.get("effective_from")).toInstant())
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(row.get("operator_id")).isNull();
        assertThat(row.get("operator_name")).isNull();
    }

    @Test
    void given_existing_rows_when_open_then_overlap_guarded_and_boundary_allowed()
            throws Exception {
        // 同起点：F＝既有行起点 → 409 METER_008，不落行
        plantOpenRow("m-o3", T0, "0.000001");
        String sameStart = openBody("m-o3", 1, "0.000002", "2026-07-01T00:00:00Z");
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/price-entries")
                                .contentType(MediaType.APPLICATION_JSON).content(sameStart),
                        "/api/backoffice/price-entries", sameStart))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("同匹配键生效区间重叠（跨区间或同起点）"));
        assertThat(rowCountOfKey("m-o3")).isEqualTo(1);

        // 跨区间：F 落既有敞口区间内 → 409，不落行
        String crossOverlap = openBody("m-o3", 1, "0.000002", "2026-09-01T00:00:00Z");
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/price-entries")
                                .contentType(MediaType.APPLICATION_JSON).content(crossOverlap),
                        "/api/backoffice/price-entries", crossOverlap))
                .andExpect(status().isConflict());
        assertThat(rowCountOfKey("m-o3")).isEqualTo(1);

        // 边界：关停后自停用边界点重开（[T0,T2) 关行后开 [T2,∞)）→ 合法，无缝无叠
        plantClosedRow("m-o4", T0, T2, "0.000001");
        String resume = openBody("m-o4", 1, "0.000002", "2026-08-01T00:00:00Z");
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/price-entries")
                                .contentType(MediaType.APPLICATION_JSON).content(resume),
                        "/api/backoffice/price-entries", resume))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveFrom").value("2026-08-01T00:00:00Z"));
        assertThat(rowCountOfKey("m-o4")).isEqualTo(2);

        // 反向越界：F 早于既有已关行终点 → 409（与 [T0,T2) 相交）
        String backtrack = openBody("m-o4", 1, "0.000003", "2026-07-15T00:00:00Z");
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/price-entries")
                                .contentType(MediaType.APPLICATION_JSON).content(backtrack),
                        "/api/backoffice/price-entries", backtrack))
                .andExpect(status().isConflict());
        assertThat(rowCountOfKey("m-o4")).isEqualTo(2);
    }

    @Test
    void given_invalid_open_input_then_400() throws Exception {
        // 字段不完整／负单价／非 ISO 币种：400 METER_004 / METER_010（聚合守卫）；
        // 非法 tokenKind code：绑定失败 400。全部不落行
        for (String body : new String[] {
                "{}",
                "{\"provider\":\"" + PROVIDER + "\"}", // 缺 model/档位/单价/币种
                "{\"provider\":\"" + PROVIDER + "\",\"model\":\"m-g2\",\"tokenKind\":1,"
                        + "\"unitPrice\":-0.000001,\"currency\":\"USD\"}",
                "{\"provider\":\"" + PROVIDER + "\",\"model\":\"m-g2\",\"tokenKind\":1,"
                        + "\"unitPrice\":0.000001,\"currency\":\"MONOPOLY\"}",
                "{\"provider\":\"" + PROVIDER + "\",\"model\":\"m-g2\",\"tokenKind\":99,"
                        + "\"unitPrice\":0.000001,\"currency\":\"USD\"}"}) {
            mockMvc.perform(BackofficeSignatures.signed(
                            post("/api/backoffice/price-entries")
                                    .contentType(MediaType.APPLICATION_JSON).content(body),
                            "/api/backoffice/price-entries", body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(rowCountOfKey("m-g2")).isEqualTo(0);
    }

    // ---------- 原子改价：happy path＋留痕＋落空 ----------

    @Test
    void given_current_row_when_signed_reprice_with_operator_then_close_and_open_persisted()
            throws Exception {
        PriceEntry current = plantOpenRow("m-r", T0, "0.000001");
        String path = "/api/backoffice/price-entries/" + current.getId() + "/reprice";
        String body = "{\"unitPrice\":0.000002,\"currency\":\"USD\"}"; // 无 effectiveFrom＝即时

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closed.id").value(current.getId().toString()))
                .andExpect(jsonPath("$.data.closed.effectiveTo").isNotEmpty())
                .andExpect(jsonPath("$.data.closed.operatorId").value(nullValue()))
                .andExpect(jsonPath("$.data.opened.id").value(
                        org.hamcrest.Matchers.not(current.getId().toString())))
                .andExpect(jsonPath("$.data.opened.unitPrice").value("0.000002"))
                .andExpect(jsonPath("$.data.opened.effectiveFrom").isNotEmpty())
                .andExpect(jsonPath("$.data.opened.effectiveTo").value(nullValue()))
                .andExpect(jsonPath("$.data.opened.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.opened.operatorName").value(OPERATOR_NAME));

        // 库内事实（JdbcTemplate）：被关行落 effective_to（≈现在）、保留空操作者
        // （库植行＝存量形制，改价不改写旧行留痕）；新行敞口、操作者两列落值、
        // 匹配键沿用
        Map<String, Object> closedRow = jdbcTemplate.queryForMap(
                "SELECT effective_to, operator_id FROM met_price_entries WHERE id = ?",
                current.getId());
        assertThat(closedRow.get("effective_to")).isNotNull();
        assertThat(((Timestamp) closedRow.get("effective_to")).toInstant())
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(closedRow.get("operator_id")).isNull();

        Map<String, Object> openedRow = jdbcTemplate.queryForMap(
                "SELECT provider, model, token_kind, unit_price, currency, effective_to, "
                        + "operator_id, operator_name FROM met_price_entries "
                        + "WHERE provider = ? AND model = ? AND id != ?",
                PROVIDER, "m-r", current.getId());
        assertThat(openedRow.get("provider")).isEqualTo(PROVIDER);
        assertThat(openedRow.get("model")).isEqualTo("m-r");
        assertThat(openedRow.get("token_kind")).isEqualTo(TokenKind.INPUT.getCode());
        assertThat((BigDecimal) openedRow.get("unit_price"))
                .isEqualByComparingTo(new BigDecimal("0.000002"));
        assertThat(openedRow.get("currency")).isEqualTo("USD");
        assertThat(openedRow.get("effective_to")).isNull();
        assertThat(openedRow.get("operator_id")).isEqualTo(OPERATOR_ID);
        assertThat(openedRow.get("operator_name")).isEqualTo(OPERATOR_NAME);
    }

    @Test
    void given_reprice_without_operator_headers_then_new_row_operator_null() throws Exception {
        // v0 形制无操作者头 → 落空口径：新行操作者两列 NULL（「操作者为空」是读面
        // 一等状态），关行照常
        PriceEntry current = plantOpenRow("m-r2", T0, "0.000001");
        String path = "/api/backoffice/price-entries/" + current.getId() + "/reprice";
        String body = "{\"unitPrice\":0.000002,\"currency\":\"USD\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body))
                .andExpect(status().isOk());

        Map<String, Object> openedRow = jdbcTemplate.queryForMap(
                "SELECT operator_id, operator_name FROM met_price_entries "
                        + "WHERE provider = ? AND model = ? AND id != ?",
                PROVIDER, "m-r2", current.getId());
        assertThat(openedRow.get("operator_id")).isNull();
        assertThat(openedRow.get("operator_name")).isNull();
    }

    @Test
    void given_future_effective_from_when_reprice_then_window_splits_at_boundary() throws Exception {
        // 预发布：effectiveFrom＝未来时点（对齐供应商凌晨调价）。落库后旧价区间
        // 收到 F，窗口前后事件各按各时点单价（聚合口径验证）
        PriceEntry current = plantOpenRow("m-f", T0, "0.000001");
        Instant boundary = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(3600);
        String path = "/api/backoffice/price-entries/" + current.getId() + "/reprice";
        String body = "{\"unitPrice\":0.000002,\"currency\":\"USD\","
                + "\"effectiveFrom\":\"" + boundary + "\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closed.effectiveFrom").value("2026-07-01T00:00:00Z"));

        // 被关行 effective_to ＝ F 精确落库（毫秒锚，PG 微秒精度内无损）
        Timestamp closedTo = jdbcTemplate.queryForObject(
                "SELECT effective_to FROM met_price_entries WHERE id = ?",
                Timestamp.class, current.getId());
        assertThat(closedTo.toInstant()).isEqualTo(boundary);

        // 聚合口径：窗口前事件按旧价、窗口后事件按新价（历史成本不漂移）
        report("evt-before-window", Instant.now().minusSeconds(3600), "m-f", 1000);
        report("evt-after-window", boundary.plusSeconds(1800), "m-f", 1000);
        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);
        // 1000×$1/1M + 1000×$2/1M = $0.003（分档各按各时点价）
        assertThat(summary.cost()).containsOnlyKeys(USD);
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.003"));
        assertThat(summary.unpriced()).isEmpty();
    }

    // ---------- 重叠校验：同起点＋跨区间，两行都不动 ----------

    @Test
    void given_same_start_when_reprice_then_409_and_nothing_written() throws Exception {
        // 同起点：F＝当前行自身起点——关行不改写 effective_from，直插会撞唯一约束
        // 出 500；服务端先拦出干净 METER_008
        PriceEntry current = plantOpenRow("m-x1", T0, "0.000001");
        String path = "/api/backoffice/price-entries/" + current.getId() + "/reprice";
        String body = "{\"unitPrice\":0.000002,\"currency\":\"USD\","
                + "\"effectiveFrom\":\"2026-07-01T00:00:00Z\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("同匹配键生效区间重叠（跨区间或同起点）"));

        // 原子性：两行都不动——旧行仍敞口、无新行
        assertThat(jdbcTemplate.queryForMap(
                "SELECT effective_to FROM met_price_entries WHERE id = ?", current.getId()))
                .containsEntry("effective_to", null);
        assertThat(rowCountOfKey("m-x1")).isEqualTo(1);
    }

    @Test
    void given_preexisting_overlap_when_reprice_then_409_and_untouched() throws Exception {
        // 跨区间重叠（唯一约束防不住的事故口）：同键已有一行敞口区间越过 F（库植
        // 双敞口行＝遗留事故态），拟开区间与其相交 → 拦
        PriceEntry target = plantOpenRow("m-x2", T0, "0.000001");
        PriceEntry stray = plantOpenRow("m-x2", T2, "0.000009");
        String path = "/api/backoffice/price-entries/" + target.getId() + "/reprice";
        String body = "{\"unitPrice\":0.000002,\"currency\":\"USD\","
                + "\"effectiveFrom\":\"2026-09-01T00:00:00Z\"}"; // F 落 stray 敞口区间内

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("同匹配键生效区间重叠（跨区间或同起点）"));

        // 原子性：目标行不动、事故行不动、无新行
        assertThat(jdbcTemplate.queryForMap(
                "SELECT effective_to FROM met_price_entries WHERE id = ?", target.getId()))
                .containsEntry("effective_to", null);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT effective_to FROM met_price_entries WHERE id = ?", stray.getId()))
                .containsEntry("effective_to", null);
        assertThat(rowCountOfKey("m-x2")).isEqualTo(2);
    }

    @Test
    void given_invalid_reprice_input_then_400_or_404() throws Exception {
        // 行不存在（含畸形标识）：404 METER_006
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/price-entries/999999999/reprice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"unitPrice\":0.000002,\"currency\":\"USD\"}"),
                        "/api/backoffice/price-entries/999999999/reprice",
                        "{\"unitPrice\":0.000002,\"currency\":\"USD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("单价行不存在"));

        // 字段不完整（缺单价/币种）／负单价／非 ISO 币种：400 METER_004 / METER_010
        PriceEntry current = plantOpenRow("m-g", T0, "0.000001");
        String path = "/api/backoffice/price-entries/" + current.getId() + "/reprice";
        for (String body : new String[] {
                "{}",
                "{\"unitPrice\":-0.000001,\"currency\":\"USD\"}",
                "{\"unitPrice\":0.000002,\"currency\":\"MONOPOLY\"}"}) {
            mockMvc.perform(BackofficeSignatures.signed(
                            post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                            path, body))
                    .andExpect(status().isBadRequest());
        }

        // 起点早于被关行起点：400 METER_005（关行时点非法）；行不动
        String backdated = "{\"unitPrice\":0.000002,\"currency\":\"USD\","
                + "\"effectiveFrom\":\"2026-06-01T00:00:00Z\"}";
        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(backdated),
                        path, backdated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("关行时点非法（空或早于生效起点）"));

        assertThat(rowCountOfKey("m-g")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT effective_to FROM met_price_entries WHERE id = ?", current.getId()))
                .containsEntry("effective_to", null);
    }

    // ---------- 停用：即时生效＋unpriced＋留痕＋守卫 ----------

    @Test
    void given_current_row_when_signed_deactivate_with_operator_then_closed_and_unpriced_after()
            throws Exception {
        PriceEntry current = plantOpenRow("m-d", T0, "0.000001");
        String path = "/api/backoffice/price-entries/" + current.getId() + "/deactivate";

        mockMvc.perform(BackofficeSignatures.signed(post(path), path, null)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(current.getId().toString()))
                .andExpect(jsonPath("$.data.effectiveTo").isNotEmpty())
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME));

        // 库内留痕（JdbcTemplate）：关行时点≈现在＋停用操作者落被关行（停用不接
        // 新行，被关行是唯一落点）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT effective_to, operator_id, operator_name FROM met_price_entries "
                        + "WHERE id = ?", current.getId());
        assertThat(row.get("effective_to")).isNotNull();
        assertThat(((Timestamp) row.get("effective_to")).toInstant())
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
        assertThat(row.get("operator_id")).isEqualTo(OPERATOR_ID);
        assertThat(row.get("operator_name")).isEqualTo(OPERATOR_NAME);
        assertThat(rowCountOfKey("m-d")).isEqualTo(1); // 不接新行

        // 聚合口径：停用前窗口事件照换算，停用后窗口用量进 unpriced（缺价不伪装 0）
        report("evt-before-close", Instant.parse("2026-08-15T00:00:00Z"), "m-d", 1000);
        report("evt-after-close", Instant.now().plusSeconds(3600), "m-d", 500);
        UsageSummary summary = usageQueryPort.bySubject(SUBJ, null, null);
        assertThat(summary.cost()).containsOnlyKeys(USD);
        assertThat(summary.cost().get(USD)).isEqualByComparingTo(new BigDecimal("0.001"));
        assertThat(summary.unpriced()).containsExactly(
                new UsageSummary.UnpricedUsage(PROVIDER, "m-d", TokenKind.INPUT));
    }

    @Test
    void given_future_published_row_when_deactivate_then_clamped_to_never_effective()
            throws Exception {
        // 预发布行（未来起点）停用：钳到自身起点成空区间（从未生效，不倒挂）；
        // 停用后未来窗口用量进 unpriced
        Instant futureFrom = Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.MILLIS);
        PriceEntry future = plantOpenRow("m-d2", futureFrom, "0.000001");
        String path = "/api/backoffice/price-entries/" + future.getId() + "/deactivate";

        mockMvc.perform(BackofficeSignatures.signed(post(path), path, null)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT effective_from, effective_to, operator_id FROM met_price_entries "
                        + "WHERE id = ?", future.getId());
        assertThat(((Timestamp) row.get("effective_to")).toInstant()).isEqualTo(futureFrom);
        assertThat(((Timestamp) row.get("effective_from")).toInstant()).isEqualTo(futureFrom);
        assertThat(row.get("operator_id")).isEqualTo(OPERATOR_ID);

        report("evt-beyond", futureFrom.plusSeconds(3600), "m-d2", 100);
        assertThat(usageQueryPort.bySubject(SUBJ, null, null).unpriced()).containsExactly(
                new UsageSummary.UnpricedUsage(PROVIDER, "m-d2", TokenKind.INPUT));
    }

    @Test
    void given_closed_row_or_unknown_when_deactivate_or_reprice_then_409_or_404() throws Exception {
        // 已关行：改价/停用皆被 METER_007 拦（不可改写历史），行不动
        PriceEntry closed = plantClosedRow("m-d3", T0, T2, "0.000001");
        String deactivatePath = "/api/backoffice/price-entries/" + closed.getId() + "/deactivate";
        String repricePath = "/api/backoffice/price-entries/" + closed.getId() + "/reprice";
        String body = "{\"unitPrice\":0.000002,\"currency\":\"USD\"}";

        mockMvc.perform(BackofficeSignatures.signed(post(deactivatePath), deactivatePath, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("单价行非当前行（已关行不可改价或停用）"));
        mockMvc.perform(BackofficeSignatures.signed(
                        post(repricePath).contentType(MediaType.APPLICATION_JSON).content(body),
                        repricePath, body))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForMap(
                "SELECT effective_to, operator_id FROM met_price_entries WHERE id = ?",
                closed.getId()))
                .containsEntry("effective_to", Timestamp.from(T2))
                .containsEntry("operator_id", null);
        assertThat(rowCountOfKey("m-d3")).isEqualTo(1);

        // 停用寻址不存在：404 METER_006
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/price-entries/999999999/deactivate"),
                        "/api/backoffice/price-entries/999999999/deactivate", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("单价行不存在"));
    }

    // ---------- 夹具 ----------

    /** 库植敞口行（起点可未来＝预发布形制），存量形制无操作者。 */
    private PriceEntry plantOpenRow(String model, Instant from, String unitPrice) {
        return priceEntryRepository.save(PriceEntry.open(PROVIDER, model, TokenKind.INPUT,
                new BigDecimal(unitPrice), "USD", from, null));
    }

    /** 开行请求体（provider 固定夹具值）。 */
    private String openBody(String model, int tokenKind, String unitPrice, String effectiveFrom) {
        return "{\"provider\":\"" + PROVIDER + "\",\"model\":\"" + model + "\",\"tokenKind\":"
                + tokenKind + ",\"unitPrice\":" + unitPrice + ",\"currency\":\"USD\","
                + "\"effectiveFrom\":\"" + effectiveFrom + "\"}";
    }

    /** 库植已关行（历史行形制）：[from, to)。 */
    private PriceEntry plantClosedRow(String model, Instant from, Instant to, String unitPrice) {
        PriceEntry row = plantOpenRow(model, from, unitPrice);
        row.close(to);
        return priceEntryRepository.save(row);
    }

    /** 该键行数（原子性断言：负例后不添行）。 */
    private int rowCountOfKey(String model) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM met_price_entries WHERE provider = ? AND model = ?",
                Integer.class, PROVIDER, model);
    }

    private void report(String eventId, Instant ts, String model, long inputTokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, SUBJ, "run-1", "session-1",
                PROVIDER, model, Map.of(), new TokenUsage(inputTokens, 0, 0, 0, 0)));
    }
}
