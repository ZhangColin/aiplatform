package com.aieducenter.aiplatform.business.order.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cartisan.core.exception.DomainException;
import com.cartisan.openapi.config.CartisanOpenapiAutoConfiguration;
import com.cartisan.web.config.BaseEnumConverter;
import com.cartisan.web.config.JacksonConfiguration;
import com.cartisan.web.exception.GlobalExceptionHandler;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.backoffice.BackofficeSignatureTestConfig;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.business.order.application.BackofficeOrderAppService;
import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderDetailResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderSummaryResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficePriceEntryResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.PriceEntryResponse;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;
import com.aieducenter.aiplatform.config.WebMvcConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台订单机机面 REST 契约（#29 交易环②，#157 扩取消写口）：五端点形状 +
 * cartisan-openapi 五头 HMAC 签名闸全链（真 Filter + 真 Interceptor + 真签名计算；凭据源与 nonce 存储用
 * {@link BackofficeSignatureTestConfig}、签名头计算用 {@link BackofficeSignatures}
 * ——均 #152 seam 测试件，全上下文走法见 {@code BackofficeSeamContractTest}）
 * ——无签名/错签/过期时间戳逐一拒绝；该前缀已排除会话拦截（无用户上下文的签名
 * 请求可达控制器，即排除生效的活体证明）。业务错误码 ORD_007/ORD_001 按全局
 * 处理器映射验。
 */
@WebMvcTest(BackofficeOrderController.class)
@ImportAutoConfiguration(CartisanOpenapiAutoConfiguration.class)
@Import({WebMvcConfig.class, JacksonConfiguration.class,
        BackofficeOrderControllerTest.ExceptionAdviceConfig.class,
        BackofficeSignatureTestConfig.class,
        BackofficeOrderControllerTest.EnumParamBindingConfig.class})
class BackofficeOrderControllerTest {

    private static final String API_SECRET = BackofficeSignatureTestConfig.API_SECRET;
    private static final String PRD = "# PRD\n\n需求背景：契约测试。";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackofficeOrderAppService queryAppService;

    @MockitoBean
    private OrderAppService appService;

    // ---------- 签名闸：正例 ----------

    @Test
    void given_signed_request_when_get_orders_then_page_envelope_without_session()
            throws Exception {
        // 无用户会话（不注 RequestContext）+ 合法签名 → 放行：会话拦截排除 +
        // 机机签名接管的组合行为在此活体验证
        when(queryAppService.orders(List.of(OrderStatus.PENDING_QUOTE), null, null,
                null, null, new Pagination(1, 20, null))).thenReturn(
                new PageResponse<>(List.of(summary()), 1, 1, 20));

        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/orders")
                        .queryParam("status", "1").queryParam("page", "1").queryParam("size", "20"),
                "/api/backoffice/orders?status=1&page=1&size=20", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("900"))
                .andExpect(jsonPath("$.data.items[0].projectName").value("宠物店官网"))
                .andExpect(jsonPath("$.data.items[0].ownerExternalId").value("sub-user-1"))
                .andExpect(jsonPath("$.data.items[0].ownerDisplayName").value("文野"))
                .andExpect(jsonPath("$.data.items[0].status").value(1))
                .andExpect(jsonPath("$.data.total").value("1")) // Long 全局序列化为字符串
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    // ---------- #156：四维检索的参数契约 ----------

    @Test
    void given_signed_request_when_status_comma_multi_and_time_range_then_bound_and_forwarded()
            throws Exception {
        // 逗号分隔多选是唯一签名安全的 status 形态（协议按参数名去重，重复参数
        // 只签末值）；时间区间 ISO-8601。两者绑定后原样进应用服务
        when(queryAppService.orders(List.of(OrderStatus.PENDING_QUOTE, OrderStatus.CANCELLED),
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 30, 23, 59, 59),
                "sub-user-1", "900", new Pagination(1, 20, null))).thenReturn(
                new PageResponse<>(List.of(summary()), 1, 1, 20));

