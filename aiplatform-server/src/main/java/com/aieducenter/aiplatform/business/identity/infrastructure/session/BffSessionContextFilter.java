package com.aieducenter.aiplatform.business.identity.infrastructure.session;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cartisan.core.context.RequestContext;

import com.aieducenter.aiplatform.business.identity.application.GuestAppService;
import com.aieducenter.aiplatform.business.identity.domain.model.AuthCookies;
import com.aieducenter.aiplatform.web.GuestContext;

/**
 * 会话 → RequestContext 过滤器（A2 §2 表 6，ADR-0001：顶替 cartisan-security
 * SecurityFilter 的位置，业务代码读法不变）。#326 起并装访客维度（GuestContext）。
 *
 * <p>cookie → 会话 → {@code userId = accountId}（内部 PK，非 sub）、
 * {@code userName = 显示名}；无有效会话时显式清空用户字段——顺带洗掉请求头携带的
 * {@code X-User-Id}/{@code X-User-Name}（cartisan RequestContextFilter 会透传机机
 * 调用的头；v1 用户面全部以 BFF 会话为准，伪造头不得残留进上下文/审计字段）。</p>
 *
 * <p><b>访客维度（#326）</b>：无账户会话且带访客凭证 cookie 时，经
 * {@link GuestAppService#resolve} 无状态直查（行存在且未作废且在保留期内即有效）
 * 绑定 {@link GuestContext}；<b>账户会话优先于访客凭证</b>——会话在场即不解析访客
 * （不绑不查）。滑动续期随节流写触发时重发同值 cookie 刷新浏览器侧寿命（双端同窗）
 * ——节流窗口内零写零 Set-Cookie。</p>
 *
 * <p>豁免 {@code /api/backoffice/**}（#152）：机机签名面由 cartisan-openapi 五头
 * HMAC 担保调用应用，操作者透传头在该面内明示信任（#149 口径：签名担保调用应用、
 * 不担保操作者本人）——本过滤器整段跳过，cartisan RequestContextFilter 绑定的
 * 头原样穿链。操作者＝admin 侧管理员标识（TSID＋昵称，{@code X-User-Id} Long＋
 * {@code X-User-Name}），<b>非本平台用户</b>，落库/读数不得与 accountId/externalId
 * 混读；会话 cookie 即便捎带也不参与该面装配。用户面洗头行为不变。</p>
 *
 * <p>顺序：紧跟 cartisan RequestContextFilter（{@code MIN_VALUE}，绑定 requestId/
 * clientIp 与 ScopedValue 骨架）之后；本过滤器内用 {@code withUser} 嵌套重绑，
 * 保留 requestId/clientIp。{@code /api/**} 的强制拦截不在本层（filter 抛的异常进不了
 * 全局异常映射），归 {@code ApiAuthInterceptor}。</p>
 *
 * <p>会话存储与访客用例经 {@link ObjectProvider} 注入：@WebMvcTest 切片会收 Filter
 * bean 但不含 identity BC 的存储——整个认证机制在切片中缺席时本过滤器静默放行
 * （切片要测的是单个 controller 的契约，鉴权行为归 identity BC 自己的测试）。</p>
 */
@Component
public class BffSessionContextFilter extends OncePerRequestFilter implements Ordered {

    /** cartisan RequestContextFilter = MIN_VALUE；本过滤器紧随其后 */
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    /**
     * 后台机机面路径前缀（#152 豁免）：该前缀不经会话装配与洗头。裸 URI 前缀
     * 比较，口径对齐 WebMvcConfig 的 {@code /api/backoffice/**} 拦截排除——
     * 若引入 context-path 或裸前缀端点，两处闸口须同步调整。
     */
    public static final String BACKOFFICE_PATH_PREFIX = "/api/backoffice/";

    /** cartisan 过滤器缺席（窄测试上下文等）时的兜底骨架上下文 */
    private static final RequestContext BARE =
            new RequestContext(null, null, null, null, null, null, null, null);

    private final ObjectProvider<BffSessionStore> sessionStoreProvider;
    private final ObjectProvider<GuestAppService> guestAppServiceProvider;

    public BffSessionContextFilter(ObjectProvider<BffSessionStore> sessionStoreProvider,
            ObjectProvider<GuestAppService> guestAppServiceProvider) {
        this.sessionStoreProvider = sessionStoreProvider;
        this.guestAppServiceProvider = guestAppServiceProvider;
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(BACKOFFICE_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        BffSessionStore sessionStore = sessionStoreProvider.getIfAvailable();
        if (sessionStore == null) {
            chain.doFilter(request, response);
            return;
        }
        BffSession session = sessionStore.get(cookieValue(request, AuthCookies.SESSION_COOKIE_NAME))
                .orElse(null);
        RequestContext current = RequestContext.CONTEXT.isBound()
                ? RequestContext.CONTEXT.get()
                : BARE;
        RequestContext bound = session != null
                ? current.withUser(session.accountId(), session.displayName())
                : current.withUser(null, null);
        // 账户会话优先于访客凭证（spec 段②）：无会话才解析访客维度
        Long guestId = session == null
                ? resolveGuest(request, response, guestAppServiceProvider.getIfAvailable())
                : null;
        try {
            GuestContext.runWith(guestId, () ->
                    RequestContext.runFor(bound, () -> {
                        chain.doFilter(request, response);
                        return null;
                    }));
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // runFor 声明 throws Exception；上面的具名分支已覆盖实际可抛集合
            throw new ServletException(e);
        }
    }

    /**
     * 访客凭证解析（无状态直查）：行存在且未作废且在保留期内即绑定访客维度；
     * 滑动续期随节流写触发时重发同值 cookie（MaxAge 对齐保留期——浏览器侧寿命
     * 与服务端窗口同步滑动）。解析缺席（切片上下文）/凭证无效皆返回 null（不认）。
     */
    private static Long resolveGuest(HttpServletRequest request, HttpServletResponse response,
            GuestAppService guestAppService) {
        if (guestAppService == null) {
            return null;
        }
        return guestAppService
                .resolve(cookieValue(request, AuthCookies.GUEST_COOKIE_NAME))
                .map(resolved -> {
                    if (resolved.renewed() && resolved.guestId() != null) {
                        // 续期写已发生：同值重发刷新浏览器侧寿命（值不变只顺寿）
                        String credential = cookieValue(request, AuthCookies.GUEST_COOKIE_NAME);
                        response.addHeader(HttpHeaders.SET_COOKIE,
                                guestAppService.credentialCookie(credential).toString());
                    }
                    return resolved.guestId();
                })
                .orElse(null);
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
