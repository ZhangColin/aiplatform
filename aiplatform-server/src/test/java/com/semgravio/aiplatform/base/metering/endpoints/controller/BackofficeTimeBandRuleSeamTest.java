package com.semgravio.aiplatform.base.metering.endpoints.controller;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.base.metering.domain.aggregate.TimeBandRule;
import com.semgravio.aiplatform.base.metering.domain.enums.Band;
import com.semgravio.aiplatform.base.metering.domain.repository.TimeBandRuleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 时段规则管理写口在 {@code #152} seam 上全绿（#497，ADR-0044 峰谷 1/4）：真过滤链
 * （签名闸/强制拦截器）＋真应用服务＋aiplatform_test 真库。时段规则＝通道 × 生效
 * 区间 × 星期口径 × UTC 窗口行（met_time_band_rules），生命周期与单价表同构
 * （关行开新行，对称 METER_008 重叠校验）——本类照
 * {@code BackofficePriceEntrySeamTest} 先例一链钉死：
 *
 * <ul>
 * <li><b>开行</b>（种子脚本通道）：一次调用原子落 N 行窗口行＝一个规则版本
 * （DeepSeek 峰窗 01:00–04:00＋06:00–10:00 工作日两窗口一单种入）；操作者两列
 * 落新行（无头落空）；band 落库 code 钉死（防 ORDINAL 错位）；</li>
 * <li><b>重叠拒</b>（对称 METER_008）：同通道同起点重复开行拒、跨区间重叠拒、
 * 反向越界拒；关行后自停用边界重开合法；</li>
 * <li><b>原子改窗</b>：寻址行须当前行（METER_007），命中即关该通道当前版本
 * 全部行＋开新版本（新行操作者落新行、被关行保留原操作者）；守卫失败版本不动；</li>
 * <li><b>停用</b>：关当前版本全部行不接新行，操作者落被关行；预发布版本钳成空区间；</li>
 * <li><b>字段守卫</b>（METER_013）：星期口径 bitmask 越界/窗口空区间/跨午夜
 * 窗口（start ≥ end）/end 越界/非法 band code/空窗口清单——全部 400 不落行。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeTimeBandRuleSeamTest {

    /** 夹具 provider：与种子行、他类测试互不沾。 */
    private static final String PROVIDER = "time-band-prov";

    /** 夹具时间锚：历史版本起点（远过去）。 */
    private static final Instant T0 = Instant.parse("2026-08-16T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-01T00:00:00Z");

    /** 操作者测试身份：admin 侧管理员的 TSID＋昵称样例（同单价 seam 口径）。 */
    private static final String OPERATOR_ID = "700160";
    private static final String OPERATOR_NAME = "运营·时段管理员";

    /** DeepSeek 峰窗形状：工作日（Mon–Fri bitmask 31）01:00–04:00＋06:00–10:00 UTC。 */
    private static final String PEAK_WINDOWS_BODY = "["
            + "{\"band\":1,\"dayMask\":31,\"startMinute\":60,\"endMinute\":240},"
            + "{\"band\":1,\"dayMask\":31,\"startMinute\":360,\"endMinute\":600}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TimeBandRuleRepository timeBandRuleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM met_time_band_date_overrides WHERE provider = ?", PROVIDER);
        jdbcTemplate.update("DELETE FROM met_time_band_rules WHERE provider = ?", PROVIDER);
    }

    // ---------- 开行（种子脚本通道：一版本 N 窗口行原子落） ----------

    @Test
    void given_no_rule_when_signed_open_two_windows_with_operator_then_version_persisted()
            throws Exception {
        // 空通道首开：DeepSeek 峰窗两窗口一单种入（一次开行＝一个规则版本）
        String body = openBody(PEAK_WINDOWS_BODY, "2026-08-16T00:00:00Z");

        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/time-band-rules")
                                .contentType(MediaType.APPLICATION_JSON).content(body),
                        "/api/backoffice/time-band-rules", body)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].provider").value(containsInAnyOrder(PROVIDER, PROVIDER)))
                .andExpect(jsonPath("$.data[*].band").value(containsInAnyOrder(1, 1)))
                .andExpect(jsonPath("$.data[*].bandName").value(containsInAnyOrder("峰", "峰")))
                .andExpect(jsonPath("$.data[*].dayMask").value(containsInAnyOrder(31, 31)))
                .andExpect(jsonPath("$.data[*].dayMaskName")
                        .value(containsInAnyOrder("周一至周五", "周一至周五")))
                .andExpect(jsonPath("$.data[*].startMinute").value(containsInAnyOrder(60, 360)))
                .andExpect(jsonPath("$.data[*].endMinute").value(containsInAnyOrder(240, 600)))
                .andExpect(jsonPath("$.data[*].effectiveFrom").value(containsInAnyOrder(
                        "2026-08-16T00:00:00Z", "2026-08-16T00:00:00Z")))
                .andExpect(jsonPath("$.data[*].effectiveTo").value(containsInAnyOrder(nullValue(), nullValue())))
                .andExpect(jsonPath("$.data[*].operatorId")
                        .value(containsInAnyOrder(OPERATOR_ID, OPERATOR_ID)))
                .andExpect(jsonPath("$.data[*].operatorName")
                        .value(containsInAnyOrder(OPERATOR_NAME, OPERATOR_NAME)));

        // 库内事实（JdbcTemplate）：两窗口行同版本（同起点敞口）、band 落库 code＝1
        // （钉死防 ORDINAL 错位）、操作者两列落值
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT band, day_mask, start_minute, end_minute, effective_from, effective_to, "
                        + "operator_id, operator_name FROM met_time_band_rules WHERE provider = ?",
                PROVIDER);
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("band")).isEqualTo(Band.PEAK.getCode());
            assertThat(row.get("day_mask")).isEqualTo(31);
            assertThat((Integer) row.get("start_minute")).isIn(60, 360);
            assertThat(row.get("effective_to")).isNull();
            assertThat(((Timestamp) row.get("effective_from")).toInstant())
                    .isEqualTo(Instant.parse("2026-08-16T00:00:00Z"));
            assertThat(row.get("operator_id")).isEqualTo(OPERATOR_ID);
            assertThat(row.get("operator_name")).isEqualTo(OPERATOR_NAME);
        }
    }

    @Test
    void given_open_without_operator_headers_then_operator_null() throws Exception {
        // 无头落空口径（同单价）：种子脚本不传操作者头 → 种入行操作者两列 NULL
        String body = openBody(PEAK_WINDOWS_BODY, null);

        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/time-band-rules")
                                .contentType(MediaType.APPLICATION_JSON).content(body),
                        "/api/backoffice/time-band-rules", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].effectiveFrom").isNotEmpty())
                .andExpect(jsonPath("$.data[0].operatorId").value(nullValue()));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT operator_id, operator_name FROM met_time_band_rules "
                        + "WHERE provider = ? AND start_minute = 60",
                PROVIDER);
        assertThat(row.get("operator_id")).isNull();
        assertThat(row.get("operator_name")).isNull();
    }

    // ---------- 行清单：含历史行＋过滤＋排序＋分页 ----------

    @Test
    void given_versions_with_history_when_signed_list_then_filtered_and_sorted() throws Exception {
        // v1 历史版本（已关）＋v2 当前版本（改窗一次：版本起点 T2）
        plantClosedVersion(T0, T2, 60, 240);
        plantVersion(T2, 360, 600);

        // provider 过滤：全量四行（v1 两窗口行＋v2 两窗口行）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/time-band-rules").queryParam("provider", PROVIDER),
                        "/api/backoffice/time-band-rules?provider=" + PROVIDER, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(4)))
                .andExpect(jsonPath("$.data.total").value("4"));

        // 生效起点倒序：当前版本（T2 起）在前，历史版本（T0 起）在后
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/time-band-rules").queryParam("provider", PROVIDER),
                        "/api/backoffice/time-band-rules?provider=" + PROVIDER, null))
                .andExpect(jsonPath("$.data.items[0].effectiveFrom").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].effectiveTo").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].effectiveFrom").value("2026-08-16T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[2].effectiveTo").value("2026-09-01T00:00:00Z"));

        // 未命中 provider：空清单
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/time-band-rules").queryParam("provider", "time-band-none"),
                        "/api/backoffice/time-band-rules?provider=time-band-none", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));
    }

    @Test
    void given_four_rows_when_paging_then_walked_and_bad_page_param_400() throws Exception {
        plantVersion(T0, 60, 240);
        plantVersion(T2, 360, 600);

        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/time-band-rules")
                                .queryParam("provider", PROVIDER).queryParam("size", "3"),
                        "/api/backoffice/time-band-rules?provider=" + PROVIDER + "&size=3", null))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.total").value("4"));
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/time-band-rules")
                                .queryParam("provider", PROVIDER).queryParam("size", "3")
                                .queryParam("page", "2"),
                        "/api/backoffice/time-band-rules?provider=" + PROVIDER + "&size=3&page=2",
                        null))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.page").value(2));

        // 非法分页值：400 带字段级明细（框架统一信封）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/time-band-rules")
                                .queryParam("provider", PROVIDER).queryParam("page", "abc"),
                        "/api/backoffice/time-band-rules?provider=" + PROVIDER + "&page=abc", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parameter validation failed"));
    }

    // ---------- 重叠校验（对称 METER_008）：同起点＋跨区间＋反向越界，全不落行 ----------

    @Test
    void given_existing_version_when_open_then_same_start_rejected_and_nothing_written()
            throws Exception {
        // Demo AC：同起点重复开行被拒（种子脚本重复执行＝第二单同起点 → 409）
        plantVersion(T0, 60, 240);
        String body = openBody(PEAK_WINDOWS_BODY, "2026-08-16T00:00:00Z");

        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/time-band-rules")
                                .contentType(MediaType.APPLICATION_JSON).content(body),
                        "/api/backoffice/time-band-rules", body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("同匹配键生效区间重叠（跨区间或同起点）"));
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void given_open_version_when_open_then_cross_overlap_rejected() throws Exception {
        // 跨区间：F 落既有敞口版本区间内 → 409（对称单价表事故口口径）
        plantVersion(T0, 60, 240);
        String body = openBody(PEAK_WINDOWS_BODY, "2026-09-01T00:00:00Z");

        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/time-band-rules")
                                .contentType(MediaType.APPLICATION_JSON).content(body),
                        "/api/backoffice/time-band-rules", body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("同匹配键生效区间重叠（跨区间或同起点）"));
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void given_closed_version_when_open_then_boundary_resume_allowed_and_backtrack_rejected()
            throws Exception {
        // 边界：历史版本 [T0,T2) 后自 T2 重开 → 合法无缝无叠
        plantClosedVersion(T0, T2, 60, 240);
        String resume = openBody(PEAK_WINDOWS_BODY, "2026-09-01T00:00:00Z");
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/time-band-rules")
                                .contentType(MediaType.APPLICATION_JSON).content(resume),
                        "/api/backoffice/time-band-rules", resume))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].effectiveFrom").value("2026-09-01T00:00:00Z"));
        assertThat(rowCount()).isEqualTo(4);

        // 反向越界：F 早于历史版本终点落其闭区间内 → 409
        String backtrack = openBody(PEAK_WINDOWS_BODY, "2026-08-20T00:00:00Z");
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/time-band-rules")
                                .contentType(MediaType.APPLICATION_JSON).content(backtrack),
                        "/api/backoffice/time-band-rules", backtrack))
                .andExpect(status().isConflict());
        assertThat(rowCount()).isEqualTo(4);
    }

    // ---------- 字段守卫（METER_013）：全部 400 不落行 ----------

    @Test
    void given_invalid_open_input_then_400_and_nothing_written() throws Exception {
        // 字段不完整／星期口径越界／空窗口与跨午夜窗口（start ≥ end）／end 越界／
        // 非法 band code：400（聚合守卫＋绑定失败），全部不落行
        for (String body : new String[] {
                "{}",
                "{\"provider\":\"" + PROVIDER + "\"}", // 缺窗口清单
                "{\"provider\":\"" + PROVIDER + "\",\"windows\":[]}", // 空窗口清单
                "{\"provider\":\"" + PROVIDER + "\",\"windows\":"
                        + "[{\"band\":1,\"dayMask\":0,\"startMinute\":60,\"endMinute\":240}]}", // 星期口径 0
                "{\"provider\":\"" + PROVIDER + "\",\"windows\":"
                        + "[{\"band\":1,\"dayMask\":128,\"startMinute\":60,\"endMinute\":240}]}", // 越 7 位
                "{\"provider\":\"" + PROVIDER + "\",\"windows\":"
                        + "[{\"band\":1,\"dayMask\":31,\"startMinute\":240,\"endMinute\":240}]}", // 空区间
                "{\"provider\":\"" + PROVIDER + "\",\"windows\":"
                        + "[{\"band\":1,\"dayMask\":31,\"startMinute\":1320,\"endMinute\":120}]}", // 跨午夜
                "{\"provider\":\"" + PROVIDER + "\",\"windows\":"
                        + "[{\"band\":1,\"dayMask\":31,\"startMinute\":-1,\"endMinute\":240}]}", // 负起点
                "{\"provider\":\"" + PROVIDER + "\",\"windows\":"
                        + "[{\"band\":1,\"dayMask\":31,\"startMinute\":60,\"endMinute\":1441}]}", // 终点越界
                "{\"provider\":\"" + PROVIDER + "\",\"windows\":"
                        + "[{\"band\":99,\"dayMask\":31,\"startMinute\":60,\"endMinute\":240}]}"}) {
            mockMvc.perform(BackofficeSignatures.signed(
                            post("/api/backoffice/time-band-rules")
                                    .contentType(MediaType.APPLICATION_JSON).content(body),
                            "/api/backoffice/time-band-rules", body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(rowCount()).isEqualTo(0);
    }

    // ---------- 原子改窗：关当前版本全部行＋开新版本 ----------

    @Test
    void given_current_version_when_signed_rewindow_then_close_all_and_open_new_with_operator()
            throws Exception {
        // 库植 v1 两窗口行（存量形制无操作者）；改窗寻址其中一行 → 整版本关闭
        List<TimeBandRule> versionOne = plantVersion(T0, 60, 240);
        TimeBandRule addressed = versionOne.get(0);
        String path = "/api/backoffice/time-band-rules/" + addressed.getId() + "/rewindow";
        // v2：周末全天谷（Sat+Sun bitmask 96，00:00–24:00），起点即时
        String body = "{\"windows\":[{\"band\":2,\"dayMask\":96,"
                + "\"startMinute\":0,\"endMinute\":1440}]}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closed", hasSize(2)))
                .andExpect(jsonPath("$.data.closed[*].effectiveTo").value(containsInAnyOrder(
                        org.hamcrest.Matchers.notNullValue(), org.hamcrest.Matchers.notNullValue())))
                // 被关行保留原操作者（库植＝存量形制 NULL，改窗不改写旧行留痕）
                .andExpect(jsonPath("$.data.closed[*].operatorId")
                        .value(containsInAnyOrder(nullValue(), nullValue())))
                .andExpect(jsonPath("$.data.opened", hasSize(1)))
                .andExpect(jsonPath("$.data.opened[0].id").value(
                        org.hamcrest.Matchers.not(versionOne.get(0).getId().toString())))
                .andExpect(jsonPath("$.data.opened[0].id").value(
                        org.hamcrest.Matchers.not(versionOne.get(1).getId().toString())))
                .andExpect(jsonPath("$.data.opened[0].band").value(2))
                .andExpect(jsonPath("$.data.opened[0].bandName").value("谷"))
                .andExpect(jsonPath("$.data.opened[0].dayMask").value(96))
                .andExpect(jsonPath("$.data.opened[0].dayMaskName").value("周六至周日"))
                .andExpect(jsonPath("$.data.opened[0].startMinute").value(0))
                .andExpect(jsonPath("$.data.opened[0].endMinute").value(1440))
                .andExpect(jsonPath("$.data.opened[0].effectiveTo").value(nullValue()))
                .andExpect(jsonPath("$.data.opened[0].operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.opened[0].operatorName").value(OPERATOR_NAME));

        // 库内事实：v1 两行皆关（effective_to≈现在、操作者仍 NULL）；v2 新行敞口、
        // 操作者落值
        List<Map<String, Object>> closedRows = jdbcTemplate.queryForList(
                "SELECT effective_to, operator_id FROM met_time_band_rules WHERE provider = ? "
                        + "AND start_minute IN (60, 240) AND effective_to IS NOT NULL", PROVIDER);
        assertThat(closedRows).hasSize(2);
        for (Map<String, Object> row : closedRows) {
            assertThat(((Timestamp) row.get("effective_to")).toInstant())
                    .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
            assertThat(row.get("operator_id")).isNull();
        }
        Map<String, Object> openedRow = jdbcTemplate.queryForMap(
                "SELECT band, day_mask, effective_to, operator_id, operator_name "
                        + "FROM met_time_band_rules WHERE provider = ? AND start_minute = 0",
                PROVIDER);
        assertThat(openedRow.get("band")).isEqualTo(Band.OFF_PEAK.getCode());
        assertThat(openedRow.get("day_mask")).isEqualTo(96);
        assertThat(openedRow.get("effective_to")).isNull();
        assertThat(openedRow.get("operator_id")).isEqualTo(OPERATOR_ID);
        assertThat(openedRow.get("operator_name")).isEqualTo(OPERATOR_NAME);
    }

    @Test
    void given_rewindow_without_operator_headers_then_new_version_operator_null() throws Exception {
        List<TimeBandRule> versionOne = plantVersion(T0, 60, 240);
        String path = "/api/backoffice/time-band-rules/" + versionOne.get(0).getId() + "/rewindow";
        String body = "{\"windows\":[{\"band\":1,\"dayMask\":31,\"startMinute\":60,\"endMinute\":240}]}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body))
                .andExpect(status().isOk());

        Map<String, Object> openedRow = jdbcTemplate.queryForMap(
                "SELECT operator_id, operator_name FROM met_time_band_rules "
                        + "WHERE provider = ? AND effective_to IS NULL",
                PROVIDER);
        assertThat(openedRow.get("operator_id")).isNull();
        assertThat(openedRow.get("operator_name")).isNull();
    }

    @Test
    void given_future_effective_from_when_rewindow_then_current_version_closes_at_future_boundary()
            throws Exception {
        // 预发布：改窗可指定未来起点（对齐供应商调窗口公告期）；被关行收到 F 精确落库
        List<TimeBandRule> versionOne = plantVersion(T0, 60, 240);
        Instant boundary = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(3600);
        String path = "/api/backoffice/time-band-rules/" + versionOne.get(0).getId() + "/rewindow";
        String body = "{\"windows\":[{\"band\":1,\"dayMask\":31,\"startMinute\":60,\"endMinute\":240}],"
                + "\"effectiveFrom\":\"" + boundary + "\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closed[0].effectiveFrom").value("2026-08-16T00:00:00Z"));

        Timestamp closedTo = jdbcTemplate.queryForObject(
                "SELECT effective_to FROM met_time_band_rules WHERE id = ?",
                Timestamp.class, versionOne.get(0).getId());
        assertThat(closedTo.toInstant()).isEqualTo(boundary);
    }

    // ---------- 改窗负例：METER_007/015/014/008，版本不动 ----------

    @Test
    void given_closed_row_or_unknown_when_rewindow_then_409_or_404_and_untouched()
            throws Exception {
        // 已关版本行寻址改窗：METER_007 拦（不可改写历史）
        List<TimeBandRule> history = plantClosedVersion(T0, T2, 60, 240);
        String closedPath = "/api/backoffice/time-band-rules/" + history.get(0).getId() + "/rewindow";
        String body = "{\"windows\":[{\"band\":1,\"dayMask\":31,\"startMinute\":60,\"endMinute\":240}]}";

        ResultActions conflict = mockMvc.perform(BackofficeSignatures.signed(
                post(closedPath).contentType(MediaType.APPLICATION_JSON).content(body),
                closedPath, body));
        conflict.andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("时段规则行非当前行（已关行不可改或停用）"));
        assertThat(rowCount()).isEqualTo(2);

        // 寻址不存在（含畸形标识）：404 METER_015
        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/time-band-rules/999999999/rewindow")
                                .contentType(MediaType.APPLICATION_JSON).content(body),
                        "/api/backoffice/time-band-rules/999999999/rewindow", body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("时段规则行不存在"));
    }

    @Test
    void given_backdated_effective_from_when_rewindow_then_400_and_version_untouched()
            throws Exception {
        // 新起点早于被关版本起点：400 METER_014（关行时点非法）
        List<TimeBandRule> versionOne = plantVersion(T2, 60, 240);
        String path = "/api/backoffice/time-band-rules/" + versionOne.get(0).getId() + "/rewindow";
        String body = "{\"windows\":[{\"band\":1,\"dayMask\":31,\"startMinute\":60,\"endMinute\":240}],"
                + "\"effectiveFrom\":\"2026-08-01T00:00:00Z\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("关行时点非法（空或早于生效起点）"));

        // 原子性：版本仍敞口、无新行
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM met_time_band_rules WHERE provider = ? AND effective_to IS NULL",
                Integer.class, PROVIDER)).isEqualTo(2);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void given_stray_open_version_when_rewindow_then_409_overlap_and_nothing_written()
            throws Exception {
        // 事故态：同通道双敞口版本（库植），改窗拟开区间落 stray 敞口区间内 →
        // METER_008 拦（唯一约束防不住的事故口，服务端补），两行版本皆不动
        List<TimeBandRule> target = plantVersion(T0, 60, 240);
        List<TimeBandRule> stray = plantOpenVersion(Instant.now().minusSeconds(1800), 0, 30);
        String path = "/api/backoffice/time-band-rules/" + target.get(0).getId() + "/rewindow";
        String body = "{\"windows\":[{\"band\":1,\"dayMask\":31,\"startMinute\":60,\"endMinute\":240}],"
                + "\"effectiveFrom\":\"2026-09-01T00:00:00Z\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                        path, body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("同匹配键生效区间重叠（跨区间或同起点）"));

        for (TimeBandRule row : List.of(target.get(0), target.get(1), stray.get(0), stray.get(1))) {
            assertThat(jdbcTemplate.queryForMap(
                    "SELECT effective_to FROM met_time_band_rules WHERE id = ?", row.getId()))
                    .containsEntry("effective_to", null);
        }
        assertThat(rowCount()).isEqualTo(4);
    }

    @Test
    void given_invalid_rewindow_input_then_400_and_version_untouched() throws Exception {
        List<TimeBandRule> versionOne = plantVersion(T0, 60, 240);
        String path = "/api/backoffice/time-band-rules/" + versionOne.get(0).getId() + "/rewindow";

        // 空窗口清单／跨午夜窗口：400 METER_013，被关版本不动、无新行
        for (String body : new String[] {
                "{}",
                "{\"windows\":[]}",
                "{\"windows\":[{\"band\":1,\"dayMask\":31,\"startMinute\":1200,\"endMinute\":120}]}"}) {
            mockMvc.perform(BackofficeSignatures.signed(
                            post(path).contentType(MediaType.APPLICATION_JSON).content(body),
                            path, body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM met_time_band_rules WHERE provider = ? AND effective_to IS NULL",
                Integer.class, PROVIDER)).isEqualTo(2);
        assertThat(rowCount()).isEqualTo(2);
    }

    // ---------- 停用：关当前版本全部行不接新行＋留痕＋钳位 ----------

    @Test
    void given_current_version_when_signed_deactivate_with_operator_then_all_closed_no_new_row()
            throws Exception {
        List<TimeBandRule> versionOne = plantVersion(T0, 60, 240);
        String path = "/api/backoffice/time-band-rules/" + versionOne.get(1).getId() + "/deactivate";

        // 寻址第二行：命中版本 → 两行全关（关行在版本级，非单行）
        mockMvc.perform(BackofficeSignatures.signed(post(path), path, null)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].effectiveTo").value(containsInAnyOrder(
                        org.hamcrest.Matchers.notNullValue(), org.hamcrest.Matchers.notNullValue())))
                .andExpect(jsonPath("$.data[*].operatorId")
                        .value(containsInAnyOrder(OPERATOR_ID, OPERATOR_ID)));

        // 库内留痕：两行皆关（effective_to≈现在）、停用操作者落被关行（版本各行皆落）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT effective_to, operator_id, operator_name FROM met_time_band_rules "
                        + "WHERE provider = ?", PROVIDER);
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(((Timestamp) row.get("effective_to")).toInstant())
                    .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
            assertThat(row.get("operator_id")).isEqualTo(OPERATOR_ID);
            assertThat(row.get("operator_name")).isEqualTo(OPERATOR_NAME);
        }
    }

    @Test
    void given_future_published_version_when_deactivate_then_clamped_to_never_effective()
            throws Exception {
        // 预发布版本（未来起点）停用：钳到自身起点成空区间（从未生效，不倒挂）
        Instant futureFrom = Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.MILLIS);
        List<TimeBandRule> future = plantOpenVersion(futureFrom, 60, 240);
        String path = "/api/backoffice/time-band-rules/" + future.get(0).getId() + "/deactivate";

        mockMvc.perform(BackofficeSignatures.signed(post(path), path, null)
                        .header("X-User-Id", OPERATOR_ID)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT effective_from, effective_to, operator_id FROM met_time_band_rules "
                        + "WHERE id = ?", future.get(0).getId());
        assertThat(((Timestamp) row.get("effective_to")).toInstant()).isEqualTo(futureFrom);
        assertThat(((Timestamp) row.get("effective_from")).toInstant()).isEqualTo(futureFrom);
        assertThat(row.get("operator_id")).isEqualTo(OPERATOR_ID);
    }

    @Test
    void given_closed_version_or_unknown_when_deactivate_then_409_or_404_and_untouched()
            throws Exception {
        List<TimeBandRule> history = plantClosedVersion(T0, T2, 60, 240);
        String closedPath = "/api/backoffice/time-band-rules/" + history.get(0).getId() + "/deactivate";

        mockMvc.perform(BackofficeSignatures.signed(post(closedPath), closedPath, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("时段规则行非当前行（已关行不可改或停用）"));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT effective_to FROM met_time_band_rules WHERE id = ?", history.get(0).getId()))
                .containsEntry("effective_to", Timestamp.from(T2));

        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/time-band-rules/999999999/deactivate"),
                        "/api/backoffice/time-band-rules/999999999/deactivate", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("时段规则行不存在"));
    }

    // ---------- 夹具 ----------

    /** 开行请求体（provider 固定夹具值；effectiveFrom 可空＝即时）。 */
    private String openBody(String windowsJson, String effectiveFrom) {
        String body = "{\"provider\":\"" + PROVIDER + "\",\"windows\":" + windowsJson;
        if (effectiveFrom != null) {
            body += ",\"effectiveFrom\":\"" + effectiveFrom + "\"";
        }
        return body + "}";
    }

    /** 库植当前版本：两窗口行（start–240、360–600 同档）敞口，存量形制无操作者。 */
    private List<TimeBandRule> plantVersion(Instant from, int firstStart, int secondStart) {
        return plantOpenVersion(from, firstStart, secondStart);
    }

    private List<TimeBandRule> plantOpenVersion(Instant from, int firstStart, int secondStart) {
        TimeBandRule first = TimeBandRule.open(PROVIDER, Band.PEAK, 31, firstStart, firstStart + 180,
                from, null);
        TimeBandRule second = TimeBandRule.open(PROVIDER, Band.PEAK, 31, secondStart, secondStart + 180,
                from, null);
        return List.of(timeBandRuleRepository.save(first), timeBandRuleRepository.save(second));
    }

    /** 库植历史版本：[from, to) 两窗口行，存量形制无操作者。 */
    private List<TimeBandRule> plantClosedVersion(Instant from, Instant to,
                                                  int firstStart, int secondStart) {
        List<TimeBandRule> rows = plantOpenVersion(from, firstStart, secondStart);
        for (TimeBandRule row : rows) {
            row.close(to);
            timeBandRuleRepository.save(row);
        }
        return rows;
    }

    /** 该通道行数（原子性断言：负例后不添行）。 */
    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM met_time_band_rules WHERE provider = ?", Integer.class,
                PROVIDER);
    }
}
