package com.aieducenter.aiplatform.business.order.endpoints.controller;

import java.sql.Timestamp;
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

import com.cartisan.core.context.RequestContext;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.business.identity.domain.aggregate.Account;
import com.aieducenter.aiplatform.business.identity.domain.repository.AccountRepository;
import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
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
 *
 * <p>#156 四维检索＋owner 显示名在本类钉死：状态多选（空选/单选/组合/全选）、
 * 创建时间区间（含端点边界）、externalId 换算过滤（未命中＝空清单）、订单号
 * 精确、行带 ownerDisplayName（账号缺档为 null）、分页上界截断与越界——过滤
 * 走数据库查询路径（真库 WHERE 生效即为证）。</p>
 *
 * <p>#157 运营取消写口在本类一链钉死：已报价单带原因＋操作者头签名取消 →
 * 订单落已取消＋库列留痕（JdbcTemplate）→ 后台详情呈现留痕 → 同项目再下单
 * 成功（解冻回迭代）；待报价无头取消留原因落空操作者；已支付/已归档/已取消
 * 被 ORD_005 拦；缺/空白/超长原因被 ORD_013/014 拒。用户面读面不携带原因归
 * {@code OrderAppServiceTest} 钉死。</p>
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
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ord_orders");
        jdbcTemplate.update("DELETE FROM idn_accounts");
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

    // ---------- #157：运营取消写口（原因必填＋操作者留痕＋解冻再下单） ----------

    @Test
    void given_quoted_order_when_signed_cancel_with_reason_and_operator_then_unfrozen_and_replaceable()
            throws Exception {
        // 一链：已报价单运营取消（带原因＋操作者头）→ 订单落已取消 → 库列留痕 →
        // 后台详情呈现留痕 → 同项目再下单成功（解冻回迭代）
        OrderResponse order = placeOrder();
        appService.submitQuote(Long.parseLong(order.id()), 128000L, "首版报价", null);
        stubProjectName();
        String cancelPath = "/api/backoffice/orders/" + order.id() + "/cancel";
        String body = "{\"reason\":\"用户改需求，终止报价流程\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(cancelPath).contentType(MediaType.APPLICATION_JSON).content(body),
                        cancelPath, body)
                        .header("X-User-Id", OPERATOR_ID_STR)
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(5))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());

        // 库内留痕（JdbcTemplate 断言）：状态迁移＋取消时点＋原因＋操作者两列
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, cancelled_at, cancel_reason, cancel_operator_id, "
                        + "cancel_operator_name FROM ord_orders WHERE id = ?",
                Long.parseLong(order.id()));
        assertThat(row.get("status")).isEqualTo(OrderStatus.CANCELLED.getCode());
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(row.get("cancel_reason")).isEqualTo("用户改需求，终止报价流程");
        assertThat(row.get("cancel_operator_id")).isEqualTo(OPERATOR_ID_STR);
        assertThat(row.get("cancel_operator_name")).isEqualTo(OPERATOR_NAME);

        // 后台详情呈现留痕（运营内部读面可见；用户面不携带由 OrderAppServiceTest 钉死）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders/" + order.id()),
                        "/api/backoffice/orders/" + order.id(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(5))
                .andExpect(jsonPath("$.data.cancelReason").value("用户改需求，终止报价流程"))
                .andExpect(jsonPath("$.data.cancelOperatorId").value(OPERATOR_ID_STR))
                .andExpect(jsonPath("$.data.cancelOperatorName").value(OPERATOR_NAME));

        // 项目解冻：同项目再下单成功（旧单已终态，未终结唯一索引不再占位）
        OrderResponse reordered = placeOrder();
        assertThat(reordered.id()).isNotEqualTo(order.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ord_orders WHERE project_id = ? AND id != ?",
                Integer.class, PROJECT_ID, Long.parseLong(order.id()))).isEqualTo(1);
    }

    @Test
    void given_pending_order_when_signed_cancel_without_operator_headers_then_reason_only()
            throws Exception {
        // 待报价可达（无需先报价）；v0 形制无操作者头 → 原因照留、操作者落空
        OrderResponse order = placeOrder();
        String cancelPath = "/api/backoffice/orders/" + order.id() + "/cancel";
        String body = "{\"reason\":\"拒单：需求超出交付范围\"}";

        mockMvc.perform(BackofficeSignatures.signed(
                        post(cancelPath).contentType(MediaType.APPLICATION_JSON).content(body),
                        cancelPath, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(5));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT cancel_reason, cancel_operator_id, cancel_operator_name "
                        + "FROM ord_orders WHERE id = ?",
                Long.parseLong(order.id()));
        assertThat(row.get("cancel_reason")).isEqualTo("拒单：需求超出交付范围");
        assertThat(row.get("cancel_operator_id")).isNull();
        assertThat(row.get("cancel_operator_name")).isNull();
    }

    @Test
    void given_paid_archived_or_cancelled_order_when_signed_cancel_then_409_ord005()
            throws Exception {
        // 非未支付态被既有守卫拦（ORD_005，无新增状态机回边）；逐态钉死，各用独立
        // 项目（已支付非终态，占未终结名额）
        long[] projectIds = {910601L, 910602L, 910603L};
        OrderStatus[] states = {OrderStatus.PAID, OrderStatus.ARCHIVED, OrderStatus.CANCELLED};
        for (int i = 0; i < states.length; i++) {
            OrderResponse order = placeOrder(projectIds[i]);
            jdbcTemplate.update("UPDATE ord_orders SET status = ? WHERE id = ?",
                    states[i].getCode(), Long.parseLong(order.id()));
            String cancelPath = "/api/backoffice/orders/" + order.id() + "/cancel";
            String body = "{\"reason\":\"迟到\"}";

            mockMvc.perform(BackofficeSignatures.signed(
                            post(cancelPath).contentType(MediaType.APPLICATION_JSON).content(body),
                            cancelPath, body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("订单已支付或已终结，无法取消"));

            // 状态不被破坏，留痕不落半截
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM ord_orders WHERE id = ?", Integer.class,
                    Long.parseLong(order.id()))).isEqualTo(states[i].getCode());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT cancel_reason FROM ord_orders WHERE id = ?", String.class,
                    Long.parseLong(order.id()))).isNull();
        }
    }

    @Test
    void given_missing_blank_or_overlong_reason_when_signed_cancel_then_400() throws Exception {
        // 原因必填（ORD_013）与超长（ORD_014）：聚合守卫经全局处理器映射；订单不动
        OrderResponse order = placeOrder();
        String cancelPath = "/api/backoffice/orders/" + order.id() + "/cancel";
        String overlong = "{\"reason\":\"" + "长".repeat(1001) + "\"}";

        for (String body : new String[] {"{}", "{\"reason\":\"\"}", "{\"reason\":\" \"}", overlong}) {
            mockMvc.perform(BackofficeSignatures.signed(
                            post(cancelPath).contentType(MediaType.APPLICATION_JSON).content(body),
                            cancelPath, body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, cancel_reason FROM ord_orders WHERE id = ?",
                Long.parseLong(order.id())))
                .containsEntry("status", OrderStatus.PENDING_QUOTE.getCode())
                .containsEntry("cancel_reason", null);
    }

    // ---------- #156：四维检索＋owner 显示名（真库 WHERE 生效即为证） ----------

    @Test
    void given_orders_across_statuses_when_filter_by_comma_multi_then_only_matching_rows()
            throws Exception {
        // 三态在库：待报价 / 已报价（真报价链） / 已取消（真取消链）
        OrderResponse pending = placeOrder(910101L);
        OrderResponse quoted = placeOrder(910102L);
        appService.submitQuote(Long.parseLong(quoted.id()), 99000L, null, null);
        OrderResponse cancelled = placeOrder(910103L);
        appService.cancel(Long.parseLong(cancelled.id()));

        // 组合多选 1,5：待报价＋已取消可见、已报价排除
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("status", "1,5"),
                        "/api/backoffice/orders?status=1,5", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(pending.id(), cancelled.id())))
                .andExpect(jsonPath("$.data.total").value("2"));

        // 单选 1：仅待报价
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("status", "1"),
                        "/api/backoffice/orders?status=1", null))
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(pending.id())))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 全选 1,2,3,4,5 ＝ 全量（IN 全集不丢行）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("status", "1,2,3,4,5"),
                        "/api/backoffice/orders?status=1,2,3,4,5", null))
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(pending.id(), quoted.id(), cancelled.id())))
                .andExpect(jsonPath("$.data.total").value("3"));

        // 空选（无参）＝全量
        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/orders"),
                        "/api/backoffice/orders", null))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.total").value("3"));
    }

    @Test
    void given_orders_created_at_known_times_when_filter_by_range_then_endpoints_inclusive()
            throws Exception {
        OrderResponse first = placeOrder(910201L);
        OrderResponse second = placeOrder(910202L);
        LocalDateTime firstAt = createdAtOf(first.id());
        LocalDateTime secondAt = createdAtOf(second.id());
        // 边界断言依赖两单时点可分（两笔完整事务隔开，PG 微秒精度下必然成立）；
        // 假设显式化，环境异常时明确失败而非 jsonPath 疑难杂症
        assertThat(secondAt).isAfter(firstAt);

        // createdFrom＝较早单的时点（下界含端点）：两单都在
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders")
                                .queryParam("createdFrom", firstAt.toString()),
                        "/api/backoffice/orders?createdFrom=" + firstAt, null))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.total").value("2"));

        // createdTo＝较早单的时点（上界含端点）：仅较早单（较晚单被上界排除）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders")
                                .queryParam("createdTo", firstAt.toString()),
                        "/api/backoffice/orders?createdTo=" + firstAt, null))
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(first.id())))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 闭区间 [首单时点, 次单时点]：两单都在（两端点都含）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders")
                                .queryParam("createdFrom", firstAt.toString())
                                .queryParam("createdTo", secondAt.toString()),
                        "/api/backoffice/orders?createdFrom=" + firstAt
                                + "&createdTo=" + secondAt, null))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.total").value("2"));

        // 下界抬到次单时点：仅次单（首单严格早于下界被排除）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders")
                                .queryParam("createdFrom", secondAt.toString()),
                        "/api/backoffice/orders?createdFrom=" + secondAt, null))
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(second.id())))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    @Test
    void given_order_placed_by_known_account_when_filter_by_external_id_then_row_with_owner_name()
            throws Exception {
        // 真账号 + 该账号会话上下文里下单（ownerAccountId 落值）+ 一笔无主单
        Account owner = accountRepository.save(Account.register("sub-156-a", "运营查档·李四"));
        OrderResponse owned = placeOrderAs(910301L, owner.getId());
        OrderResponse anonymous = placeOrder(910302L);

        // externalId 命中：只有该账号的单，行带显示名（运营不用二次查档）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("externalId", "sub-156-a"),
                        "/api/backoffice/orders?externalId=sub-156-a", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(owned.id())))
                .andExpect(jsonPath("$.data.items[0].ownerDisplayName").value("运营查档·李四"))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 全量行：有主单带名、无主单容缺 null（下单账号可空，不炸）。
        // TSID 倒序＝新单在前：匿名单后下在前（items[0] 无名）、有主单在后
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders"), "/api/backoffice/orders", null))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(owned.id(), anonymous.id())))
                .andExpect(jsonPath("$.data.items[0].ownerDisplayName").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].ownerDisplayName").value("运营查档·李四"));

        // externalId 未命中（用户在我方无建档）＝无单可检：如实空清单，非错误
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("externalId", "sub-never-registered"),
                        "/api/backoffice/orders?externalId=sub-never-registered", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));

        // 账号已删：externalId 换算必落空 → 过滤面同「未建档」语义＝空清单；
        // 订单本身是交易记录仍可见（不带账号维度看），取名容缺 ownerDisplayName
        // 落 null 不炸（有主单悬空引用＋无主单，两行皆 null）
        accountRepository.deleteById(owner.getId());
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("externalId", "sub-156-a"),
                        "/api/backoffice/orders?externalId=sub-156-a", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));
        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/orders"),
                        "/api/backoffice/orders", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id",
                        containsInAnyOrder(owned.id(), anonymous.id())))
                .andExpect(jsonPath("$.data.items[0].ownerDisplayName").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].ownerDisplayName").value(nullValue()));
    }

    @Test
    void given_orders_when_filter_by_order_id_then_exact_hit_and_misses_are_empty()
            throws Exception {
        OrderResponse order = placeOrder(910401L);
        placeOrder(910402L);

        // 订单号精确命中：单行
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("orderId", order.id()),
                        "/api/backoffice/orders?orderId=" + order.id(), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id", containsInAnyOrder(order.id())))
                .andExpect(jsonPath("$.data.total").value("1"));

        // 查无此号：空清单
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("orderId", "123"),
                        "/api/backoffice/orders?orderId=123", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));

        // 非数值订单号（过滤值非寻址语义）：不可能命中任何 TSID → 空清单，不 500
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("orderId", "not-a-tsid"),
                        "/api/backoffice/orders?orderId=not-a-tsid", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));
    }

    @Test
    void given_101_orders_when_page_bounds_then_size_capped_and_far_page_empty() throws Exception {
        // 101 单 > size 上界 100：上界截断可证（items 恰 100、total 仍 101）
        for (long projectId = 910500L; projectId < 910500L + 101; projectId++) {
            placeOrder(projectId);
        }

        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders")
                                .queryParam("size", "500").queryParam("page", "1"),
                        "/api/backoffice/orders?size=500&page=1", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(100)))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.total").value("101"));

        // page 越界：空页不炸、total 原样回（分页元数据完整）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders")
                                .queryParam("size", "20").queryParam("page", "99"),
                        "/api/backoffice/orders?size=20&page=99", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(99))
                .andExpect(jsonPath("$.data.total").value("101"));
    }

    // -------- 夹具 --------

    /** 详情读面跨 BC 取项目名（软引用）：统一 stub，缺档为 null 也成立。 */
    private void stubProjectName() {
        when(projectQueryAppService.namesOf(List.of(PROJECT_ID)))
                .thenReturn(Map.of(PROJECT_ID, "seam 测试项目"));
    }

    /** 经真应用服务下单（真库写入、快照冻结），返回带 TSID 的订单回执 */
    private OrderResponse placeOrder() {
        return placeOrder(PROJECT_ID);
    }

    /** 指定项目的下单夹具（多单场景各用独立项目——同项目至多一个未终结单）。 */
    private OrderResponse placeOrder(long projectId) {
        when(projectQueryAppService.detail(projectId)).thenReturn(new ProjectDetailResponse(
                Long.toString(projectId), "seam 测试项目", ProjectType.WEBSITE, "官网", "9100",
                ProjectStatus.IN_PROGRESS, ProjectStatus.IN_PROGRESS.getName(), false,
                LocalDateTime.of(2026, 9, 13, 9, 0), null, null, null, null, null));
        when(projectQueryAppService.prd(projectId)).thenReturn(new PrdResponse(
                Long.toString(projectId), PRD, Instant.parse("2026-09-13T01:00:00Z")));
        return appService.place(projectId);
    }

    /** 以指定账号为下单人（RequestContext 会话内下单，ownerAccountId 落值）。 */
    private OrderResponse placeOrderAs(long projectId, Long accountId) throws Exception {
        // 8 位构造位参中 userId 居第 5 位（requestId/clientIp/callerAppId/callerAppName
        // 之后），位参标注防错读
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, /* userId */ accountId,
                        null, null, null),
                () -> placeOrder(projectId));
    }

    /** 库内下单时点（边界断言以库值为准，不依赖应用时钟）。 */
    private LocalDateTime createdAtOf(String orderId) {
        Timestamp createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM ord_orders WHERE id = ?",
                Timestamp.class, Long.parseLong(orderId));
        assertThat(createdAt).isNotNull();
        return createdAt.toLocalDateTime();
    }
}
