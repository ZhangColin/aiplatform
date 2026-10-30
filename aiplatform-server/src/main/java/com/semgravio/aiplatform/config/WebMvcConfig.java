package com.semgravio.aiplatform.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.semgravio.aiplatform.business.identity.endpoints.interceptor.ApiAuthInterceptor;
import com.semgravio.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;
import com.semgravio.aiplatform.business.project.endpoints.interceptor.ProjectOwnerInterceptor;
import com.semgravio.aiplatform.business.project.endpoints.interceptor.ProjectTouchInterceptor;

/**
 * Web MVC 全局配置（片0，A2 增补鉴权拦截）。
 *
 * <p>ADR-0001 以 /swagger-ui/index 为 swagger UI 正本地址；该无后缀路径不会命中
 * springdoc 的静态资源（index.html），此处重定向补齐。404 语义恢复见
 * {@code com.semgravio.aiplatform.web.NotFoundExceptionHandler}。</p>
 *
 * <p>A2 起全 {@code /api/**} 拦截（含 SSE 事件端点），无会话 401；白名单路径
 * （/auth/**、/v3/api-docs/**、/swagger-ui/**、actuator）不在 /api 下，天然放行。
 * {@code /api/backoffice/**}（#29 后台机机面）排除会话拦截——鉴权由
 * cartisan-openapi 五头 HMAC 签名闸接管（签名＝认证，无用户会话可访问）。</p>
 *
 * <p>机机签名面（#29/#152，各后台控制器指认本处、不再复述签名说明）：验签机制
 * 实现在 cartisan-openapi（SignatureVerificationFilter/Interceptor 按控制器类级
 * {@code @RequireSignature} 强制闸），本类只声明项目侧用法——五头 HMAC
 * （X-Api-Key / X-Timestamp / X-Nonce / X-Body-Digest / X-Sign），无签名/错签/
 * 过期时间戳一律 401（Signature required / Signature mismatch / Timestamp
 * expired）；验签 Filter 只在带 X-Api-Key 时介入，全裸请求落到强制闸。鉴权拦截
 * 器无状态直接 new（窄测试上下文扫本包时无需 identity BC 的 bean）。</p>
 *
 * <p>#170 唤醒触发面：{@code /api/projects/**} 追加触碰拦截（鉴权之后）——拨
 * last-touch + 异步探查沙箱实态，容器缺失/被杀自动唤醒（用户可见面只有待期
 * 「系统启动中」）。项目编排 bean 经 {@link ObjectProvider} 延迟解析：@WebMvcTest
 * 窄切片（不含 project BC）无此 bean 时跳过注册，切片语义本就不含触碰自愈。</p>
 *
 * <p>#325 owner 守卫：{@code /api/projects/**} 在触碰之前追加归属拦截
 * （{@code ProjectOwnerInterceptor}，仓储同经 {@link ObjectProvider} 延迟解析）
 * ——单项目寻址非本人即 404 PRJ_001，先于触碰故越权请求不触发他人沙箱唤醒。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<ProjectLifecycleAppService> projectLifecycleAppService;

    private final ObjectProvider<ProjectRepository> projectRepository;

    public WebMvcConfig(ObjectProvider<ProjectLifecycleAppService> projectLifecycleAppService,
                        ObjectProvider<ProjectRepository> projectRepository) {
        this.projectLifecycleAppService = projectLifecycleAppService;
        this.projectRepository = projectRepository;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/swagger-ui/index", "/swagger-ui/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiAuthInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/backoffice/**");
        // #325 owner 守卫先于触碰：越权请求 404 在前，不触发他人沙箱的唤醒副作用；
        // 仓储经 ObjectProvider 延迟解析（@WebMvcTest 窄切片无此 bean 时跳过注册，
        // 切片语义本就不含数据面守卫——守卫契约归 ProjectOwnerInterceptorMvcTest
        // 以 mock 仓储装配钉）
        ProjectRepository projects = projectRepository.getIfAvailable();
        if (projects != null) {
            registry.addInterceptor(new ProjectOwnerInterceptor(projects))
                    .addPathPatterns("/api/projects/**");
        }
        ProjectLifecycleAppService lifecycle = projectLifecycleAppService.getIfAvailable();
        if (lifecycle != null) {
            registry.addInterceptor(new ProjectTouchInterceptor(lifecycle))
                    .addPathPatterns("/api/projects/**");
        }
    }
}
