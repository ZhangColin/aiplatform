package com.aieducenter.aiplatform.business.order.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.config.JacksonConfiguration;

import com.aieducenter.aiplatform.business.order.application.PaymentPlanAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.PaymentNodeResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.PaymentPlanResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.PaymentVoucherResponse;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单付款用户面 REST 契约（#337）：ApiResponse 信封、付款计划取数面形状
 * （节点五态/附言码/开工门/渠道位/还需支付推导/凭证清单）与凭证上传端点
 * （multipart、追加保留语义在应用层集测钉）＋错误码（ORD_001 订单不存在、
 * ORD_036 无计划、ORD_037 节点不存在、ORD_038 状态守卫、ORD_040 凭证无效）。
 * mock 支付端点退役的负向钉（POST /api/orders/{id}/payment 404）一并在此。
 */
@WebMvcTest(OrderPaymentController.class)
@Import({OrderControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class OrderPaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentPlanAppService appService;

    /** 全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "payment-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_plan_when_get_then_envelope_with_nodes_and_derived_fields() throws Exception {
        when(appService.planOfOrder(900L)).thenReturn(new PaymentPlanResponse(
                "910", "900", "920", "SG-3ukqz0c0", 4800000L, 1920000L,
                LocalDateTime.of(2026, 10, 17, 9, 0),
                List.of(new PaymentNodeResponse("911", 1, "首期开工款", 1920000L, 40,
                        1, "待支付", "SG-3ukqz0c0-K1", true, 1, "线下转账", null,
                        1000000L, 920000L, null, List.of(new PaymentVoucherResponse(
                        "930", "911", "receipt.pdf", "application/pdf", 1024L,
                        LocalDateTime.of(2026, 10, 17, 10, 0)))))));

        performAsUser(get("/api/orders/900/payment-plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.contractNo").value("SG-3ukqz0c0"))
                .andExpect(jsonPath("$.data.totalAmount").value(4800000))
                .andExpect(jsonPath("$.data.totalSettled").value(1920000))
                .andExpect(jsonPath("$.data.nodes[0].seq").value(1))
                .andExpect(jsonPath("$.data.nodes[0].status").value(1)) // PENDING → Integer code
                .andExpect(jsonPath("$.data.nodes[0].statusName").value("待支付"))
                .andExpect(jsonPath("$.data.nodes[0].remarkCode").value("SG-3ukqz0c0-K1"))
                .andExpect(jsonPath("$.data.nodes[0].kickoffGate").value(true))
                .andExpect(jsonPath("$.data.nodes[0].channelType").value(1))
                // 还需支付推导面（前台「还需支付金额与对应节点」）
                .andExpect(jsonPath("$.data.nodes[0].settledAmount").value(1000000))
                .andExpect(jsonPath("$.data.nodes[0].remainingAmount").value(920000))
                .andExpect(jsonPath("$.data.nodes[0].vouchers[0].fileName").value("receipt.pdf"));
    }

    @Test
    void given_no_plan_when_get_then_404_ord036() throws Exception {
        when(appService.planOfOrder(900L)).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_PLAN_NOT_FOUND));

        performAsUser(get("/api/orders/900/payment-plan"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5036))
                .andExpect(jsonPath("$.message").value(OrderMessage.PAYMENT_PLAN_NOT_FOUND.message()));
    }

    @Test
    void given_voucher_file_when_upload_then_plan_envelope() throws Exception {
        when(appService.uploadVoucher(eq(900L), eq(911L), any())).thenReturn(
                new PaymentPlanResponse("910", "900", "920", "SG-3ukqz0c0",
                        4800000L, 0L, LocalDateTime.of(2026, 10, 17, 9, 0), List.of()));

        performAsUser(multipart("/api/orders/900/payment-nodes/911/vouchers")
                        .file(new MockMultipartFile("file", "receipt.pdf",
                                "application/pdf", "%PDF-1.4".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractNo").value("SG-3ukqz0c0"));

        verify(appService).uploadVoucher(eq(900L), eq(911L), any());
    }

    @Test
    void given_received_node_when_upload_then_409_ord038() throws Exception {
        when(appService.uploadVoucher(eq(900L), eq(911L), any())).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_ACTION_NOT_ALLOWED));

        performAsUser(multipart("/api/orders/900/payment-nodes/911/vouchers")
                        .file(new MockMultipartFile("file", "again.pdf",
                                "application/pdf", "%PDF-1.4".getBytes())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5038));
    }

    @Test
    void given_invalid_format_when_upload_then_400_ord040() throws Exception {
        when(appService.uploadVoucher(eq(900L), eq(911L), any())).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_VOUCHER_INVALID));

        performAsUser(multipart("/api/orders/900/payment-nodes/911/vouchers")
                        .file(new MockMultipartFile("file", "note.txt",
                                "text/plain", "x".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5040));
    }

    @Test
    void given_stale_node_when_upload_then_404_ord037() throws Exception {
        when(appService.uploadVoucher(eq(900L), eq(911L), any())).thenThrow(
                new ApplicationException(OrderMessage.PAYMENT_NODE_NOT_FOUND));

        performAsUser(multipart("/api/orders/900/payment-nodes/911/vouchers")
                        .file(new MockMultipartFile("file", "stale.pdf",
                                "application/pdf", "%PDF-1.4".getBytes())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5037));
    }

    @Test
    void given_retired_mock_payment_endpoint_when_post_then_404_no_route() throws Exception {
        // mock 支付闭环退役（#337）：同步支付端点不复存在——404（无路由，非业务码）
        performAsUser(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/orders/900/payment"))
                .andExpect(status().isNotFound());
    }
}
