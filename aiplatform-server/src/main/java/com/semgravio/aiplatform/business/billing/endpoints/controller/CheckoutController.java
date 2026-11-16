package com.semgravio.aiplatform.business.billing.endpoints.controller;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.billing.application.CheckoutAppService;
import com.semgravio.aiplatform.business.billing.application.dto.command.CheckoutAccessCodeCommand;
import com.semgravio.aiplatform.business.billing.application.dto.command.CheckoutTopupCommand;
import com.semgravio.aiplatform.business.billing.application.CheckoutAppService.CheckoutReceipt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟收银台端点（#534 计费④，ADR-0047「模拟收银台」词条兑现）：前台充值承载
 * （渠道真接前的形态——流程形态不变只换通道）。准入码校验独立成端点（正确码
 * 才进金额选择——错误/缺失被拦并如实引导 BILL_019）；账户上下文充值充「我的
 * 账户」池（项目上下文充值走 /api/projects/{id}/billing/topup 复用 owner 守卫）。
 * <b>须先登录</b>（CONTEXT：未登录先走 M01——本控制器的充值端点按无账户会话 401，
 * 前端全局出口弹 M01）。
 */
@RestController
@RequestMapping("/api/checkout")
@Tag(name = "Checkout", description = "模拟收银台：准入码校验 / 账户上下文充值（business.billing）")
public class CheckoutController {

    private final CheckoutAppService appService;

    public CheckoutController(CheckoutAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/access-code")
    @Operation(summary = "充值准入码校验（第一步：正确码进入金额选择）",
            description = "码错误/缺失 400 BILL_019（信封数字码 8019——message 指路联系平台索取，"
                    + "如实引导）；正确 200。本端点本身不要求登录态（码非机密——充值入账口才"
                    + "要求先登录）；入账端点对码复校验（独立校验端点可被绕过，入账以复校验为准）。")
    public ApiResponse<Void> accessCode(@RequestBody(required = false) CheckoutAccessCodeCommand command) {
        appService.validateAccessCode(command == null ? null : command.code());
        return ApiResponse.ok();
    }

    @PostMapping("/topup")
    @Operation(summary = "账户上下文充值（充「我的账户」余额池）",
            description = "准入码＋金额（分）——入账即时到账：源池余额立变＋append-only 流水行"
                    + "（备注「模拟收银台充值」、无操作者＝自助形态）。须先登录：无账户会话 401"
                    + "（前端 M01 先行）；码错 400 BILL_019；金额非正整数 400 BILL_002。"
                    + "项目池充值走 /api/projects/{id}/billing/topup（owner 守卫面）。")
    public ApiResponse<CheckoutReceipt> topup(@RequestBody(required = false) CheckoutTopupCommand command) {
        return ApiResponse.ok(appService.topupAccount(RequestContext.getUserId(), command));
    }
}
