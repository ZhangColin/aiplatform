package com.aieducenter.aiplatform.business.order.endpoints.controller;

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

import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单用户面 REST 契约（#28 交易环①）：ApiResponse 信封、BaseEnum → Integer code
 * 双向、下单/详情/取消三端点形状与错误码（PRJ_001/PRJ_015 透传、ORD_003 唯一
 * 未终结单、ORD_001 不存在、ORD_005 已支付不可取消）。
 */
@WebMvcTest(OrderController.class)
@Import({OrderControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderAppService appService;

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
                .andExpect(jsonPath("$.data.statusName").value("待报价"));

        verify(appService).place(100L);
    }

    @Test
    void given_active_order_when_post_project_orders_then_409_ord003() throws Exception {
        when(appService.place(100L)).thenThrow(
                new ApplicationException(OrderMessage.ORDER_ALREADY_ACTIVE));

        performAsUser(post("/api/projects/100/orders"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_ALREADY_ACTIVE.message()));
    }

    @Test
    void given_prd_never_produced_when_post_project_orders_then_prj015_passthrough()
            throws Exception {
        // 项目上下文错误原样透传（跨 BC 软引用口径）：PRD 未产出 404 PRJ_015
        when(appService.place(100L)).thenThrow(
                new ApplicationException(ProjectMessage.PRD_NOT_PRODUCED));

        performAsUser(post("/api/projects/100/orders"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("PRD 尚未产出"));
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
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message")
                        .value(OrderMessage.ORDER_CANCEL_NOT_ALLOWED.message()));
    }

    @Test
    void given_malformed_order_id_when_get_then_404_ord001() throws Exception {
        performAsUser(get("/api/orders/abc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    // ---------- 夹具 ----------

    private static OrderResponse order(String id, OrderStatus status, LocalDateTime cancelledAt) {
        return new OrderResponse(id, "100", status, status.getName(),
                LocalDateTime.of(2026, 9, 1, 9, 0), cancelledAt);
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
