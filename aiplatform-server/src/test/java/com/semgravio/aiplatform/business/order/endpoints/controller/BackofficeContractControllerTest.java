package com.semgravio.aiplatform.business.order.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import com.cartisan.openapi.config.CartisanOpenapiAutoConfiguration;
import com.cartisan.web.config.JacksonConfiguration;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;
import com.cartisan.web.exception.GlobalExceptionHandler;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.backoffice.BackofficeSignatureTestConfig;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.business.order.application.ContractAppService;
import com.semgravio.aiplatform.business.order.application.dto.response.BackofficeContractDetailResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.BackofficeContractSummaryResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.ContractResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.ContractTransitionResponse;
import com.semgravio.aiplatform.business.order.domain.enums.ContractParty;
import com.semgravio.aiplatform.business.order.domain.enums.ContractStatus;
import com.semgravio.aiplatform.business.order.domain.enums.ContractTransitionAction;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台合同 REST 契约（#336 六态承接）：ApiResponse 信封、BaseEnum → Integer
 * code、动作端点形状（advance/fail/withdraw/void/sign/record/createForOrder）
 * 与错误码（ORD_020 不存在、ORD_021 状态守卫、ORD_022 依据必填、ORD_024 活跃
 * 唯一、ORD_028 重复签署、ORD_031 重录窗口）。全链真库签名面归 seam 测试
 * （BackofficeContractSeamTest），本类钉控制器契约与异常映射。
 */
@WebMvcTest(BackofficeContractController.class)
@ImportAutoConfiguration(CartisanOpenapiAutoConfiguration.class)
@Import({JacksonConfiguration.class,
        BackofficeOrderControllerTest.ExceptionAdviceConfig.class,
        BackofficeOrderControllerTest.EnumParamBindingConfig.class,
        BackofficeSignatureTestConfig.class})
class BackofficeContractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContractAppService appService;

    /** 签名请求（五头 HMAC 测试件，路径归一不含 query——签名计算不含 query 参数先例）。 */
    private ResultActions performSigned(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String canonicalPath, String body) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                builder.contentType(MediaType.APPLICATION_JSON).content(body), canonicalPath, body));
    }

    @Test
    void given_contracts_when_list_then_page_envelope() throws Exception {
        PageResponse<BackofficeContractSummaryResponse> page = PageResponse.of(new PageImpl<>(
                List.of(new BackofficeContractSummaryResponse("910", "SG-3ukqz0c0", "900",
                        ContractStatus.SIGNING, "签署进行中", 1, 128000L, "CNY",
                        LocalDateTime.of(2026, 10, 17, 9, 0))),
                PageRequest.of(0, 20), 1));
        when(appService.contracts(any(), any(), any(), any(), any(), any(Pagination.class)))
                .thenReturn(page);

        mockMvc.perform(BackofficeSignatures.signed(
                get("/api/backoffice/contracts").queryParam("status", "3").queryParam("orderId", "900"),
                "/api/backoffice/contracts?status=3&orderId=900", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value("1")) // Long 全局序列化为字符串
                .andExpect(jsonPath("$.data.items[0].contractNo").value("SG-3ukqz0c0"))
                .andExpect(jsonPath("$.data.items[0].status").value(3))
                .andExpect(jsonPath("$.data.items[0].version").value(1));
    }

    @Test
    void given_contract_when_detail_then_full_face_with_trail() throws Exception {
        when(appService.detailForBackoffice(910L)).thenReturn(new BackofficeContractDetailResponse(
                contract(), "# PRD v3\n\n已确认版本全文", List.of(
                new ContractTransitionResponse(ContractTransitionAction.ADVANCE, "推进",
                        ContractStatus.PREPARING, "准备中", ContractStatus.AWAITING_SIGNATURE,
                        "待核对与签署", "内容核对完毕，送用户签署", "700100", "运营·小刘",
                        LocalDateTime.of(2026, 10, 17, 10, 0)))));

        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/contracts/910"),
                "/api/backoffice/contracts/910", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contract.contractNo").value("SG-3ukqz0c0"))
                .andExpect(jsonPath("$.data.prdSnapshot").value("# PRD v3\n\n已确认版本全文"))
                .andExpect(jsonPath("$.data.transitions[0].action").value(1))
                .andExpect(jsonPath("$.data.transitions[0].fromStatus").value(1))
                .andExpect(jsonPath("$.data.transitions[0].toStatus").value(2))
                .andExpect(jsonPath("$.data.transitions[0].basis").value("内容核对完毕，送用户签署"))
                .andExpect(jsonPath("$.data.transitions[0].operatorName").value("运营·小刘"));
    }

    @Test
    void given_not_found_when_detail_then_404_ord020() throws Exception {
        when(appService.detailForBackoffice(910L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_CONTRACT_NOT_FOUND));

        mockMvc.perform(BackofficeSignatures.signed(get("/api/backoffice/contracts/910"),
                "/api/backoffice/contracts/910", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5020))
                .andExpect(jsonPath("$.message").value(OrderMessage.ORDER_CONTRACT_NOT_FOUND.message()));
    }

    @Test
    void given_advance_command_when_post_advance_then_contract_envelope() throws Exception {
        when(appService.advance(eq(910L), eq("内容核对完毕"), any())).thenReturn(contract());

        performSigned(post("/api/backoffice/contracts/910/advance"),
                "/api/backoffice/contracts/910/advance", "{\"basis\":\"内容核对完毕\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.statusName").value("待核对与签署"));
    }

    @Test
    void given_signing_contract_when_post_sign_then_slot_marked() throws Exception {
        when(appService.sign(eq(910L), eq(ContractParty.PARTY_A), eq("甲方盖章"), any()))
                .thenReturn(contract());

        performSigned(post("/api/backoffice/contracts/910/sign"),
                "/api/backoffice/contracts/910/sign", "{\"party\":1,\"basis\":\"甲方盖章\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partyASigned").value(false)); // 夹具定格；动作语义归服务缝
    }

    @Test
    void given_terminal_contract_when_post_advance_then_409_ord021() throws Exception {
        when(appService.advance(eq(910L), any(), any())).thenThrow(
                new DomainException(OrderMessage.CONTRACT_ACTION_NOT_ALLOWED));

        performSigned(post("/api/backoffice/contracts/910/advance"),
                "/api/backoffice/contracts/910/advance", "{\"basis\":\"迟到\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5021));
    }

    @Test
    void given_blank_basis_when_post_advance_then_400_ord022() throws Exception {
        when(appService.advance(eq(910L), any(), any())).thenThrow(
                new DomainException(OrderMessage.CONTRACT_BASIS_REQUIRED));

        performSigned(post("/api/backoffice/contracts/910/advance"),
                "/api/backoffice/contracts/910/advance", "{\"basis\":\" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(5022));
    }

    @Test
    void given_active_contract_when_post_create_for_order_then_409_ord024() throws Exception {
        when(appService.confirmScope(900L)).thenThrow(
                new ApplicationException(OrderMessage.CONTRACT_ALREADY_ACTIVE));

        performSigned(post("/api/backoffice/contracts/orders/900"),
                "/api/backoffice/contracts/orders/900", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5024));
    }

    @Test
    void given_signing_contract_when_post_record_then_409_ord031() throws Exception {
        when(appService.record(eq(910L), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DomainException(OrderMessage.CONTRACT_RE_RECORD_NOT_ALLOWED));

        performSigned(post("/api/backoffice/contracts/910/record"),
                "/api/backoffice/contracts/910/record", "{\"basis\":\"越窗重录\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5031));
    }

    @Test
    void given_signed_slot_when_post_sign_again_then_409_ord028() throws Exception {
        when(appService.sign(eq(910L), eq(ContractParty.PARTY_A), any(), any()))
                .thenThrow(new DomainException(OrderMessage.CONTRACT_PARTY_ALREADY_SIGNED));

        performSigned(post("/api/backoffice/contracts/910/sign"),
                "/api/backoffice/contracts/910/sign", "{\"party\":1,\"basis\":\"重复\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5028));
    }

    @Test
    void given_withdraw_command_when_post_withdraw_then_terminal_envelope() throws Exception {
        when(appService.withdraw(eq(910L), eq("范围录错"), any())).thenReturn(contract());

        performSigned(post("/api/backoffice/contracts/910/withdraw"),
                "/api/backoffice/contracts/910/withdraw", "{\"basis\":\"范围录错\"}")
                .andExpect(status().isOk());

        verify(appService).withdraw(eq(910L), eq("范围录错"), any());
    }

    // ---------- 夹具 ----------

    private static ContractResponse contract() {
        return new ContractResponse("910", "SG-3ukqz0c0", "900",
                ContractStatus.AWAITING_SIGNATURE, "待核对与签署",
                1, false, "张三", "语义引力（北京）科技有限公司",
                "管理后台＋小程序全量交付", "开工款 50%，验收 50%",
                "交付清单：管理后台", "验收方式：按 PRD v3 验收", "工期口径：4 周",
                128000L, "CNY", 3, 2,
                false, null, false, null,
                LocalDateTime.of(2026, 10, 17, 9, 0));
    }
}
