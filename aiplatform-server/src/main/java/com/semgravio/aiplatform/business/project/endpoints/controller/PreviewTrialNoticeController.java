package com.semgravio.aiplatform.business.project.endpoints.controller;

import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.project.application.PreviewTrialNoticeAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.TrialNoticeResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试用体验提示端点（#539 访谈④）：{@code /.aiplatform/trial-notice} 裸前缀——有意
 * 落在 {@code /api/**} 会话拦截面外（试用预览的访客无会话，网关注入脚本必须匿名
 * 可达），生产只经预览网关反代暴露（gateway-inject.conf 同前缀 location，
 * X-Preview-Workspace 头由网关子域捕获注入、覆写客户端同名字头——workspaceId
 * 以路由为准）。只读 GET＋no-store：付费翻转的熄灭不得被中间缓存压住。响应载荷
 * 只含判定（{@code trial}），文案在注入脚本侧随实施打磨。
 */
@RestController
@Tag(name = "PreviewTrialNotice", description = "试用体验提示判定（网关反代匿名面，#539）")
public class PreviewTrialNoticeController {

    private final PreviewTrialNoticeAppService noticeAppService;

    public PreviewTrialNoticeController(PreviewTrialNoticeAppService noticeAppService) {
        this.noticeAppService = noticeAppService;
    }

    @GetMapping("/.aiplatform/trial-notice")
    @Operation(summary = "试用体验提示判定",
            description = "trial=true＝试用态（提示显），false＝已付费/未知工作区（熄/不出）。"
                    + "workspaceId 经 X-Preview-Workspace 头（网关注入；头缺席/畸形如实 false）。"
                    + "Cache-Control: no-store。访客匿名可达（裸前缀在 /api 拦截面外）。")
    public ApiResponse<TrialNoticeResponse> notice(
            @RequestHeader(value = "X-Preview-Workspace", required = false) String workspaceId,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(noticeAppService.trialNotice(parseWorkspace(workspaceId)));
    }

    /** 头解析收口：缺席/非数值一律 null——提示面永不因畸形输入 500 阻断预览页。 */
    private Long parseWorkspace(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
