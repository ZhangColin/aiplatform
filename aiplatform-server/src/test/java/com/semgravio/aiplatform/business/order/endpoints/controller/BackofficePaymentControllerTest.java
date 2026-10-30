package com.semgravio.aiplatform.business.order.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.openapi.config.CartisanOpenapiAutoConfiguration;

import com.semgravio.aiplatform.backoffice.BackofficeSignatureTestConfig;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.business.order.application.PaymentPlanAppService;
import com.semgravio.aiplatform.business.order.application.dto.command.VerifyPaymentCommand;
import com.semgravio.aiplatform.business.order.application.dto.response.BackofficePaymentPlanDetailResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.PaymentNodeResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.PaymentPlanResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.PaymentVerificationResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.PaymentVoucherContent;
import com.semgravio.aiplatform.business.order.application.dto.response.PaymentVoucherResponse;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台付款 REST 契约（#337 凭证核验承接）：ApiResponse 信封、计划详情/节点
 * 录入/到账核验/打回端点形状与错误码（ORD_036 无计划、ORD_039 录入无效、
 * ORD_041 核验录入无效、ORD_042 打回依据必填、ORD_044 重录窗口）＋操作者透传
 * 头落痕（X-User-Id/X-User-Name）＋凭证内容二进制直出。全链真库签名面归
 * seam 测试（BackofficeContractSeamTest 形制），本类钉控制器契约与异常映射。
 */
@WebMvcTest(BackofficePaymentController.class)
@ImportAutoConfiguration(CartisanOpenapiAutoConfiguration.class)
@Import({com.cartisan.web.config.JacksonConfiguration.class,
        BackofficeOrderControllerTest.ExceptionAdviceConfig.class,
        BackofficeSignatureTestConfig.class})
class BackofficePaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentPlanAppService appService;

    /** 签名请求（五头 HMAC 测试件，路径归一不含 query）——签名后再加自定义头（操作者透传头不进签名）；GET 无 body 不设 content。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedBuilder(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String canonicalPath, String body) {
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return BackofficeSignatures.signed(builder, canonicalPath, body);
    }

    @Test
    void given_plan_when_detail_then_full_face_with_trails_and_history() throws Exception {
        when(appService.detailForBackoffice(900L)).thenReturn(new BackofficePaymentPlanDetailResponse(
                planResponse(),
                List.of(new PaymentVerificationResponse("950", "911", 1, "到账核验", 4, 5,
                        1920000L, LocalDateTime.of(2026, 10, 17, 10, 0), "930", "银企对账一致",
                        "700100", "运营·小刘", LocalDateTime.of(2026, 10, 17, 10, 5))),
                List.of(new BackofficePaymentPlanDetailResponse.HistoryPlanResponse(
                        "909", "919", "SG-3ukqz0c0", 3, 4800000L, 0L,
                        LocalDateTime.of(2026, 10, 16, 9, 0)))));

        mockMvc.perform(signedBuilder(get("/api/backoffice/orders/900/payment-plan"),
                "/api/backoffice/orders/900/payment-plan", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.contractNo").value("SG-3ukqz0c0"))
                .andExpect(jsonPath("$.data.plan.nodes[0].remarkCode").value("SG-3ukqz0c0-K1"))
                .andExpect(jsonPath("$.data.trails[0].action").value(1))
                .andExpect(jsonPath("$.data.trails[0].fromStatus").value(4))
                .andExpect(jsonPath("$.data.trails[0].toStatus").value(5))
                .andExpect(jsonPath("$.data.trails[0].actualAmount").value(1920000))
                .andExpect(jsonPath("$.data.trails[0].operatorName").value("运营·小刘"))
                // 旧计划随旧合同留痕（历史行摘要）
                .andExpect(jsonPath("$.data.history[0].contractNo").value("SG-3ukqz0c0"))
                .andExpect(jsonPath("$.data.history[0].nodeCount").value(3));
    }

    @Test
    void given_no_plan_when_detail_then_404_ord036() throws Exception {
        when(appService.detailForBackoffice(900L)).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_PLAN_NOT_FOUND));

        mockMvc.perform(signedBuilder(get("/api/backoffice/orders/900/payment-plan"),
                "/api/backoffice/orders/900/payment-plan", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5036));
    }

    @Test
    void given_node_specs_when_enter_then_plan_envelope() throws Exception {
        when(appService.enterNodes(eq(900L), anyList())).thenReturn(planResponse());

        mockMvc.perform(signedBuilder(post("/api/backoffice/orders/900/payment-plan/nodes"),
                "/api/backoffice/orders/900/payment-plan/nodes",
                """
                        {"nodes":[{"name":"首期开工款","amount":1920000,"kickoffGate":true},
                        {"name":"尾款结算","amount":960000,"kickoffGate":false}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value(4800000))
                .andExpect(jsonPath("$.data.nodes[0].kickoffGate").value(true));

        verify(appService).enterNodes(eq(900L), anyList());
    }

    @Test
    void given_locked_nodes_when_enter_then_409_ord044() throws Exception {
        when(appService.enterNodes(eq(900L), anyList())).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_NODES_LOCKED));

        mockMvc.perform(signedBuilder(post("/api/backoffice/orders/900/payment-plan/nodes"),
                "/api/backoffice/orders/900/payment-plan/nodes",
                """
                        {"nodes":[{"name":"尾款","amount":960000,"kickoffGate":false}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5044))
                .andExpect(jsonPath("$.message").value(OrderMessage.PAYMENT_NODES_LOCKED.message()));
    }

    @Test
    void given_verification_entry_when_verify_then_plan_envelope_and_operator_passed()
            throws Exception {
        when(appService.verifyArrival(eq(900L), eq(911L), any(VerifyPaymentCommand.class),
                any(Operator.class))).thenReturn(planResponse());

        mockMvc.perform(signedBuilder(post("/api/backoffice/orders/900/payment-nodes/911/verify"),
                "/api/backoffice/orders/900/payment-nodes/911/verify",
                """
                        {"actualAmount":1920000,"arrivedAt":"2026-10-17T10:00:00",
                        "voucherId":"930","channelRefNo":"BANK-20261017-0001","basis":"银企对账一致"}""")
                .header("X-User-Id", "700100")
                .header("X-User-Name", "运营·小刘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[0].status").value(1));

        verify(appService).verifyArrival(eq(900L), eq(911L),
                any(VerifyPaymentCommand.class), any(Operator.class));
    }

    @Test
    void given_invalid_entry_when_verify_then_400_ord041() throws Exception {
        when(appService.verifyArrival(eq(900L), eq(911L), any(VerifyPaymentCommand.class),
                any(Operator.class))).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_VERIFY_ENTRY_INVALID));

        mockMvc.perform(signedBuilder(post("/api/backoffice/orders/900/payment-nodes/911/verify"),
                "/api/backoffice/orders/900/payment-nodes/911/verify",
                """
                        {"actualAmount":0,"arrivedAt":"2026-10-17T10:00:00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5041));
    }

    @Test
    void given_reject_without_basis_then_400_ord042() throws Exception {
        when(appService.reject(eq(900L), eq(911L), any(), any(Operator.class))).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_REJECT_BASIS_REQUIRED));

        mockMvc.perform(signedBuilder(post("/api/backoffice/orders/900/payment-nodes/911/reject"),
                "/api/backoffice/orders/900/payment-nodes/911/reject",
                """
                        {"basis":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5042));
    }

    @Test
    void given_voucher_when_content_then_binary_attachment() throws Exception {
        when(appService.voucherWithContent(930L)).thenReturn(new PaymentVoucherContent(
                "receipt.pdf", "application/pdf", 1024L, "%PDF-1.4".getBytes()));

        mockMvc.perform(signedBuilder(get("/api/backoffice/payment-vouchers/930/content"),
                "/api/backoffice/payment-vouchers/930/content", null))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"receipt.pdf\""))
                .andExpect(content().bytes("%PDF-1.4".getBytes()));
    }

    @Test
    void given_missing_voucher_when_content_then_404_ord045() throws Exception {
        when(appService.voucherWithContent(930L)).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_VOUCHER_NOT_FOUND));

        mockMvc.perform(signedBuilder(get("/api/backoffice/payment-vouchers/930/content"),
                "/api/backoffice/payment-vouchers/930/content", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5045));
    }

    private static PaymentPlanResponse planResponse() {
        return new PaymentPlanResponse("910", "900", "920", "SG-3ukqz0c0", 4800000L, 0L,
                LocalDateTime.of(2026, 10, 17, 9, 0),
                List.of(new PaymentNodeResponse("911", 1, "首期开工款", 1920000L, 40,
                        1, "待支付", "SG-3ukqz0c0-K1", true, 1, "线下转账", null,
                        0L, 1920000L, null, List.of(new PaymentVoucherResponse(
                        "930", "911", "receipt.pdf", "application/pdf", 1024L,
                        LocalDateTime.of(2026, 10, 17, 10, 0))))));
    }
}
