package com.aieducenter.aiplatform.business.project.endpoints.interceptor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目域触碰拦截器（#170 唤醒触发面）：路径提取与委派——项目域全部端点形态
 * （详情/消息/预览/文件/版本）都在面内，非项目域（backoffice、无 id 集合端点、
 * 非 /api/projects 前缀）不触发；触碰失败不阻断请求。
 */
@ExtendWith(MockitoExtension.class)
class ProjectTouchInterceptorTest {

    @Mock
    private ProjectLifecycleAppService appService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Test
    void given_project_detail_path_when_pre_handle_then_touched() {
        when(request.getRequestURI()).thenReturn("/api/projects/100");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);

        assertThat(interceptor().preHandle(request, response, new Object())).isTrue();

        verify(appService).touchProject(100L);
    }

    @Test
    void given_project_subresource_paths_when_pre_handle_then_all_touched() {
        // 详情/消息/预览/文件/版本/问答——项目域子资源全形态在触发面内
        for (String uri : new String[]{
                "/api/projects/100/messages", "/api/projects/100/preview",
                "/api/projects/100/files", "/api/projects/100/files/content",
                "/api/projects/100/versions", "/api/projects/100/versions/abc123",
                "/api/projects/100/conversation", "/api/projects/100/questions/9/answer"}) {
            when(request.getRequestURI()).thenReturn(uri);
            when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);

            assertThat(interceptor().preHandle(request, response, new Object())).isTrue();
        }

        verify(appService, times(8)).touchProject(100L);
    }

    @Test
    void given_non_project_paths_when_pre_handle_then_not_touched() {
        // 建项目（无 id）、项目列表、backoffice（对接面）、非项目域——都不触发
        for (String uri : new String[]{
                "/api/projects", "/api/projects?status=1",
                "/api/backoffice/projects/100", "/api/orders/900", "/api/events"}) {
            when(request.getRequestURI()).thenReturn(uri);
            when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);

            assertThat(interceptor().preHandle(request, response, new Object())).isTrue();
        }

        verify(appService, never()).touchProject(anyLong());
    }

    @Test
    void given_invalid_id_when_pre_handle_then_not_touched_and_pass_through() {
        when(request.getRequestURI()).thenReturn("/api/projects/not-a-number");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);

        assertThat(interceptor().preHandle(request, response, new Object())).isTrue();

        verify(appService, never()).touchProject(anyLong());
    }

    @Test
    void given_touch_throws_when_pre_handle_then_request_still_passes() {
        when(request.getRequestURI()).thenReturn("/api/projects/100");
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        doThrow(new IllegalStateException("boom"))
                .when(appService).touchProject(100L);

        assertThatCode(() -> interceptor().preHandle(request, response, new Object()))
                .doesNotThrowAnyException();
        assertThat(interceptor().preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void given_async_dispatch_when_pre_handle_then_not_touched() {
        // SSE ASYNC 再入不重复触碰（首查 dispatcher type 即返回，路径不读）
        when(request.getDispatcherType()).thenReturn(DispatcherType.ASYNC);

        assertThat(interceptor().preHandle(request, response, new Object())).isTrue();

        verify(appService, never()).touchProject(anyLong());
    }

    private ProjectTouchInterceptor interceptor() {
        return new ProjectTouchInterceptor(appService);
    }
}