        String pathWithQuery = "/api/backoffice/orders?status=1,5"
                + "&createdFrom=2026-09-01T00:00:00&createdTo=2026-09-30T23:59:59"
                + "&externalId=sub-user-1&orderId=900&page=1&size=20";
        mockMvc.perform(BackofficeSignatures.signed(get(pathWithQuery), pathWithQuery, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void given_signed_malformed_time_range_when_get_orders_then_404_type_mismatch() throws Exception {
        // 时间类型不匹配按框架口径 404（BaseCodeMessage.NOT_FOUND，不暴露转换细节）
        mockMvc.perform(BackofficeSignatures.signed(
                        get("/api/backoffice/orders").queryParam("createdFrom", "not-a-time"),
                        "/api/backoffice/orders?createdFrom=not-a-time", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
        verify(queryAppService, never()).orders(any(), any(), any(), any(), any(), any());
    }

    @Test
    void given_signed_request_when_get_detail_then_full_quote_basis() throws Exception {
        when(queryAppService.detail(900L)).thenReturn(backofficeDetail());

        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/orders/900"), "/api/backoffice/orders/900", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("900"))
                .andExpect(jsonPath("$.data.projectName").value("宠物店官网"))
                .andExpect(jsonPath("$.data.ownerExternalId").value("sub-user-1"))
                .andExpect(jsonPath("$.data.ownerDisplayName").value("文野"))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.amount").value("128000"))
                .andExpect(jsonPath("$.data.note").value("首版报价"))
                // #155 价目历史：新 → 旧，新条目带操作者、存量条目操作者为空
                .andExpect(jsonPath("$.data.priceEntries.length()").value(2))
                .andExpect(jsonPath("$.data.priceEntries[0].amount").value("99000"))
                .andExpect(jsonPath("$.data.priceEntries[0].operatorId").value("700100"))
                .andExpect(jsonPath("$.data.priceEntries[0].operatorName").value("运营·小刘"))
                .andExpect(jsonPath("$.data.priceEntries[1].amount").value("128000"))
                .andExpect(jsonPath("$.data.priceEntries[1].operatorId").value(nullValue()))
                .andExpect(jsonPath("$.data.priceEntries[1].operatorName").value(nullValue()))
                .andExpect(jsonPath("$.data.prdSnapshot").value(PRD))
                .andExpect(jsonPath("$.data.quotedAt").value("2026-09-01T10:00:00"))
                // #158 归档操作者：未归档/支付链自动归档为空（字段位与取消留痕对称）
                .andExpect(jsonPath("$.data.archiveOperatorId").value(nullValue()))
                .andExpect(jsonPath("$.data.archiveOperatorName").value(nullValue()));
    }

    @Test
    void given_signed_request_when_get_source_package_then_gzip_stream() throws Exception {
        byte[] bytes = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x64};
        when(queryAppService.sourcePackage(900L)).thenReturn(bytes);

        byte[] body = mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/orders/900/source-package"),
                        "/api/backoffice/orders/900/source-package", null))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/gzip"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).containsExactly(bytes); // 真实文件字节（不走 JSON 信封）
    }

    @Test
    void given_signed_request_when_post_quote_then_reprice_response() throws Exception {
        when(appService.submitQuote(eq(900L), eq(99000L), eq("调整：去掉导入功能"), any(Operator.class)))
                .thenReturn(quotedOrder());

        String body = "{\"amount\":99000,\"note\":\"调整：去掉导入功能\"}";
        mockMvc.perform(BackofficeSignatures.signed(post("/api/backoffice/orders/900/quote")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        "/api/backoffice/orders/900/quote", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.amount").value("99000"))
                .andExpect(jsonPath("$.data.note").value("调整：去掉导入功能"))
                .andExpect(jsonPath("$.data.priceEntries.length()").value(2))
                .andExpect(jsonPath("$.data.priceEntries[0].amount").value("99000"))
                .andExpect(jsonPath("$.data.priceEntries[1].amount").value("128000"));

        // 操作者随请求透传进应用服务（MVC 切片无 RequestContext 绑定 → 落空形 null）
        verify(appService).submitQuote(eq(900L), eq(99000L), eq("调整：去掉导入功能"),
                eq(new Operator(null, null)));
    }

    @Test
    void given_signed_request_when_post_cancel_then_cancelled_response() throws Exception {
        // #157 运营取消：命令体携必填原因，透传头操作者经 RequestContext 落空形
        // （MVC 切片无绑定 → null）转交应用服务
        when(appService.cancelByBackoffice(eq(900L), eq("用户改需求，终止报价流程"),
                any(Operator.class)))
                .thenReturn(cancelledOrder());

        String body = "{\"reason\":\"用户改需求，终止报价流程\"}";
        mockMvc.perform(BackofficeSignatures.signed(post("/api/backoffice/orders/900/cancel")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        "/api/backoffice/orders/900/cancel", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(5))
                .andExpect(jsonPath("$.data.cancelledAt").value("2026-09-01T12:00:00"));

        verify(appService).cancelByBackoffice(eq(900L), eq("用户改需求，终止报价流程"),
                eq(new Operator(null, null)));
    }

    @Test
    void given_signed_request_when_post_retry_archive_then_archived_response() throws Exception {
        // #158 重试归档：无命令体（守卫即幂等），透传头操作者经 RequestContext 落空形
        // （MVC 切片无绑定 → null）转交应用服务
        when(appService.retryArchive(eq(900L), any(Operator.class)))
                .thenReturn(archivedOrder());

        mockMvc.perform(BackofficeSignatures.signed(post("/api/backoffice/orders/900/retry-archive"),
                        "/api/backoffice/orders/900/retry-archive", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(4))
                .andExpect(jsonPath("$.data.archivedAt").value("2026-09-01T13:00:00"));

        verify(appService).retryArchive(eq(900L), eq(new Operator(null, null)));
    }

    @Test
    void given_valid_signature_when_retry_archive_non_paid_order_then_409_ord012() throws Exception {
        // 签名合法而业务被拒：聚合守卫 DomainException → 全局处理器 → 409 ORD_012
        // （幂等由守卫保证：重复触发/非已支付态同拦）
        when(appService.retryArchive(eq(900L), any(Operator.class)))
                .thenThrow(new DomainException(OrderMessage.ORDER_ARCHIVE_NOT_ALLOWED));

        mockMvc.perform(BackofficeSignatures.signed(post("/api/backoffice/orders/900/retry-archive"),
                        "/api/backoffice/orders/900/retry-archive", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_ARCHIVE_NOT_ALLOWED.message()));
    }

    // ---------- 签名闸：反例（验收：无签名/错签被拒） ----------

    @Test
    void given_no_signature_headers_when_get_orders_then_401_signature_required() throws Exception {
        // Filter 只在带 X-Api-Key 时验签；全裸请求落到 @RequireSignature 强制闸
        mockMvc.perform(get("/api/backoffice/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    @Test
    void given_wrong_secret_when_get_orders_then_401_signature_mismatch() throws Exception {
        Map<String, String> headers = BackofficeSignatures.headers("/api/backoffice/orders", "", "wrong-secret");
        mockMvc.perform(BackofficeSignatures.apply(get("/api/backoffice/orders"), headers))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature mismatch"));
    }

    @Test
    void given_stale_timestamp_when_get_orders_then_401_timestamp_expired() throws Exception {
        // 签名自洽但时间戳过期：容差窗口（默认 300s）外的请求拒绝
        long stale = System.currentTimeMillis() / 1000 - 3600;
        Map<String, String> headers = BackofficeSignatures.headers("/api/backoffice/orders", "", API_SECRET, stale);
        mockMvc.perform(BackofficeSignatures.apply(get("/api/backoffice/orders"), headers))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Timestamp expired"));
    }

    @Test
    void given_valid_signature_when_quote_paid_order_then_409_ord007() throws Exception {
        // 签名合法而业务被拒：聚合守卫 DomainException → 全局处理器 → 409 ORD_007
        when(appService.submitQuote(eq(900L), eq(1000L), eq("迟到"), any(Operator.class)))
                .thenThrow(new DomainException(OrderMessage.ORDER_QUOTE_NOT_ALLOWED));

        String body = "{\"amount\":1000,\"note\":\"迟到\"}";
        mockMvc.perform(BackofficeSignatures.signed(post("/api/backoffice/orders/900/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body), "/api/backoffice/orders/900/quote", body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_QUOTE_NOT_ALLOWED.message()));
    }

    @Test
    void given_signed_malformed_order_id_when_get_detail_then_404_ord001() throws Exception {
        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/orders/abc"), "/api/backoffice/orders/abc", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    void given_signed_unknown_status_when_get_orders_then_400_with_legal_values() throws Exception {
        // 非法状态 code 在绑定层即 400（框架统一信封：带合法取值表），应用服务不被触达
        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/orders").queryParam("status", "99"),
                        "/api/backoffice/orders?status=99", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("status 取值 99 非法，合法取值：1=待报价, 2=已报价, 3=已支付, 4=已归档, 5=已取消"));
        verify(queryAppService, never()).orders(any(), any(), any(), any(), any(), any());
    }

    // ---------- 夹具 ----------

    private static BackofficeOrderSummaryResponse summary() {
        return new BackofficeOrderSummaryResponse("900", "100", "宠物店官网", "sub-user-1", "文野",
                OrderStatus.PENDING_QUOTE, "待报价", null, null,
                LocalDateTime.of(2026, 9, 1, 9, 0), null);
    }

    private static BackofficeOrderDetailResponse backofficeDetail() {
        return new BackofficeOrderDetailResponse("900", "100", "宠物店官网", "sub-user-1", "文野",
                OrderStatus.QUOTED, "已报价", 128000L, "CNY", "首版报价",
                List.of(
                        new BackofficePriceEntryResponse("902", 99000L, "CNY", "调整：去掉导入功能",
                                "700100", "运营·小刘", LocalDateTime.of(2026, 9, 1, 11, 0)),
                        new BackofficePriceEntryResponse("901", 128000L, "CNY", "首版报价",
                                null, null, LocalDateTime.of(2026, 9, 1, 10, 0))),
                PRD,
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 1, 10, 0),
                null, null, null, null, null, null, null, null);
    }

    private static OrderResponse quotedOrder() {
        return new OrderResponse("900", "100", OrderStatus.QUOTED, "已报价",
                99000L, "CNY", "调整：去掉导入功能", LocalDateTime.of(2026, 9, 1, 10, 0),
                List.of(
                        new PriceEntryResponse("902", 99000L, "CNY", "调整：去掉导入功能",
                                LocalDateTime.of(2026, 9, 1, 11, 0)),
                        new PriceEntryResponse("901", 128000L, "CNY", "首版报价",
                                LocalDateTime.of(2026, 9, 1, 10, 0))),
                LocalDateTime.of(2026, 9, 1, 9, 0), null, null, null);
    }

    /** #157 取消回执：用户面同构（无取消原因——运营内部口径不进用户面响应形）。 */
    private static OrderResponse cancelledOrder() {
        return new OrderResponse("900", "100", OrderStatus.CANCELLED, "已取消",
                128000L, "CNY", "首版报价", LocalDateTime.of(2026, 9, 1, 10, 0),
                List.of(new PriceEntryResponse("901", 128000L, "CNY", "首版报价",
                        LocalDateTime.of(2026, 9, 1, 10, 0))),
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 1, 12, 0),
                null, null);
    }

    /** #158 重试归档回执：用户面同构（归档操作者不进用户面响应形）。 */
    private static OrderResponse archivedOrder() {
        return new OrderResponse("900", "100", OrderStatus.ARCHIVED, "已归档",
                128000L, "CNY", "首版报价", LocalDateTime.of(2026, 9, 1, 10, 0),
                List.of(new PriceEntryResponse("901", 128000L, "CNY", "首版报价",
                        LocalDateTime.of(2026, 9, 1, 10, 0))),
                LocalDateTime.of(2026, 9, 1, 9, 0), null, LocalDateTime.of(2026, 9, 1, 11, 0),
                LocalDateTime.of(2026, 9, 1, 13, 0));
    }

    /** MVC 切片不含 cartisan-web autoconfig，手动注册其全局异常处理器。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    /**
     * Integer code ↔ BaseEnum 的参数绑定（生产由 cartisan-web 注册，MVC 切片
     * 不含其 autoconfig，手动对齐——同 ProjectControllerTest 形制）。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class EnumParamBindingConfig implements WebMvcConfigurer {

        @Override
        public void addFormatters(FormatterRegistry registry) {
            registry.addConverterFactory(new BaseEnumConverter());
        }
    }
}
