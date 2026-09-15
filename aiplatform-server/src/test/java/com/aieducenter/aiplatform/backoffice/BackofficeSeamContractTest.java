package com.aieducenter.aiplatform.backoffice;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.business.order.application.BackofficeOrderAppService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台机机面 seam 契约（#152 底座验收）：MockMvc 穿完整生产过滤链（cartisan
 * RequestContextFilter 绑操作者头 → CachingRequestBody → SignatureVerification
 * 验签 → BffSessionContextFilter 对该前缀豁免 → ApiAuthInterceptor 排除 →
 * SignatureVerificationInterceptor 强制闸），验两件事——
 *
 * <ol>
 * <li>带 {@code X-User-Id}/{@code X-User-Name} 的签名请求穿链后上下文中操作者
 * 可读：落点观察在 v0 订单清单端点的应用服务缝（最小探针，观察后即返空页）；
 * 操作者＝admin 侧管理员标识（TSID＋昵称），非本平台用户。</li>
 * <li>签名负例三连：无签名 / 错 secret / 过期时间戳 → 逐一 401（spec #151
 * 安全基线，本票起在 seam 上钉死，后续域票不重复验）。</li>
 * </ol>
 *
 * <p>v0 四端点真实数据全链全绿归 {@code BackofficeOrderSeamTest}（真应用服务＋
 * 真库）；端点 JSON 契约细粒度归 {@code BackofficeOrderControllerTest}（MVC 切片）。</p>
 */
@BackofficeSeamTest
class BackofficeSeamContractTest {

    /** 操作者测试身份：admin 侧管理员的 TSID＋昵称样例 */
    private static final long OPERATOR_ID = 700100L;
    private static final String OPERATOR_NAME = "运营·小刘";

    @Autowired
    private MockMvc mockMvc;

    /** 观察缝：v0 清单端点的应用服务——在此捕获穿链后的上下文操作者 */
    @MockitoBean
    private BackofficeOrderAppService queryAppService;

    @Test
    void given_signed_request_with_operator_headers_when_full_chain_then_operator_readable()
            throws Exception {
        AtomicReference<Long> seenUserId = new AtomicReference<>();
        AtomicReference<String> seenUserName = new AtomicReference<>();
        when(queryAppService.orders(null, null, null, null, null,
                new Pagination(1, 20, null))).thenAnswer(invocation -> {
            seenUserId.set(RequestContext.getUserId());
            seenUserName.set(RequestContext.getUserName());
            return new PageResponse<>(List.of(), 0, 1, 20);
        });

        mockMvc.perform(BackofficeSignatures
                        .signed(get("/api/backoffice/orders"), "/api/backoffice/orders", null)
                        .header("X-User-Id", Long.toString(OPERATOR_ID))
                        .header("X-User-Name", OPERATOR_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        assertThat(seenUserId.get()).isEqualTo(OPERATOR_ID);
        assertThat(seenUserName.get()).isEqualTo(OPERATOR_NAME);
    }

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
        mockMvc.perform(BackofficeSignatures.apply(get("/api/backoffice/orders"),
                        BackofficeSignatures.headers("/api/backoffice/orders", "", "wrong-secret")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature mismatch"));
    }

    @Test
    void given_stale_timestamp_when_get_orders_then_401_timestamp_expired() throws Exception {
        // 签名自洽但时间戳过期：容差窗口（默认 300s）外的请求拒绝
        long stale = System.currentTimeMillis() / 1000 - 3600;
        mockMvc.perform(BackofficeSignatures.apply(get("/api/backoffice/orders"),
                        BackofficeSignatures.headers("/api/backoffice/orders", "",
                                BackofficeSignatureTestConfig.API_SECRET, stale)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Timestamp expired"));
    }
}
