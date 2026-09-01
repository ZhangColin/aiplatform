package com.aieducenter.aiplatform.business.order.endpoints.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cartisan.core.exception.DomainException;
import com.cartisan.openapi.config.CartisanOpenapiAutoConfiguration;
import com.cartisan.openapi.nonce.InMemoryNonceRepository;
import com.cartisan.openapi.nonce.NonceRepository;
import com.cartisan.openapi.provider.ApiKeyInfo;
import com.cartisan.openapi.provider.ApiKeyProvider;
import com.cartisan.web.config.BaseEnumConverter;
import com.cartisan.web.config.JacksonConfiguration;
import com.cartisan.web.exception.GlobalExceptionHandler;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.business.order.application.BackofficeOrderAppService;
import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderDetailResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderSummaryResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.PriceEntryResponse;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.config.WebMvcConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台订单机机面 REST 契约（#29 交易环②）：四端点形状 + cartisan-openapi 五头
 * HMAC 签名闸全链（真 Filter + 真 Interceptor + 真签名计算，仅凭据源与 nonce
 * 存储替换为测试件）——无签名/错签/过期时间戳逐一拒绝；该前缀已排除会话拦截
 * （无用户上下文的签名请求可达控制器，即排除生效的活体证明）。业务错误码
 * ORD_007/ORD_001 按全局处理器映射验。
 */
@WebMvcTest(BackofficeOrderController.class)
@ImportAutoConfiguration(CartisanOpenapiAutoConfiguration.class)
@Import({WebMvcConfig.class, JacksonConfiguration.class,
        BackofficeOrderControllerTest.ExceptionAdviceConfig.class,
        BackofficeOrderControllerTest.SignatureTestConfig.class,
        BackofficeOrderControllerTest.EnumParamBindingConfig.class})
class BackofficeOrderControllerTest {

    private static final String API_KEY = "backoffice-test";
    private static final String API_SECRET = "test-secret";
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
        when(queryAppService.orders(OrderStatus.PENDING_QUOTE, 1, 20)).thenReturn(
                new PageResponse<>(List.of(summary()), 1, 1, 20));

