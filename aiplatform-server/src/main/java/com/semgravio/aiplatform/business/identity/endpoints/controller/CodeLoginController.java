package com.semgravio.aiplatform.business.identity.endpoints.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.identity.domain.model.AuthCookies;
import com.semgravio.aiplatform.business.identity.application.CodeLoginAppService;
import com.semgravio.aiplatform.business.identity.application.dto.command.SendVerificationCodeCommand;
import com.semgravio.aiplatform.business.identity.application.dto.command.VerifyCodeLoginCommand;
import com.semgravio.aiplatform.business.identity.application.dto.response.CodeSentResponse;
import com.semgravio.aiplatform.business.identity.application.dto.response.MeResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 验证码登录端点（#327，M01 弹窗后端面）：JSON 数据端点（区别于 AuthController
 * 的 OIDC 302 跳转流，两流并存——OIDC 收而不删）。/auth/** 不在 /api/** 拦截面，
 * 未登录可调（登录自身）；会话 cookie 由应用层装配，本层只落 Set-Cookie 头。
 */
@RestController
@Tag(name = "验证码登录", description = "平台内自建发码/验码登录（business.identity，M01）")
public class CodeLoginController {

    private final CodeLoginAppService appService;

    public CodeLoginController(CodeLoginAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/auth/code")
    @Operation(summary = "发码（手机号/邮箱）", description = """
            M01 弹窗第一步：输入手机号/邮箱（单输入，通道按形态判定）发六位数字验证码。
            有效期/发码冷却窗/试错上限走配置（回执带有效期秒数）。错误信封数字业务码：
            目标形态无效 400→6005；通道未开放（上线先邮箱，手机号开关位在配置）
            403→6006；频控 429→6007。发送服务商＝上线参数（机制先行、配置驱动）。""")
    public ApiResponse<CodeSentResponse> sendCode(@RequestBody SendVerificationCodeCommand command) {
        return ApiResponse.ok(appService.sendCode(command.target()));
    }

    @PostMapping("/auth/verify")
    @Operation(summary = "验码登录（不存在即静默建账户）", description = """
            M01 弹窗第二步：验码即登录——无密码，账户不存在则静默建档（显示名缺省＝
            标识原文）＋种 aiplatform_session 会话 cookie（契约与 OIDC 登录同源）。
            登录即自动平移（#328）：请求携带有效访客凭证（aiplatform_guest）时把访客
            名下未绑定项目过户给账户并作废访客行，响应清访客凭证 cookie；凭证无效/过期
            仅清 cookie 不平移，不阻断登录。验过即消费（一次性）。目标形态无效
            400→6005；验证码错误或已过期（含超次作废、无在途码，不区分错环节）400→6008。
            回执＝当前账号（MeResponse 同形）。""")
    public ResponseEntity<ApiResponse<MeResponse>> verify(
            @RequestBody VerifyCodeLoginCommand command,
            @CookieValue(value = AuthCookies.GUEST_COOKIE_NAME, required = false) String guestCredential,
            HttpServletResponse response) {
        CodeLoginAppService.CodeLoginCompletion completion =
                appService.verifyAndLogin(command.target(), command.code(), guestCredential);
        response.addHeader(HttpHeaders.SET_COOKIE, completion.sessionCookie().toString());
        if (completion.guestClearCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, completion.guestClearCookie().toString());
        }
        return ResponseEntity.ok(ApiResponse.ok(completion.account()));
    }
}
