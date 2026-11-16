package com.semgravio.aiplatform.business.billing.endpoints.controller;

import java.util.List;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.config.JacksonConfiguration;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.semgravio.aiplatform.business.billing.application.BillingQueryAppService;
import com.semgravio.aiplatform.business.billing.application.CheckoutAppService;
import com.semgravio.aiplatform.business.billing.application.CheckoutAppService.CheckoutReceipt;
import com.semgravio.aiplatform.business.billing.application.dto.command.CheckoutTopupCommand;
import com.semgravio.aiplatform.business.billing.application.dto.response.BillingEntryView;
import com.semgravio.aiplatform.business.billing.application.dto.response.PoolView;
import com.semgravio.aiplatform.business.billing.application.dto.response.ProjectBillingView;
import com.semgravio.aiplatform.support.Tsid;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目账单端点契约（#534 计费④）：读面投影（池/计费态/充值目标/流水新先序）、
 * 项目上下文充值（userId＋projectId 双注）、TSID 寻址（畸形 id → 404 PRJ_001
 * 域码 4001——owner 守卫在运行面接管，切片以寻址解析口径钉）。
 */
@WebMvcTest(ProjectBillingController.class)
@Import({ProjectBillingControllerTest.ExceptionAdviceConfig.class, JacksonConfiguration.class})
class ProjectBillingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BillingQueryAppService queryAppService;

    @MockitoBean
    private CheckoutAppService checkoutAppService;

    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 7L, "billing-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_project_billing_when_get_then_view_envelope() throws Exception {
        when(queryAppService.projectView(anyLong())).thenReturn(new ProjectBillingView(
                new PoolView("8012345678901", 7_500L), true, 2, "项目余额", false, 2, "项目余额",
                List.of(new BillingEntryView("8012345678902", 1, "入账", "9012345678903",
                        7_500L, 7_500L, "模拟收银台充值", null,
                        java.time.LocalDateTime.of(2026, 11, 16, 12, 0)))));

        performAsUser(get("/api/projects/9012345678903/billing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pool.balance").value(7500))
                .andExpect(jsonPath("$.data.sourceSelected").value(true))
                .andExpect(jsonPath("$.data.deductionSource").value(2))
                .andExpect(jsonPath("$.data.deductionSourceName").value("项目余额"))
                .andExpect(jsonPath("$.data.paid").value(false))
                .andExpect(jsonPath("$.data.checkoutTarget").value(2))
                .andExpect(jsonPath("$.data.checkoutTargetName").value("项目余额"))
                .andExpect(jsonPath("$.data.entries[0].projectId").value("9012345678903"));

        verify(queryAppService).projectView(eq(9012345678903L));
    }

    @Test
    void given_topup_when_post_then_receipt_and_command_binding() throws Exception {
        when(checkoutAppService.topupProject(anyLong(), anyLong(), any(CheckoutTopupCommand.class)))
                .thenReturn(new CheckoutReceipt(new PoolView("8012345678904", 10_000L),
                        8012345678905L));

        performAsUser(post("/api/projects/9012345678903/billing/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessCode\":\"DEMO-2026\",\"amountFen\":10000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pool.balance").value(10000));

        verify(checkoutAppService).topupProject(eq(7L), eq(9012345678903L),
                org.mockito.ArgumentMatchers.argThat(cmd ->
                        "DEMO-2026".equals(cmd.accessCode()) && cmd.amountFen() == 10_000L));
    }

    @Test
    void given_malformed_id_when_get_then_404_project_not_found() throws Exception {
        performAsUser(get("/api/projects/not-a-tsid/billing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001)); // PRJ_001 数字域码
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
