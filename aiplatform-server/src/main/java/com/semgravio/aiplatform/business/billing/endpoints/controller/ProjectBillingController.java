package com.semgravio.aiplatform.business.billing.endpoints.controller;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.billing.application.BillingQueryAppService;
import com.semgravio.aiplatform.business.billing.application.CheckoutAppService;
import com.semgravio.aiplatform.business.billing.application.dto.command.CheckoutTopupCommand;
import com.semgravio.aiplatform.business.billing.application.dto.response.ProjectBillingView;
import com.semgravio.aiplatform.business.billing.application.CheckoutAppService.CheckoutReceipt;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.support.Tsid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目账单与项目上下文充值端点（#534 计费④）：挂在 /api/projects/{id}/billing
 * 命名空间——<b>复用 project owner 守卫</b>（WebMvcConfig 统一归属拦截先于控制器：
 * 非本人与不存在同 404 PRJ_001，不泄露存在性）与触碰拦截（读面唤醒口径同既有
 * 项目读端点）。读面＝项目池现值＋计费态投影＋流水＋收银台充值目标（只充当前
 * 扣减源池的服务端推导）；充值口＝项目池入账（试用项目入账即选定项目源）。
 */
@RestController
@RequestMapping("/api/projects/{id}/billing")
@Tag(name = "ProjectBilling", description = "项目账单：项目池余额/流水/充值（business.billing，owner 守卫面）")
public class ProjectBillingController {

    private final BillingQueryAppService queryAppService;
    private final CheckoutAppService checkoutAppService;

    public ProjectBillingController(BillingQueryAppService queryAppService,
            CheckoutAppService checkoutAppService) {
        this.queryAppService = queryAppService;
        this.checkoutAppService = checkoutAppService;
    }

    @GetMapping
    @Operation(summary = "项目账单（项目池余额＋计费态＋流水＋充值目标）",
            description = "pool＝项目池现值（从未入账 null）；sourceSelected/deductionSource/"
                    + "deductionSourceName＝扣减源投影（两轨判定单读点）；paid＝付费状态（不可逆）；"
                    + "checkoutTarget/checkoutTargetName＝收银台充值目标（码位与扣减源对齐："
                    + "1=我的账户 2=本项目——「只充当前扣减源池」服务端推导：源未选或项目源＝充项目池；"
                    + "账户源＝引导充我的账户）；entries＝项目池流水"
                    + "（新先序）。归属守卫同单项目寻址统一口径（先于控制器）；项目不存在 404 PRJ_001")
    public ApiResponse<ProjectBillingView> billing(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.projectView(parseId(id)));
    }

    @PostMapping("/topup")
    @Operation(summary = "项目上下文充值（充项目池——只充当前扣减源池）",
            description = "准入码＋金额（分）——入账即时到账＋append-only 流水（备注「模拟收银台充值」）。"
                    + "源未选（试用项目）＝入账即选定项目源（动作即选择，拦截卡回读即显源池余额）；"
                    + "项目源＝直充；<b>账户源拒绝 409 BILL_020</b>（死钱防线——该上下文源池是账户池，"
                    + "前端按 checkoutTarget 不呈现本选项、引导「我的账户」充值）。须先登录：无账户会话"
                    + " 401；码错 400 BILL_019；金额非正整数 400 BILL_002。归属守卫同读面。")
    public ApiResponse<CheckoutReceipt> topup(@PathVariable String id,
            @RequestBody(required = false) CheckoutTopupCommand command) {
        return ApiResponse.ok(checkoutAppService.topupProject(
                RequestContext.getUserId(), parseId(id), command));
    }

    /** 寻址解析收口（{@link Tsid}，畸形标识 → 404 PRJ_001——与 ProjectController 同口径）。 */
    private Long parseId(String id) {
        return Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND);
    }
}
