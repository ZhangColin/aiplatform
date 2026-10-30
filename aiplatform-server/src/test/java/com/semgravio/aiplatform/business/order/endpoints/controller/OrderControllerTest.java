package com.semgravio.aiplatform.business.order.endpoints.controller;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.semgravio.aiplatform.business.order.application.ContractAppService;
import com.semgravio.aiplatform.business.order.application.OrderAppService;
import com.semgravio.aiplatform.business.order.application.dto.response.ContractResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.OrderResponse;
import com.semgravio.aiplatform.business.order.domain.enums.ContractStatus;
import com.semgravio.aiplatform.business.order.domain.enums.OrderDeliverableType;
import com.semgravio.aiplatform.business.order.domain.enums.OrderStatus;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单用户面 REST 契约（#28 交易环①；#337 mock 支付端点已退役——支付语义并入
 * 付款节点凭证核验路径，付款面契约归 OrderPaymentControllerTest）：ApiResponse
 * 信封、BaseEnum → Integer code 双向、下单/详情/取消/合同确认与预览端点形状与
 * 错误码（PRJ_001 透传、ORD_003 唯一未终结单、ORD_001 不存在、ORD_005 已支付
 * 不可取消）。
 */
@WebMvcTest(OrderController.class)
@Import({OrderControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderAppService appService;

    @MockitoBean
    private ContractAppService contractAppService;

    @MockitoBean
    private com.semgravio.aiplatform.business.order.application.DeliveryStatusAppService deliveryStatusAppService;

    /** 全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "order-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_first_place_when_post_project_orders_then_pending_quote_envelope()
            throws Exception {
        when(appService.place(100L)).thenReturn(order("900", OrderStatus.PENDING_QUOTE, null));

        performAsUser(post("/api/projects/100/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("900"))
                .andExpect(jsonPath("$.data.projectId").value("100"))
                .andExpect(jsonPath("$.data.status").value(1)) // PENDING_QUOTE → Integer code
                .andExpect(jsonPath("$.data.statusName").value("待报价"))
                // 双版本引用随单冻结（#335）＋待报价缺省无金额（「待确认报价」占位
                // 态的数据面——不以 0 元或示例价代替）
                .andExpect(jsonPath("$.data.prdVersionNo").value(3))
                .andExpect(jsonPath("$.data.demoVersionNo").value(2))
                .andExpect(jsonPath("$.data.amount").doesNotExist());

        verify(appService).place(100L);
    }

    @Test
    void given_active_order_when_post_project_orders_then_409_ord003() throws Exception {
        when(appService.place(100L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_ALREADY_ACTIVE));

        performAsUser(post("/api/projects/100/orders"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5003))
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_ALREADY_ACTIVE.message()));
    }

    @Test
    void given_missing_project_when_post_project_orders_then_prj001_passthrough()
            throws Exception {
        // 项目上下文错误原样透传（跨 BC 软引用口径）：项目不存在 404 PRJ_001
        // （#335 起下单不再读工作区 PRD——PRJ_015 不再可能是下单障碍，快照源＝
        // 已确认版本全文）
        when(appService.place(100L)).thenThrow(
                new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(post("/api/projects/100/orders"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_no_confirmed_prd_version_when_post_project_orders_then_409_ord018() throws Exception {
        // 下单前置门·PRD 腿（#335 拦截与提示）：信封数字码 5018，前端 toast message
        when(appService.place(100L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_PRD_VERSION_REQUIRED));

        performAsUser(post("/api/projects/100/orders"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5018))
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_PRD_VERSION_REQUIRED.message()));
    }

    @Test
    void given_demo_unconfirmed_when_post_project_orders_then_409_ord019() throws Exception {
        // 下单前置门·Demo 腿（#335 拦截与提示）：信封数字码 5019
        when(appService.place(100L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_DEMO_VERSION_REQUIRED));

        performAsUser(post("/api/projects/100/orders"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5019))
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_DEMO_VERSION_REQUIRED.message()));
    }

    @Test
    void given_malformed_project_id_when_post_project_orders_then_404_prj001() throws Exception {
        performAsUser(post("/api/projects/abc/orders"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_order_when_get_detail_then_wrapped() throws Exception {
        when(appService.detail(900L)).thenReturn(order("900", OrderStatus.PENDING_QUOTE, null));

        performAsUser(get("/api/orders/900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("900"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.statusName").value("待报价"));
    }

    @Test
    void given_missing_order_when_get_or_cancel_then_404_ord001() throws Exception {
        when(appService.detail(900L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_NOT_FOUND));
        when(appService.cancel(900L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_NOT_FOUND));

        performAsUser(get("/api/orders/900"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("订单不存在"));
        performAsUser(post("/api/orders/900/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    void given_unpaid_order_when_cancel_then_cancelled_response() throws Exception {
        when(appService.cancel(900L)).thenReturn(
                order("900", OrderStatus.CANCELLED, LocalDateTime.of(2026, 9, 1, 10, 0)));

        performAsUser(post("/api/orders/900/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(5))
                .andExpect(jsonPath("$.data.statusName").value("已取消"))
                .andExpect(jsonPath("$.data.cancelledAt").value("2026-09-01T10:00:00"));

        verify(appService).cancel(900L);
    }

    @Test
    void given_paid_order_when_cancel_then_409_ord005() throws Exception {
        // 聚合守卫抛 DomainException（应用层不换装）——按真实异常类型验全局处理器映射
        when(appService.cancel(900L)).thenThrow(
                new DomainException(OrderMessage.ORDER_CANCEL_NOT_ALLOWED));

        performAsUser(post("/api/orders/900/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5005))
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_CANCEL_NOT_ALLOWED.message()));
    }

    @Test
    void given_malformed_order_id_when_get_then_404_ord001() throws Exception {
        performAsUser(get("/api/orders/abc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    // ---------- #336：确认范围并准备合同（合同创建口）＋合同预览 ----------

    @Test
    void given_quoted_scoped_order_when_confirm_scope_then_contract_envelope() throws Exception {
        when(contractAppService.confirmScope(900L)).thenReturn(contract(ContractStatus.PREPARING, false));

        performAsUser(post("/api/orders/900/confirm-scope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.contractNo").value("SG-3ukqz0c0"))
                .andExpect(jsonPath("$.data.status").value(1)) // PREPARING → Integer code
                .andExpect(jsonPath("$.data.statusName").value("准备中"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.needsUpdate").value(false))
                // 冻结面：范围快照三段＋冻结金额（Long → string 信封同款）
                .andExpect(jsonPath("$.data.scopeDeliverables").value("交付清单：管理后台"))
                .andExpect(jsonPath("$.data.scopeAcceptance").value("验收方式：按 PRD v3 验收"))
                .andExpect(jsonPath("$.data.scopeTimeline").value("工期口径：4 周"))
                .andExpect(jsonPath("$.data.amount").value(128000))
                .andExpect(jsonPath("$.data.prdVersionNo").value(3))
                .andExpect(jsonPath("$.data.demoVersionNo").value(2))
                .andExpect(jsonPath("$.data.partyASigned").value(false))
                .andExpect(jsonPath("$.data.partyBSigned").value(false));

        verify(contractAppService).confirmScope(900L);
    }

    @Test
    void given_unquoted_order_when_confirm_scope_then_409_ord025() throws Exception {
        // 前置门·报价腿：toast 提示 message（拦截与提示）
        when(contractAppService.confirmScope(900L)).thenThrow(
                new ApplicationException(OrderMessage.CONTRACT_QUOTE_REQUIRED));

        performAsUser(post("/api/orders/900/confirm-scope"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5025))
                .andExpect(jsonPath("$.message").value(OrderMessage.CONTRACT_QUOTE_REQUIRED.message()));
    }

    @Test
    void given_scope_missing_when_confirm_scope_then_409_ord026() throws Exception {
        when(contractAppService.confirmScope(900L)).thenThrow(
                new ApplicationException(OrderMessage.CONTRACT_SCOPE_REQUIRED));

        performAsUser(post("/api/orders/900/confirm-scope"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5026));
    }

    @Test
    void given_active_contract_when_confirm_scope_again_then_409_ord024() throws Exception {
        when(contractAppService.confirmScope(900L)).thenThrow(
                new ApplicationException(OrderMessage.CONTRACT_ALREADY_ACTIVE));

        performAsUser(post("/api/orders/900/confirm-scope"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5024));
    }

    @Test
    void given_latest_contract_when_get_order_contract_then_preview_envelope() throws Exception {
        when(contractAppService.latestByOrder(900L)).thenReturn(contract(ContractStatus.SIGNING, false));

        performAsUser(get("/api/orders/900/contract"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(3)) // SIGNING
                .andExpect(jsonPath("$.data.statusName").value("签署进行中"))
                .andExpect(jsonPath("$.data.partyA").value("张三"))
                .andExpect(jsonPath("$.data.paymentPlan").value("开工款 50%，验收 50%"));

        verify(contractAppService).latestByOrder(900L);
    }

    @Test
    void given_no_contract_when_get_order_contract_then_404_ord020() throws Exception {
        // 前端认 5020/404 判「无合同」缺席态——不渲染卡片
        when(contractAppService.latestByOrder(900L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_CONTRACT_NOT_FOUND));

        performAsUser(get("/api/orders/900/contract"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5020))
                .andExpect(jsonPath("$.message").value(OrderMessage.ORDER_CONTRACT_NOT_FOUND.message()));
    }

    // ---------- #338：交付状态读口（前台六态）＋取消收窄透传 ----------

    @Test
    void given_delivery_status_when_get_then_six_state_envelope() throws Exception {
        when(deliveryStatusAppService.statusOf(100L)).thenReturn(
                new com.semgravio.aiplatform.business.order.application.dto.response.DeliveryStatusResponse(
                        "900", 3, "已支付",
                        com.semgravio.aiplatform.business.order.domain.enums.DeliveryState.AWAITING_PRODUCTION,
                        "待安排生产", false,
                        new com.semgravio.aiplatform.business.order.application.dto.response.DeliveryStatusResponse.HandoffView(
                                1, "待安排生产", "李经理", "准备服务器环境", "已完成方案拆解",
                                LocalDateTime.of(2026, 10, 20, 9, 0))));

        performAsUser(get("/api/projects/100/delivery-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value(4))
                .andExpect(jsonPath("$.data.stateName").value("待安排生产"))
                .andExpect(jsonPath("$.data.orderStatus").value(3))
                .andExpect(jsonPath("$.data.needsUserSupplement").value(false))
                .andExpect(jsonPath("$.data.handoff.status").value(1))
                .andExpect(jsonPath("$.data.handoff.contact").value("李经理"))
                .andExpect(jsonPath("$.data.handoff.estimatedStartAt").value("2026-10-20T09:00:00"));
    }

    @Test
    void given_no_active_order_when_get_delivery_status_then_404_ord001() throws Exception {
        // 前端认 5001/404 判「无交付生命周期」缺席态——不渲染生产状态卡
        when(deliveryStatusAppService.statusOf(100L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_NOT_FOUND));

        performAsUser(get("/api/projects/100/delivery-status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(5001));
    }

    @Test
    void given_signed_contract_when_cancel_then_409_ord047() throws Exception {
        when(appService.cancel(900L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_CANCEL_SIGNED));

        performAsUser(post("/api/orders/900/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(5047))
                .andExpect(jsonPath("$.message").value(OrderMessage.ORDER_CANCEL_SIGNED.message()));
    }

    // ---------- 夹具 ----------

    /** 合同回执夹具（status/needsUpdate 按用例变参，其余定格）。 */
    private static ContractResponse contract(ContractStatus status, boolean needsUpdate) {
        return new ContractResponse("910", "SG-3ukqz0c0", "900",
                status, status.getName(),
                1, needsUpdate, "张三", "语义引力（北京）科技有限公司",
                "管理后台＋小程序全量交付", "开工款 50%，验收 50%",
                "交付清单：管理后台", "验收方式：按 PRD v3 验收", "工期口径：4 周",
                128000L, "CNY", 3, 2,
                false, null, false, null,
                LocalDateTime.of(2026, 10, 17, 9, 0));
    }

    private static OrderResponse order(String id, OrderStatus status, LocalDateTime cancelledAt) {
        return new OrderResponse(id, "100", status, status.getName(),
                OrderDeliverableType.SYSTEM, "系统", 3, 2, null, null, null,
                null, null, null, null, java.util.List.of(),
                LocalDateTime.of(2026, 9, 1, 9, 0), cancelledAt, null, null);
    }

    /** MVC 切片不含 cartisan-web autoconfig，手动注册其全局异常处理器。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
