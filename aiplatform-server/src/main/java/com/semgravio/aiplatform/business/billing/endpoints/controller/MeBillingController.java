package com.semgravio.aiplatform.business.billing.endpoints.controller;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.billing.application.BillingQueryAppService;
import com.semgravio.aiplatform.business.billing.application.dto.response.AccountBillingView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「我的账户」读面端点（#534 计费④）：账户池现值＋账户池流水（append-only，
 * 新先序）——「我的账户」页双池余额消费面之一（项目余额逐项目走项目账单读面，
 * 前端拿既有项目清单并联：billing 弱引用不跨 BC join 项目名）。账户端点：
 * 无账户会话 401（同 /api/me——访客凭证过得了拦截面、过不了账户端点）。
 */
@RestController
@Tag(name = "MyBilling", description = "我的账户：账户池余额与流水（business.billing）")
public class MeBillingController {

    private final BillingQueryAppService queryAppService;

    public MeBillingController(BillingQueryAppService queryAppService) {
        this.queryAppService = queryAppService;
    }

    @GetMapping("/api/me/billing")
    @Operation(summary = "我的账户账单（账户池余额＋流水）",
            description = "accountPool＝账户池现值（从未充值 null——零态呈现归前端）；"
                    + "entries＝账户池流水（新先序：金额带符号〔分〕＋行后余额＋种类中文名＋备注）。"
                    + "账户池扣减行带 projectId（段归属）——前端可并联项目名呈现「某项目消耗」。"
                    + "项目余额＋项目池流水＋项目充值入口走 /api/projects/{id}/billing。"
                    + "无账户会话 401（账户端点口径，同 /api/me）。")
    public ApiResponse<AccountBillingView> myBilling() {
        return ApiResponse.ok(queryAppService.accountView(RequestContext.getUserId()));
    }
}
