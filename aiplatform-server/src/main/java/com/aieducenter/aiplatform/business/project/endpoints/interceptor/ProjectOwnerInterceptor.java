package com.aieducenter.aiplatform.business.project.endpoints.interceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.web.GuestContext;

/**
 * 项目 owner 守卫拦截器（#325 修「单账号假设」安全缺口——单项目操作/详情按
 * owner 校验；#326 扩访客维度）：{@code /api/projects/{id}/**} 全形态单项目寻址
 * 在进控制器前过归属判定，非本人项目与不存在项目同抛 404 PRJ_001——<b>同一信封
 * 不可分辨、不泄露存在性</b>（与 {@code Tsid.resolve} 寻址口径同归宿）。
 *
 * <p>边界与口径：</p>
 * <ul>
 *   <li>归属谓词＝{@link Project#ownedBy(Long, Long)}（owner 双维：账户侧/访客侧
 *       等值匹配，账户优先、账户与访客项目互不可见；「无主对无主」容忍面同源）；
 *       订阅者身份由 {@code RequestContext}＋{@code GuestContext} 携带
 *       （/api/** 拦截面保证匿名 401，本层不重复判定）；</li>
 *   <li>集合端点（建项目/列表，无 id 段）不进守卫——列表过滤归
 *       {@code ProjectQueryAppService}、建项目归属随会话落列；</li>
 *   <li>畸形/超限数字标识＝「不存在的标识」直接 404，不进查库（与控制器寻址
 *       口径一致，守卫先行故控制器不再见到这类段）；</li>
 *   <li>后台机机面（{@code /api/backoffice/**}）不在本拦截器路径模式内——后台
 *       全量可视维持（行为红线）；</li>
 *   <li>守卫先于触碰拦截器注册：越权请求不触发他人沙箱的唤醒副作用。</li>
 * </ul>
 *
 * <p>SSE 订阅（/api/events）不经本层：投递按 ADR-0018 归属路由键隔离（他人
 * projectId 过滤订阅＝静默空流，同不泄露存在性）。</p>
 */
public class ProjectOwnerInterceptor implements HandlerInterceptor {

    /** 项目域路径 → projectId（首段数字；与 {@link ProjectTouchInterceptor} 同源形态）。 */
    private static final Pattern PROJECT_PATH = Pattern.compile("^/api/projects/(\\d+)(/.*)?$");

    private final ProjectRepository projectRepository;

    public ProjectOwnerInterceptor(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true; // SSE ASYNC 再入：首次 REQUEST dispatch 已过守卫
        }
        Matcher matcher = PROJECT_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            return true; // 集合端点（建项目/列表）与畸形段：寻址口径归控制器
        }
        long projectId;
        try {
            projectId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND); // 超限数字段
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null
                || !project.ownedBy(RequestContext.getUserId(), GuestContext.guestId())) {
            // 不存在与非本人同形 404——不泄露存在性
            throw new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND);
        }
        return true;
    }
}
