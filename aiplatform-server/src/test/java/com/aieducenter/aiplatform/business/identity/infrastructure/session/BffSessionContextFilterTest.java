package com.aieducenter.aiplatform.business.identity.infrastructure.session;

import java.util.Optional;

import com.cartisan.core.context.RequestContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.aieducenter.aiplatform.business.identity.application.GuestAppService;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.web.GuestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话 → RequestContext 过滤器：有效会话绑定 accountId/显示名、无会话清空用户
 * （含洗掉透传的 X-User-* 头）、会话 id 未知视同无会话；#326 起访客维度装配
 * （无账户会话时凭证直查绑定 GuestContext、账户会话优先、滑动续期随节流写刷新
 * cookie 寿命）。
 *
 * <p>store 以 mock 注入（未桩定的 get 一律 empty——#329 起 store 落 PG，真行为
 * 归 BffSessionStoreIntegrationTest；本类只测过滤器的装配与洗头逻辑）。</p>
 */
class BffSessionContextFilterTest {

    private final BffSessionStore sessionStore = mock(BffSessionStore.class);
    private final GuestAppService guestAppService = mock(GuestAppService.class);
    private final BffSessionContextFilter filter =
            new BffSessionContextFilter(providerOf(sessionStore), providerOf(guestAppService));

