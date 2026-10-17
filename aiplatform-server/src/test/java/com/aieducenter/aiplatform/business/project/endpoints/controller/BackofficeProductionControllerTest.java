package com.aieducenter.aiplatform.business.project.endpoints.controller;

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
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;
import com.cartisan.openapi.config.CartisanOpenapiAutoConfiguration;
import com.cartisan.web.config.BaseEnumConverter;
import com.cartisan.web.config.JacksonConfiguration;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.backoffice.BackofficeSignatureTestConfig;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.business.project.application.ProductionHandoffAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeProductionHandoffResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProductionHandoffAction;
import com.aieducenter.aiplatform.business.project.domain.enums.ProductionHandoffStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台生产承接 REST 契约（#338 交付承接）：ApiResponse 信封、按项目寻址的
 * 详情/信息更新/推进端点形状、「需用户补充」置位与错误信封数字码（PRJ_055
 * 承接缺档 4055、PRJ_056 状态守卫 4056、PRJ_057 依据必填 4057、PRJ_001 项目
 * 缺档 4001）。全链真库签名面归 seam 测试（BackofficeProductionSeamTest），
 * 本类钉控制器契约与异常映射。
 */
@WebMvcTest(BackofficeProductionController.class)
@ImportAutoConfiguration(CartisanOpenapiAutoConfiguration.class)
@Import({JacksonConfiguration.class,
        BackofficeProductionControllerTest.ExceptionAdviceConfig.class,
        BackofficeProductionControllerTest.EnumParamBindingConfig.class,
        BackofficeSignatureTestConfig.class})
class BackofficeProductionControllerTest {

    private static final String BASE = "/api/backoffice/projects/900800/production-handoff";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductionHandoffAppService appService;

    /** 签名请求（五头 HMAC 测试件，路径归一不含 query——签名计算不含 query 参数先例）。 */
    private ResultActions performSigned(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String canonicalPath, String body) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                builder.contentType(MediaType.APPLICATION_JSON).content(body), canonicalPath, body));
    }

    @Test
    void given_handoff_when_detail_then_full_face_with_trail() throws Exception {
        when(appService.detailForBackoffice(900800L)).thenReturn(new BackofficeProductionHandoffResponse(
                "980", "900800", "900", ProductionHandoffStatus.SCHEDULED, "待安排生产",
                "李经理（微信 ligoods）", "准备服务器环境与域名备案", "已完成技术方案拆解",
                LocalDateTime.of(2026, 10, 20, 9, 0), LocalDateTime.of(2026, 10, 17, 10, 0),
                List.of(new BackofficeProductionHandoffResponse.TransitionView(
                        ProductionHandoffAction.CREATE, "创建", ProductionHandoffStatus.SCHEDULED,
                        "待安排生产", ProductionHandoffStatus.SCHEDULED, "待安排生产",
                        "开工条件满足（合同签署完成且开工门节点全部到账），创建生产承接记录",
                        "system", "开工判定", LocalDateTime.of(2026, 10, 17, 10, 0)))));

        mockMvc.perform(BackofficeSignatures.signed(get(BASE), BASE, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.statusName").value("待安排生产"))
                .andExpect(jsonPath("$.data.contact").value("李经理（微信 ligoods）"))
                .andExpect(jsonPath("$.data.estimatedStartAt").value("2026-10-20T09:00:00"))
                .andExpect(jsonPath("$.data.transitions[0].action").value(1))
                .andExpect(jsonPath("$.data.transitions[0].operatorId").value("system"));
    }

    @Test
    void given_no_handoff_when_detail_then_404_prj_055() throws Exception {
        when(appService.detailForBackoffice(900800L)).thenThrow(
                new ApplicationException(ProjectMessage.PRODUCTION_HANDOFF_NOT_FOUND));

        mockMvc.perform(BackofficeSignatures.signed(get(BASE), BASE, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4055))
                .andExpect(jsonPath("$.message").value(ProjectMessage.PRODUCTION_HANDOFF_NOT_FOUND.message()));
    }

    @Test
    void given_update_command_when_post_update_then_fields_passed_and_envelope() throws Exception {
        when(appService.update(eq(900800L), eq("李经理"), eq("准备环境"), eq("方案拆解完成"),
                eq(LocalDateTime.of(2026, 10, 20, 9, 0)), any(), eq("首次排期录入")))
                .thenReturn(new BackofficeProductionHandoffResponse("980", "900800", "900",
                        ProductionHandoffStatus.SCHEDULED, "待安排生产", "李经理", "准备环境",
                        "方案拆解完成", LocalDateTime.of(2026, 10, 20, 9, 0), null, List.of()));

        performSigned(post(BASE + "/update"), BASE + "/update",
                        "{\"contact\":\"李经理\",\"nextStep\":\"准备环境\",\"progressNote\":\"方案拆解完成\","
                                + "\"estimatedStartAt\":\"2026-10-20T09:00:00\",\"basis\":\"首次排期录入\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contact").value("李经理"));
    }

    @Test
    void given_advance_command_when_post_advance_then_in_production() throws Exception {
        when(appService.advance(eq(900800L), eq("排期落定，今日开工"), any()))
                .thenReturn(new BackofficeProductionHandoffResponse("980", "900800", "900",
                        ProductionHandoffStatus.IN_PRODUCTION, "生产中", null, null, null, null,
                        null, List.of()));

        performSigned(post(BASE + "/advance"), BASE + "/advance", "{\"basis\":\"排期落定，今日开工\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.statusName").value("生产中"));
    }

    @Test
    void given_in_production_when_post_advance_then_409_prj_056() throws Exception {
        when(appService.advance(eq(900800L), any(), any())).thenThrow(
                new DomainException(ProjectMessage.PRODUCTION_ACTION_NOT_ALLOWED));

        performSigned(post(BASE + "/advance"), BASE + "/advance", "{\"basis\":\"再推\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4056));
    }

    @Test
    void given_blank_basis_when_post_advance_then_400_prj_057() throws Exception {
        when(appService.advance(eq(900800L), any(), any())).thenThrow(
                new DomainException(ProjectMessage.PRODUCTION_BASIS_REQUIRED));

        performSigned(post(BASE + "/advance"), BASE + "/advance", "{\"basis\":\" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(4057));
    }

    @Test
    void given_flag_command_when_post_needs_user_supplement_then_passed_through() throws Exception {
        performSigned(post(BASE + "/needs-user-supplement"),
                        BASE + "/needs-user-supplement", "{\"required\":true}")
                .andExpect(status().isOk());

        verify(appService).setNeedsUserSupplement(900800L, true);
    }

    @Test
    void given_missing_project_when_set_flag_then_404_prj_001() throws Exception {
        org.mockito.Mockito.doThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND))
                .when(appService).setNeedsUserSupplement(900800L, true);

        performSigned(post(BASE + "/needs-user-supplement"),
                        BASE + "/needs-user-supplement", "{\"required\":true}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001));
    }

    /** MVC 切片不含 cartisan-web autoconfig，手动注册其全局异常处理器（同 BackofficeOrderControllerTest 形制）。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ExceptionAdviceConfig {

        @org.springframework.context.annotation.Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    /** Integer code ↔ BaseEnum 的参数绑定（MVC 切片手动对齐，同上形制）。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class EnumParamBindingConfig implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {

        @Override
        public void addFormatters(org.springframework.format.FormatterRegistry registry) {
            registry.addConverterFactory(new BaseEnumConverter());
        }
    }
}
