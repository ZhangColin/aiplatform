package com.aieducenter.aiplatform.business.identity.endpoints.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.business.identity.application.CodeLoginAppService;
import com.aieducenter.aiplatform.business.identity.application.CodeLoginAppService.CodeLoginCompletion;
import com.aieducenter.aiplatform.business.identity.application.dto.response.CodeSentResponse;
import com.aieducenter.aiplatform.business.identity.application.dto.response.MeResponse;
import com.aieducenter.aiplatform.business.identity.domain.error.IdentityMessage;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证码登录两端点契约（#327，M01 后端面 /auth/**——登录自身不落在 /api/** 拦截面，
 * 未登录可调）：统一信封、错误信封数字业务码（IDN_005–008 → 6005–6008）、
 * 验码成功种 aiplatform_session cookie（契约与 OIDC 流同源）。
 */
@WebMvcTest(CodeLoginController.class)
@Import({CodeLoginControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class CodeLoginControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CodeLoginAppService appService;

    @Test
    void given_valid_target_when_post_code_then_envelope_with_ttl() throws Exception {
        when(appService.sendCode("user@example.com")).thenReturn(new CodeSentResponse(300));

        performCode("user@example.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));
    }

    @Test
    void given_garbage_target_when_post_code_then_400_with_numeric_code_6005() throws Exception {
        when(appService.sendCode("not-an-email"))
                .thenThrow(new ApplicationException(IdentityMessage.VERIFICATION_TARGET_INVALID));

        performCode("not-an-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(6005))
                .andExpect(jsonPath("$.message").value("请输入有效的邮箱或手机号"));
    }

    @Test
    void given_disabled_channel_when_post_code_then_403_with_numeric_code_6006() throws Exception {
        when(appService.sendCode("13800138000"))
                .thenThrow(new ApplicationException(IdentityMessage.VERIFICATION_CHANNEL_DISABLED));

        performCode("13800138000")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(6006));
    }

    @Test
    void given_rate_limited_when_post_code_then_429_with_numeric_code_6007() throws Exception {
        when(appService.sendCode("user@example.com"))
                .thenThrow(new ApplicationException(IdentityMessage.VERIFICATION_SEND_TOO_FREQUENT));

        performCode("user@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(6007));
    }

    @Test
    void given_correct_code_when_post_verify_then_session_cookie_planted_and_me_envelope()
            throws Exception {
        when(appService.verifyAndLogin("user@example.com", "123456", null)).thenReturn(
                new CodeLoginCompletion(
                        new MeResponse("3897654321098765432", "user@example.com"),
                        org.springframework.http.ResponseCookie.from(
                                AuthCookies.SESSION_COOKIE_NAME, "sid-9")
                                .httpOnly(true).sameSite("Lax").path("/").build(),
                        null));

        mockMvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"user@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accountId").value("3897654321098765432"))
                .andExpect(jsonPath("$.data.displayName").value("user@example.com"))
                .andExpect(result -> assertThat(
                        result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                        .singleElement()
                        .satisfies(cookie -> assertThat(cookie)
                                .startsWith("aiplatform_session=sid-9;")
                                .contains("HttpOnly")
                                .contains("SameSite=Lax")
                                .doesNotContain("Max-Age="))); // 会话级 cookie（同 OIDC 流）
    }

    @Test
    void given_guest_credential_carried_when_post_verify_then_guest_clear_cookie_set()
            throws Exception {
        // #328 登录即自动平移：请求携带访客凭证时验证码登录响应清访客凭证 cookie
        // （账户会话接管；平移副作用归应用层——此处钉端点层 Set-Cookie 契约）
        when(appService.verifyAndLogin("user@example.com", "123456", "guest-cred-1")).thenReturn(
                new CodeLoginCompletion(
                        new MeResponse("3897654321098765432", "user@example.com"),
                        org.springframework.http.ResponseCookie.from(
                                AuthCookies.SESSION_COOKIE_NAME, "sid-9")
                                .httpOnly(true).path("/").build(),
                        org.springframework.http.ResponseCookie.from(
                                AuthCookies.GUEST_COOKIE_NAME, "")
                                .httpOnly(true).sameSite("Lax").path("/").maxAge(0).build()));

        mockMvc.perform(post("/auth/verify")
                        .cookie(new jakarta.servlet.http.Cookie(
                                AuthCookies.GUEST_COOKIE_NAME, "guest-cred-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"user@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                        .anySatisfy(cookie -> assertThat(cookie)
                                .startsWith(AuthCookies.GUEST_COOKIE_NAME + "=;")
                                .contains("Max-Age=0")));
    }

    @Test
    void given_wrong_code_when_post_verify_then_400_with_numeric_code_6008() throws Exception {
        when(appService.verifyAndLogin("user@example.com", "000000", null))
                .thenThrow(new ApplicationException(IdentityMessage.VERIFICATION_CODE_MISMATCH));

        mockMvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"user@example.com\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(6008))
                .andExpect(jsonPath("$.message").value("验证码错误或已过期"));
    }

    private ResultActions performCode(String target) throws Exception {
        return mockMvc.perform(post("/auth/code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":\"" + target + "\"}"));
    }

    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