    /** ObjectProvider 替身：getIfAvailable 返回给定存储（null = 切片缺席场景） */
    private static <T> ObjectProvider<T> providerOf(T store) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfAvailable() {
                return store;
            }
        };
    }

    private static ObjectProvider<BffSessionStore> emptyProvider() {
        return providerOf(null);
    }

    @Test
    void given_no_store_in_context_when_filter_then_passthrough_without_rebinding()
            throws Exception {
        // @WebMvcTest 切片：Filter bean 在、会话存储缺席 → 静默放行（不绑不洗）
        BffSessionContextFilter bareFilter =
                new BffSessionContextFilter(emptyProvider(), providerOf(null));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workspaces");

        bareFilter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                captured = new Captured(RequestContext.getUserId(), RequestContext.getUserName(),
                        RequestContext.getRequestId(), GuestContext.guestId()));

        assertThat(captured.userId).isNull();
    }

    @Test
    void given_valid_session_cookie_when_filter_then_user_bound_with_account_id_and_display_name()
            throws Exception {
        when(sessionStore.get("sid-1")).thenReturn(Optional.of(session(42L, "张三")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1"));

        runAndCapture(request);

        assertThat(captured.userId).isEqualTo(42L);
        assertThat(captured.userName).isEqualTo("张三");
    }

    @Test
    void given_no_cookie_when_filter_then_user_cleared() throws Exception {
        runAndCapture(new MockHttpServletRequest("GET", "/api/me"));

        assertThat(captured.userId).isNull();
        assertThat(captured.userName).isNull();
    }

    @Test
    void given_unknown_session_id_when_filter_then_user_cleared() throws Exception {
        // 未桩定的 get 一律 empty（mock 缺省）：cookie 值无行可查＝未知会话
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "other"));

        runAndCapture(request);

        assertThat(captured.userId).isNull();
    }

    @Test
    void given_forged_user_headers_without_session_when_filter_then_not_visible_downstream()
            throws Exception {
        // cartisan RequestContextFilter 会把 X-User-Id/Name 头透传进上下文；
        // 无 BFF 会话时本过滤器必须洗掉——伪造头不得残留在上下文/审计字段
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("X-User-Id", "999");
        request.addHeader("X-User-Name", "attacker");

        RequestContext forged = new RequestContext("req-1", "127.0.0.1", null, null,
                999L, "attacker", null, null);
        runUnderContext(forged, request);

        assertThat(captured.userId).isNull();
        assertThat(captured.userName).isNull();
        assertThat(captured.requestId).isEqualTo("req-1"); // 非用户字段保留
    }

    @Test
    void given_backoffice_path_when_filter_then_skipped_operator_headers_stand() throws Exception {
        // #152：/api/backoffice/** 豁免会话上下文装配——操作者透传头（cartisan
        // RequestContextFilter 绑定，签名面内明示信任）不经本过滤器洗刷
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/backoffice/orders");
        request.addHeader("X-User-Id", "700100");
        request.addHeader("X-User-Name", "运营·小刘");

        runUnderContext(new RequestContext("req-1", "127.0.0.1", null, null,
                700100L, "运营·小刘", null, null), request);

        assertThat(captured.userId).isEqualTo(700100L);
        assertThat(captured.userName).isEqualTo("运营·小刘");
    }

    @Test
    void given_backoffice_path_with_stray_session_cookie_when_filter_then_headers_still_stand()
            throws Exception {
        // 签名面以透传头为准：即便请求捎带了用户会话 cookie 也不参与装配
        // （机机调用无会话，cookie 只可能是串味——头明示信任、会话不越权）
        when(sessionStore.get("sid-1")).thenReturn(Optional.of(session(42L, "张三")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/backoffice/orders");
        request.addHeader("X-User-Id", "700100");
        request.addHeader("X-User-Name", "运营·小刘");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1"));

        runUnderContext(new RequestContext("req-1", "127.0.0.1", null, null,
                700100L, "运营·小刘", null, null), request);

        assertThat(captured.userId).isEqualTo(700100L);
        assertThat(captured.userName).isEqualTo("运营·小刘");
    }

    // -------- 测试工具 --------

    /** 在给定上下文（模拟 cartisan RequestContextFilter 先行绑定）下跑过滤器并捕获下游视界 */
    private void runUnderContext(RequestContext context, MockHttpServletRequest request)
            throws Exception {
        RequestContext.run(context, () -> {
            try {
                runAndCapture(request);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void given_valid_session_over_forged_header_when_filter_then_session_wins() throws Exception {
        when(sessionStore.get("sid-1")).thenReturn(Optional.of(session(42L, "张三")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("X-User-Id", "999");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1"));

        RequestContext forged = new RequestContext("req-1", "127.0.0.1", null, null,
                999L, "attacker", null, null);
        RequestContext.run(forged, () -> {
            try {
                runAndCapture(request);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(captured.userId).isEqualTo(42L);
        assertThat(captured.userName).isEqualTo("张三");
    }

    // ---------- #326 访客维度装配 ----------

    @Test
    void given_guest_credential_without_account_session_when_filter_then_guest_bound()
            throws Exception {
        // 值即凭证：无账户会话时凭证直查，行有效即绑定 GuestContext（下游读
        // GuestContext.guestId()——/api/** 拦截面与项目 owner 谓词的访客腿）
        givenGuestCredential("cred-1", 88L, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.GUEST_COOKIE_NAME, "cred-1"));

        runAndCapture(request);

        assertThat(captured.userId).isNull(); // 访客维度不冒充账户维度
        assertThat(captured.guestId).isEqualTo(88L);
    }

    @Test
    void given_account_session_and_guest_credential_when_filter_then_account_wins_guest_unbound()
            throws Exception {
        // 账户会话优先于访客凭证（spec 段②）：会话在场即不解析访客——不绑、不查库
        when(sessionStore.get("sid-1")).thenReturn(Optional.of(session(42L, "张三")));
        givenGuestCredential("cred-1", 88L, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.setCookies(
                new jakarta.servlet.http.Cookie(AuthCookies.SESSION_COOKIE_NAME, "sid-1"),
                new jakarta.servlet.http.Cookie(AuthCookies.GUEST_COOKIE_NAME, "cred-1"));

        runAndCapture(request);

        assertThat(captured.userId).isEqualTo(42L);
        assertThat(captured.guestId).isNull();
        verify(guestAppService, never()).resolve(any());
    }

    @Test
    void given_invalid_guest_credential_when_filter_then_nothing_bound_and_no_cookie_touched()
            throws Exception {
        // 行不存在/过期/作废＝不认（empty）：无访客维度、不补发 cookie
        when(guestAppService.resolve("stale")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.GUEST_COOKIE_NAME, "stale"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> captured = new Captured(
                RequestContext.getUserId(), RequestContext.getUserName(),
                RequestContext.getRequestId(), GuestContext.guestId()));

        assertThat(captured.userId).isNull();
        assertThat(captured.guestId).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    @Test
    void given_renewed_credential_when_filter_then_cookie_lifetime_refreshed()
            throws Exception {
        // 滑动续期随节流写刷新浏览器侧寿命（双端同窗）：renewed 时重发同值 cookie
        givenGuestCredential("cred-1", 88L, true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.GUEST_COOKIE_NAME, "cred-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> captured = new Captured(
                RequestContext.getUserId(), RequestContext.getUserName(),
                RequestContext.getRequestId(), GuestContext.guestId()));

        assertThat(captured.guestId).isEqualTo(88L);
        assertThat(response.getHeader("Set-Cookie"))
                .contains(AuthCookies.GUEST_COOKIE_NAME + "=cred-1")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    void given_valid_credential_without_renewal_when_filter_then_no_set_cookie()
            throws Exception {
        // 节流窗口内零写：不重发 cookie（读多写少，Set-Cookie 只随续期写走）
        givenGuestCredential("cred-1", 88L, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.setCookies(new jakarta.servlet.http.Cookie(AuthCookies.GUEST_COOKIE_NAME, "cred-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> captured = new Captured(
                RequestContext.getUserId(), RequestContext.getUserName(),
                RequestContext.getRequestId(), GuestContext.guestId()));

        assertThat(captured.guestId).isEqualTo(88L);
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    private void givenGuestCredential(String credential, long guestId, boolean renewed) {
        when(guestAppService.resolve(credential))
                .thenReturn(Optional.of(new GuestAppService.ResolvedGuest(guestId, renewed)));
        when(guestAppService.credentialCookie(credential)).thenReturn(
                org.springframework.http.ResponseCookie.from(AuthCookies.GUEST_COOKIE_NAME, credential)
                        .httpOnly(true).sameSite("Lax").path("/").maxAge(3600).build());
    }

    // -------- 测试工具 --------

    private record Captured(Long userId, String userName, String requestId, Long guestId) {
    }

    private Captured captured;

    private static BffSession session(Long accountId, String displayName) {
        return new BffSession(accountId, displayName, "idt", "at", "rt");
    }

    /** 过滤后由下游链条读取 RequestContext（模拟 interceptor/controller 视角） */
    private void runAndCapture(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> captured = new Captured(
                RequestContext.getUserId(), RequestContext.getUserName(),
                RequestContext.getRequestId(), GuestContext.guestId()));
    }
}
