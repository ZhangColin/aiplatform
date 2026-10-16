package com.aieducenter.aiplatform.business.identity.endpoints.interceptor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.cartisan.core.context.RequestContext;

import com.aieducenter.aiplatform.web.AuthException;
import com.aieducenter.aiplatform.web.GuestContext;

/**
 * {@code /api/**} 鉴权拦截器（A2 §6：全拦截面含 SSE 事件端点；#326 起访客维度过闸）。
 *
 * <p>白名单（{@code /auth/**}、{@code /v3/api-docs/**}、{@code /swagger-ui/**}、
 * actuator health）不落在 {@code /api/**} 下——按路径模式注册即天然放行。用户
 * 上下文由 {@code BffSessionContextFilter} 绑定（含无会话时清空伪造头、访客凭证
 * 无状态直查后绑 {@code GuestContext}），本层只判定有无：<b>账户会话或有效访客
 * 凭证任一在场即通过</b>。401 经 {@code AuthException} 走既有全局映射。</p>
 *
 * <p><b>匿名首建引导（#326 惰性创建的入口面）</b>：{@code POST /api/projects}
 * （集合端点，精确路径＋方法）对完全匿名放行——首条需求消息提交成功才建访客行＋
 * 发凭证（Set-Cookie 随建项目响应），纯附件/空输入不建（编排层判定）、只逛零足迹。
 * 只开这一个入口：列表等其余 {@code /api/**} 匿名仍 401（无身份上下文无从归属）。</p>
 *
 * <p>异步再入放行：SSE 端点（SseEmitter）完成/超时触发的 ASYNC dispatch 在另一
 * 线程，ScopedValue 上下文已不在——首次 REQUEST dispatch 已完成鉴权，此处不再拦。</p>
 */
public class ApiAuthInterceptor implements HandlerInterceptor {

    /** 匿名首建引导端点（精确 URI）：建项目集合端点＝首条需求消息的提交面。 */
    static final String CREATE_PROJECT_URI = "/api/projects";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        if (RequestContext.getUserId() != null || GuestContext.guestId() != null) {
            return true;
        }
        if (CREATE_PROJECT_URI.equals(request.getRequestURI())
                && "POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        throw AuthException.unauthorized();
    }
}
