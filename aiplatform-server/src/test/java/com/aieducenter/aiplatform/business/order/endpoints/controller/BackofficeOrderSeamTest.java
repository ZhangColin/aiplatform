package com.aieducenter.aiplatform.business.order.endpoints.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v0 四端点在 {@code #152} seam 上全绿：真过滤链（签名闸/会话豁免/强制拦截器）
 * ＋真应用服务＋aiplatform_test 真库——订单链路 place→清单→详情→报价全真（跨
 * BC 项目读 mock 掉 docker 依赖，同 {@code OrderAppServiceTest} 形制）。源码包
 * 端点 happy path 需真实工作区容器，联调归 scripts/backoffice-quote.sh；本类证
 * 明其在 seam 上的链路与订单守卫。后续域票照本类形制挂 {@link BackofficeSeamTest}
 * 写各自端点测试。
 *
 * <p>#155 操作者留痕在本类一链钉死：带 {@code X-User-Id}/{@code X-User-Name}
 * 的签名报价 → 价目行库列落值 → 详情价目历史呈现（新条目带操作者、无头/存量
 * 条目操作者为空）。</p>
 */
@BackofficeSeamTest
class BackofficeOrderSeamTest {

    private static final long PROJECT_ID = 900100L;
    private static final String PRD = "# PRD\n\n需求背景：后台 seam。";

    /** 操作者测试身份：admin 侧管理员的 TSID＋昵称样例（同契约测试口径）。 */
    private static final long OPERATOR_ID = 700100L;
    private static final String OPERATOR_NAME = "运营·小刘";
    /** 断言/落库用字符串形（header 与 VARCHAR 列同形，避免逐处转换）。 */
    private static final String OPERATOR_ID_STR = Long.toString(OPERATOR_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderAppService appService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ord_orders");
    }

    @Test
    void given_placed_order_when_signed_list_then_row_visible_with_tsid_desc() throws Exception {
        OrderResponse order = placeOrder();

        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/orders"),
                        "/api/backoffice/orders", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(order.id()))
                .andExpect(jsonPath("$.data.items[0].status").value(1))
                .andExpect(jsonPath("$.data.total").value("1")); // Long 全局序列化为字符串
    }

    @Test
    void given_placed_order_when_signed_detail_then_frozen_prd_visible() throws Exception {
        OrderResponse order = placeOrder();
        when(projectQueryAppService.namesOf(List.of(PROJECT_ID)))
                .thenReturn(Map.of(PROJECT_ID, "seam 测试项目"));

        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders/" + order.id()),
                        "/api/backoffice/orders/" + order.id(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(order.id()))
                .andExpect(jsonPath("$.data.projectName").value("seam 测试项目"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.prdSnapshot").value(PRD));
    }

    @Test
    void given_placed_order_when_signed_quote_then_repriced_via_real_service() throws Exception {
        OrderResponse order = placeOrder();
        String body = "{\"amount\":128000,\"note\":\"首版报价\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post("/api/backoffice/orders/" + order.id() + "/quote")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        "/api/backoffice/orders/" + order.id() + "/quote", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.amount").value("128000"))
                .andExpect(jsonPath("$.data.note").value("首版报价"))
                .andExpect(jsonPath("$.data.priceEntries.length()").value(1));

        // 报价以库内行为为准：append-only 价目行落库
        Integer entries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ord_price_entries WHERE order_id = ?",
                Integer.class, Long.parseLong(order.id()));
        assertThat(entries).isEqualTo(1);
    }

    @Test
    void given_unknown_order_when_signed_source_package_then_404_ord001() throws Exception {
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders/999999999/source-package"),
                        "/api/backoffice/orders/999999999/source-package", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    // ---------- #155：操作者留痕＋详情价目历史（一链断言） ----------

    @Test
    void given_operator_headers_when_signed_quote_then_persisted_and_visible_in_detail()
            throws Exception {
        OrderResponse order = placeOrder();
        stubProjectName();
        String quotePath = "/api/backoffice/orders/" + order.id() + "/quote";
        String body = "{\"amount\":128000,\"note\":\"首版报价\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(quotePath).contentType(MediaType.APPLICATION_JSON).content(body),
                        quotePath, body)
                        .header("X-User-Id", OPERATOR_ID_STR)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2));

        // 库列落值（JdbcTemplate 断言）：Id+Name 都落
        Map<String, Object> entry = jdbcTemplate.queryForMap(
                "SELECT operator_id, operator_name FROM ord_price_entries WHERE order_id = ?",
                Long.parseLong(order.id()));
        assertThat(entry.get("operator_id")).isEqualTo(OPERATOR_ID_STR);
        assertThat(entry.get("operator_name")).isEqualTo(OPERATOR_NAME);

        // 详情呈现：价目历史数组（append-only 全量），新条目带操作者
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders/" + order.id()),
                        "/api/backoffice/orders/" + order.id(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priceEntries.length()").value(1))
                .andExpect(jsonPath("$.data.priceEntries[0].amount").value("128000"))
                .andExpect(jsonPath("$.data.priceEntries[0].operatorId")
                        .value(OPERATOR_ID_STR))
                .andExpect(jsonPath("$.data.priceEntries[0].operatorName").value(OPERATOR_NAME));
    }

    @Test
    void given_quote_without_operator_headers_then_null_and_legacy_entry_renders_empty()
            throws Exception {
        // 无操作者头（v0 形制调用）→ 落空口径：库列 NULL；随后带操作者头改价，
        // 无头写入的行即「存量形制」——详情新→旧两条：新带操作者、旧（存量）为空
        OrderResponse order = placeOrder();
        stubProjectName();
        String quotePath = "/api/backoffice/orders/" + order.id() + "/quote";
        String firstBody = "{\"amount\":128000,\"note\":\"首版报价\"}";
        String repriceBody = "{\"amount\":99000,\"note\":\"调整：去掉导入功能\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(quotePath).contentType(MediaType.APPLICATION_JSON).content(firstBody),
                        quotePath, firstBody))
                .andExpect(status().isOk());
        Map<String, Object> legacyEntry = jdbcTemplate.queryForMap(
                "SELECT operator_id, operator_name FROM ord_price_entries WHERE order_id = ?",
                Long.parseLong(order.id()));
        assertThat(legacyEntry.get("operator_id")).isNull();
        assertThat(legacyEntry.get("operator_name")).isNull();

        mockMvc.perform(BackofficeSignatures.signed(
                        post(quotePath).contentType(MediaType.APPLICATION_JSON).content(repriceBody),
                        quotePath, repriceBody)
                        .header("X-User-Id", OPERATOR_ID_STR)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk());

        // 库内事实：两行都在，旧行操作者仍为空（append-only 不改写）
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT operator_id, operator_name FROM ord_price_entries "
                        + "WHERE order_id = ? ORDER BY id",
                Long.parseLong(order.id()));
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("operator_id", null)
                .containsEntry("operator_name", null); // 无头写入的行 = 存量形制
        assertThat(entries.get(1)).containsEntry("operator_id", OPERATOR_ID_STR)
                .containsEntry("operator_name", OPERATOR_NAME);

        // 详情呈现：新 → 旧，新条目带操作者、存量条目操作者为空
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders/" + order.id()),
                        "/api/backoffice/orders/" + order.id(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priceEntries.length()").value(2))
                .andExpect(jsonPath("$.data.priceEntries[0].amount").value("99000"))
                .andExpect(jsonPath("$.data.priceEntries[0].operatorId")
                        .value(OPERATOR_ID_STR))
                .andExpect(jsonPath("$.data.priceEntries[0].operatorName").value(OPERATOR_NAME))
                .andExpect(jsonPath("$.data.priceEntries[1].amount").value("128000"))
                .andExpect(jsonPath("$.data.priceEntries[1].operatorId").value(nullValue()))
                .andExpect(jsonPath("$.data.priceEntries[1].operatorName").value(nullValue()));
    }

    // -------- 夹具 --------

    /** 详情读面跨 BC 取项目名（软引用）：统一 stub，缺档为 null 也成立。 */
    private void stubProjectName() {
        when(projectQueryAppService.namesOf(List.of(PROJECT_ID)))
                .thenReturn(Map.of(PROJECT_ID, "seam 测试项目"));
    }

    /** 经真应用服务下单（真库写入、快照冻结），返回带 TSID 的订单回执 */
    private OrderResponse placeOrder() {
        when(projectQueryAppService.detail(PROJECT_ID)).thenReturn(new ProjectDetailResponse(
                Long.toString(PROJECT_ID), "seam 测试项目", ProjectType.WEBSITE, "官网", "9100",
                ProjectStatus.IN_PROGRESS, ProjectStatus.IN_PROGRESS.getName(), false,
                LocalDateTime.of(2026, 9, 13, 9, 0), null, null, null, null, null));
        when(projectQueryAppService.prd(PROJECT_ID)).thenReturn(new PrdResponse(
                Long.toString(PROJECT_ID), PRD, Instant.parse("2026-09-13T01:00:00Z")));
        return appService.place(PROJECT_ID);
    }
}
