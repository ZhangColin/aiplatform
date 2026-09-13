package com.aieducenter.aiplatform.business.identity.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.identity.application.BackofficeAccountAppService;
import com.aieducenter.aiplatform.business.identity.application.dto.response.BackofficeAccountProfileResponse;

/**
 * 后台账号 REST 面（#154 账号域，机机签名）：唯一读口＝按 externalId 查极简
 * 档案（id/externalId/displayName/createdAt 原样）。externalId＝OIDC sub＝
 * identity 账户 Id，对外正身（我方 accountId 只是内部代理键）；无清单浏览、
 * 无 identity 富化——联系方式/封禁等身份管理归 identity/admin 侧。类级
 * {@code @RequireSignature} 强制闸，该前缀经 WebMvcConfig 排除会话拦截
 * （签名＝认证，无用户会话可访问）。错误码前缀 IDN_（未命中 IDN_004）。
 */
@RestController
@RequestMapping("/api/backoffice/accounts")
@RequireSignature
@Tag(name = "Backoffice Accounts", description = "后台账号：按 externalId 查极简档案（机机签名）")
public class BackofficeAccountController {

    private final BackofficeAccountAppService appService;

    public BackofficeAccountController(BackofficeAccountAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/{externalId}")
    @Operation(summary = "账号极简档案（按 externalId）",
            description = "监管场景确认用户身份正身：externalId＝OIDC sub＝identity 账户 Id。"
                    + "返回我方留存四字段原样（id/externalId/displayName/createdAt，"
                    + "id 为 TSID 十进制字符串）；无 identity 富化（联系方式/封禁等归 identity/admin 侧）。"
                    + "需要机机签名（五头 HMAC）；externalId 未命中 404 IDN_004")
    @ErrorCodes({"IDN_004"})
    public ApiResponse<BackofficeAccountProfileResponse> profile(@PathVariable String externalId) {
        return ApiResponse.ok(appService.profile(externalId));
    }
}
