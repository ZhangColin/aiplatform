package com.semgravio.aiplatform.business.billing.endpoints.controller;

import java.util.List;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.config.JacksonConfiguration;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.semgravio.aiplatform.business.billing.application.BillingQueryAppService;
import com.semgravio.aiplatform.business.billing.application.CheckoutAppService;
import com.semgravio.aiplatform.business.billing.application.CheckoutAppService.CheckoutReceipt;
import com.semgravio.aiplatform.business.billing.application.dto.command.CheckoutTopupCommand;
import com.semgravio.aiplatform.business.billing.application.dto.response.AccountBillingView;
import com.semgravio.aiplatform.business.billing.application.dto.response.BillingEntryView;
import com.semgravio.aiplatform.business.billing.application.dto.response.PoolView;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.web.ErrorCodePrefix;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模拟收银台与我的账户端点契约（#534 计费④）：ApiResponse 信封、数字域码
 * （BILL_019→8019）、准入码校验独立端点（正确 200/错误 400 带如实引导 message）、
 * 充值端点按会话账户入账（userId 从 RequestContext 取）、我的账户读面投影。
 */
@WebMvcTest({CheckoutController.class, MeBillingController.class})
@Import({CheckoutControllerTest.ExceptionAdviceConfig.class, JacksonConfiguration.class})
class CheckoutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutAppService checkoutAppService;

    @MockitoBean
    private BillingQueryAppService queryAppService;

    /** MVC 契约测试不走登录链——夹具直接注 RequestContext（userId=7L）。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 7L, "billing-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_valid_code_when_access_code_then_ok() throws Exception {
        performAsUser(post("/api/checkout/access-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"DEMO-2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(checkoutAppService).validateAccessCode("DEMO-2026");
    }

    @Test
    void given_wrong_code_when_access_code_then_400_with_numeric_code_and_guidance() throws Exception {
        org.mockito.Mockito.doThrow(new com.cartisan.core.exception.ApplicationException(
                BillingMessage.ACCESS_CODE_INVALID))
                .when(checkoutAppService).validateAccessCode(any());

        performAsUser(post("/api/checkout/access-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WRONG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCodePrefix.numericOf(BillingMessage.ACCESS_CODE_INVALID)))
                .andExpect(jsonPath("$.message").value(BillingMessage.ACCESS_CODE_INVALID.message()));

        // 缺失请求体同拦（空体 → null code → BILL_019）
        org.mockito.Mockito.doThrow(new com.cartisan.core.exception.ApplicationException(
                BillingMessage.ACCESS_CODE_INVALID))
                .when(checkoutAppService).validateAccessCode(isNull());
        performAsUser(post("/api/checkout/access-code")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_logged_in_when_topup_then_receipt_envelope() throws Exception {
        when(checkoutAppService.topupAccount(anyLong(), any(CheckoutTopupCommand.class)))
                .thenReturn(new CheckoutReceipt(new PoolView("8012345678901", 20_000L), 8012345678902L));

        performAsUser(post("/api/checkout/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessCode\":\"DEMO-2026\",\"amountFen\":20000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pool.poolId").value("8012345678901"))
                .andExpect(jsonPath("$.data.pool.balance").value(20000))
                .andExpect(jsonPath("$.data.entryId").value(8012345678902L));

        verify(checkoutAppService).topupAccount(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(cmd ->
                        "DEMO-2026".equals(cmd.accessCode()) && cmd.amountFen() == 20_000L));
    }

    @Test
    void given_account_billing_when_me_billing_then_view_envelope() throws Exception {
        when(queryAppService.accountView(7L)).thenReturn(new AccountBillingView(
                new PoolView("8012345678903", 5_000L),
                List.of(new BillingEntryView("8012345678904", 1, "入账", null,
                        5_000L, 5_000L, "模拟收银台充值", null,
                        java.time.LocalDateTime.of(2026, 11, 16, 12, 0)))));

        performAsUser(get("/api/me/billing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountPool.poolId").value("8012345678903"))
                .andExpect(jsonPath("$.data.accountPool.balance").value(5000))
                .andExpect(jsonPath("$.data.entries[0].kind").value(1))
                .andExpect(jsonPath("$.data.entries[0].kindName").value("入账"))
                .andExpect(jsonPath("$.data.entries[0].amount").value(5000))
                .andExpect(jsonPath("$.data.entries[0].note").value("模拟收银台充值"));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