        mockMvc.perform(signed(get("/api/backoffice/orders")
                        .queryParam("status", "1").queryParam("page", "1").queryParam("size", "20"),
                "/api/backoffice/orders?status=1&page=1&size=20", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("900"))
                .andExpect(jsonPath("$.data.items[0].projectName").value("宠物店官网"))
                .andExpect(jsonPath("$.data.items[0].status").value(1))
                .andExpect(jsonPath("$.data.total").value("1")) // Long 全局序列化为字符串
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void given_signed_request_when_get_detail_then_full_quote_basis() throws Exception {
        when(queryAppService.detail(900L)).thenReturn(backofficeDetail());

        mockMvc.perform(signed(get("/api/backoffice/orders/900"), "/api/backoffice/orders/900", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("900"))
                .andExpect(jsonPath("$.data.projectName").value("宠物店官网"))
                .andExpect(jsonPath("$.data.ownerDisplayName").value("文野"))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.amount").value("128000"))
                .andExpect(jsonPath("$.data.note").value("首版报价"))
                .andExpect(jsonPath("$.data.prdSnapshot").value(PRD))
                .andExpect(jsonPath("$.data.quotedAt").value("2026-09-01T10:00:00"));
    }

    @Test
    void given_signed_request_when_get_source_package_then_gzip_stream() throws Exception {
        byte[] bytes = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x64};
        when(queryAppService.sourcePackage(900L)).thenReturn(bytes);

        byte[] body = mockMvc.perform(signed(get("/api/backoffice/orders/900/source-package"),
                        "/api/backoffice/orders/900/source-package", null))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/gzip"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).containsExactly(bytes); // 真实文件字节（不走 JSON 信封）
    }

    @Test
    void given_signed_request_when_post_quote_then_reprice_response() throws Exception {
        when(appService.submitQuote(900L, 99000L, "调整：去掉导入功能"))
                .thenReturn(quotedOrder());

        String body = "{\"amount\":99000,\"note\":\"调整：去掉导入功能\"}";
        mockMvc.perform(signed(post("/api/backoffice/orders/900/quote")
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

        verify(appService).submitQuote(900L, 99000L, "调整：去掉导入功能");
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
        Map<String, String> headers = signatureHeaders("/api/backoffice/orders", "", "wrong-secret");
        mockMvc.perform(apply(get("/api/backoffice/orders"), headers))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature mismatch"));
    }

    @Test
    void given_stale_timestamp_when_get_orders_then_401_timestamp_expired() throws Exception {
        // 签名自洽但时间戳过期：容差窗口（默认 300s）外的请求拒绝
        long stale = System.currentTimeMillis() / 1000 - 3600;
        Map<String, String> headers = signatureHeaders("/api/backoffice/orders", "", API_SECRET, stale);
        mockMvc.perform(apply(get("/api/backoffice/orders"), headers))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Timestamp expired"));
    }

    @Test
    void given_valid_signature_when_quote_paid_order_then_409_ord007() throws Exception {
        // 签名合法而业务被拒：聚合守卫 DomainException → 全局处理器 → 409 ORD_007
        when(appService.submitQuote(900L, 1000L, "迟到"))
                .thenThrow(new DomainException(OrderMessage.ORDER_QUOTE_NOT_ALLOWED));

        String body = "{\"amount\":1000,\"note\":\"迟到\"}";
        mockMvc.perform(signed(post("/api/backoffice/orders/900/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body), "/api/backoffice/orders/900/quote", body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_QUOTE_NOT_ALLOWED.message()));
    }

    @Test
    void given_signed_malformed_order_id_when_get_detail_then_404_ord001() throws Exception {
        mockMvc.perform(signed(get("/api/backoffice/orders/abc"), "/api/backoffice/orders/abc", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    void given_signed_unknown_status_when_get_orders_then_400_ord010() throws Exception {
        // 非法状态 code 在绑定层即 400 ORD_010，应用服务不被触达
        mockMvc.perform(signed(get("/api/backoffice/orders").queryParam("status", "99"),
                        "/api/backoffice/orders?status=99", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_STATUS_FILTER_UNKNOWN.message()));
        verify(queryAppService, never()).orders(any(), anyInt(), anyInt());
    }

    // ---------- 签名工具（与 SignatureVerificationFilter 同构的客户端实现） ----------

    /** 按五头协议为请求盖章（时间戳/nonce 现生成，body 为空串即 GET 无体）。 */
    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder,
                                                        String pathWithQuery, String body) {
        return apply(builder, signatureHeaders(pathWithQuery, body == null ? "" : body, API_SECRET));
    }

    private static Map<String, String> signatureHeaders(String pathWithQuery, String body, String secret) {
        return signatureHeaders(pathWithQuery, body, secret, System.currentTimeMillis() / 1000);
    }

    private static Map<String, String> signatureHeaders(String pathWithQuery, String body, String secret,
                                                        long timestampSeconds) {
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = sha256Hex(body);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("apiKey", API_KEY);
        params.put("bodyDigest", bodyDigest);
        params.put("nonce", nonce);
        params.put("timestamp", Long.toString(timestampSeconds));
        int queryStart = pathWithQuery.indexOf('?');
        if (queryStart >= 0) {
            for (String pair : pathWithQuery.substring(queryStart + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0], kv[1]);
                }
            }
        }
        StringBuilder stringToSign = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!stringToSign.isEmpty()) {
                stringToSign.append('&');
            }
            stringToSign.append(e.getKey()).append('=').append(e.getValue());
        }
        return Map.of(
                "X-Api-Key", API_KEY,
                "X-Timestamp", Long.toString(timestampSeconds),
                "X-Nonce", nonce,
                "X-Body-Digest", bodyDigest,
                "X-Sign", hmacSha256Hex(secret, stringToSign.toString()));
    }

    private static MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder builder,
                                                       Map<String, String> headers) {
        headers.forEach(builder::header);
        return builder;
    }

    private static String sha256Hex(String body) {
        return hex(digest("SHA-256", body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] digest(String algorithm, byte[] input) {
        try {
            return java.security.MessageDigest.getInstance(algorithm).digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ---------- 夹具 ----------

    private static BackofficeOrderSummaryResponse summary() {
        return new BackofficeOrderSummaryResponse("900", "100", "宠物店官网",
                OrderStatus.PENDING_QUOTE, "待报价", null, null,
                LocalDateTime.of(2026, 9, 1, 9, 0), null);
    }

    private static BackofficeOrderDetailResponse backofficeDetail() {
        return new BackofficeOrderDetailResponse("900", "100", "宠物店官网", "文野",
                OrderStatus.QUOTED, "已报价", 128000L, "CNY", "首版报价", PRD,
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 1, 10, 0),
                null, null, null);
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

    /** MVC 切片不含 cartisan-web autoconfig，手动注册其全局异常处理器。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    /** 签名链测试件：固定凭据（替换远端 app-registry 取数）+ 内存 nonce 存储。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class SignatureTestConfig {

        @Bean
        public ApiKeyProvider apiKeyProvider() {
            return apiKey -> new ApiKeyInfo(API_KEY, "backoffice-test-app", API_SECRET);
        }

        @Bean
        public NonceRepository nonceRepository() {
            return new InMemoryNonceRepository();
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
