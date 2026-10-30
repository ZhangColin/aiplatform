package com.semgravio.aiplatform.business.identity.endpoints.interceptor;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.semgravio.aiplatform.business.identity.application.GuestAppService;
import com.semgravio.aiplatform.business.identity.domain.model.AuthCookies;
import com.semgravio.aiplatform.business.identity.infrastructure.session.BffSession;
import com.semgravio.aiplatform.business.identity.infrastructure.session.BffSessionStore;
import com.semgravio.aiplatform.config.WebMvcConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/**} 鉴权拦截面（A2 §6 + #326 访客维度）：无会话且无有效访客凭证 401
 * 统一信封（经 AuthExceptionHandler）、有用户上下文<b>或有效访客凭证</b>放行、
 * 匿名首建引导（POST /api/projects 集合端点）放行——惰性建访客的入口；非 /api
 * 路径不拦、ASYNC 再入放行（SSE 语义，见拦截器 javadoc）。走 WebMvcConfig 真实
 * 注册路径（含 /api/** 模式）。
 */
@WebMvcTest(controllers = ApiAuthInterceptorMvcTest.ProbeController.class)
@Import({ApiAuthInterceptorMvcTest.ProbeController.class, WebMvcConfig.class})
class ApiAuthInterceptorMvcTest {

    @Autowired
    private MockMvc mockMvc;

    /** 切片会收集 Filter bean（BffSessionContextFilter）但不带其依赖——mock 掉 */
    @MockitoBean
    private BffSessionStore sessionStore;

    @MockitoBean
    private GuestAppService guestAppService;

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe")
        public String apiProbe() {
            return "ok";
        }

        /** 建项目集合端点同形（匿名首建引导的拦截面判定只看方法+路径）。 */
        @PostMapping("/api/projects")
        public String createProject() {
            return "ok";
        }

        /** 列表端点同形（匿名仍 401 的对照面——方法不同、引导不开）。 */
        @GetMapping("/api/projects")
        public String listProjects() {
            return "ok";
        }

        @GetMapping("/public/probe")
        public String publicProbe() {
            return "ok";
        }
    }

    @Test
    void given_no_user_context_when_request_api_then_401_with_unified_envelope() throws Exception {
        mockMvc.perform(get("/api/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void given_session_cookie_when_request_api_then_filter_binds_context_and_allowed() throws Exception {
        // 走真实链路：BffSessionContextFilter 凭 cookie 绑定上下文 → 拦截器放行
        when(sessionStore.get("sid-1")).thenReturn(Optional.of(
                new BffSession(7L, "张三", "idt", "at", "rt")));

        mockMvc.perform(get("/api/probe")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1")))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void given_valid_guest_credential_when_request_api_then_allowed() throws Exception {
        // #326：无账户会话但有有效访客凭证 → 请求上下文带访客维度、过 /api/** 拦截面
        when(guestAppService.resolve("cred-1")).thenReturn(Optional.of(
                new GuestAppService.ResolvedGuest(88L, false)));

        mockMvc.perform(get("/api/probe")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.GUEST_COOKIE_NAME, "cred-1")))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void given_invalid_guest_credential_when_request_api_then_401() throws Exception {
        // 过期/作废/未知凭证不认：无访客维度 → 401（数据保留在服务端，前端弹 M01）
        when(guestAppService.resolve("stale")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/probe")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookies.GUEST_COOKIE_NAME, "stale")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void given_anonymous_when_create_project_then_allowed_as_lazy_guest_entry() throws Exception {
        // 匿名首建引导（#326 惰性创建的入口面）：POST /api/projects 集合端点无凭证
        // 放行——首条需求消息提交成功才建访客行＋发凭证，只逛零足迹
        mockMvc.perform(post("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void given_anonymous_when_get_projects_then_still_401() throws Exception {
        // 引导只开建项目首入口：列表/其余 /api/** 匿名仍 401（列表按 owner 过滤，
        // 无身份上下文无从归属）
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void given_no_user_context_when_request_non_api_then_not_intercepted() throws Exception {
        // /auth/**、swagger、actuator 不在 /api 下——注册模式天然放行
        mockMvc.perform(get("/public/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void given_async_dispatch_without_context_when_pre_handle_then_allowed() {
        // SSE emitter 完成触发的 ASYNC 再入在另一线程，上下文已不在——不得再拦
        MockHttpServletRequest asyncRequest = new MockHttpServletRequest("GET", "/api/events");
        asyncRequest.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);

        assertThat(new ApiAuthInterceptor().preHandle(asyncRequest, null, null)).isTrue();
    }

    // -------- 测试工具 --------
}
