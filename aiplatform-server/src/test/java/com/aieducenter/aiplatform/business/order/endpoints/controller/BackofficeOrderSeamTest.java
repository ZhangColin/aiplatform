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
 */
@BackofficeSeamTest
class BackofficeOrderSeamTest {

    private static final long PROJECT_ID = 900100L;
    private static final String PRD = "# PRD\n\n需求背景：后台 seam。";

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

    // -------- 夹具 --------

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
