package com.aieducenter.aiplatform.business.project.endpoints.interceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目域触碰拦截器（#170 唤醒触发面，ADR-0016「唤醒触发面 = 项目 API 自动唤醒」）：
 * {@code /api/projects/**} 每请求拨工作区 last-touch + 异步探查沙箱实态——容器
 * 缺失/被杀（#168 型漂移；休眠后触碰同路径）则自动唤醒重建至预览可用，用户全程只见
 * 待期（系统启动中）。非项目域（backoffice、建项目无 id、SSE 事件端点）不触发。
 * 尽力而为：触碰失败只记日志不阻断业务请求（下次触碰再试）。
 */
@Slf4j
public class ProjectTouchInterceptor implements HandlerInterceptor {

    /** 项目域路径 → projectId（首段数字；无 id 的集合端点不匹配）。 */
    private static final Pattern PROJECT_PATH = Pattern.compile("^/api/projects/(\\d+)(/.*)?$");

    private final ProjectLifecycleAppService appService;

    public ProjectTouchInterceptor(ProjectLifecycleAppService appService) {
        this.appService = appService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;   // SSE ASYNC 再入不重复触碰（首次 REQUEST dispatch 已触）
        }
        Matcher matcher = PROJECT_PATH.matcher(request.getRequestURI());
        if (matcher.matches()) {
            try {
                appService.touchProject(Long.parseLong(matcher.group(1)));
            } catch (RuntimeException e) {
                // 触碰自愈尽力而为：不阻断业务请求
                log.warn("项目域触碰处理失败（放行请求）: {}", request.getRequestURI(), e);
            }
        }
        return true;
    }
}
