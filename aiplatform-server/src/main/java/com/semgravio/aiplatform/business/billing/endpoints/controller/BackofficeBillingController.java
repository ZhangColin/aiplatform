package com.semgravio.aiplatform.business.billing.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import static com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType.BALANCE_ADJUST;
import static com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType.GRANT;
import static com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType.MANUAL_TOPUP;

import com.semgravio.aiplatform.business.billing.application.BackofficeBillingAppService;
import com.semgravio.aiplatform.business.billing.application.BackofficeBillingAppService.ActionReceipt;
import com.semgravio.aiplatform.business.billing.application.dto.command.AdjustParamCommand;
import com.semgravio.aiplatform.business.billing.application.dto.command.BalanceAdjustCommand;
import com.semgravio.aiplatform.business.billing.application.dto.command.GrantCommand;
import com.semgravio.aiplatform.business.billing.application.dto.command.ManualTopupCommand;
import com.semgravio.aiplatform.business.billing.application.dto.command.ReselectSourceCommand;
import com.semgravio.aiplatform.business.billing.application.dto.response.BackofficeActionView;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.OperatorActionType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.Operator;

/**
 * 后台账户运营 REST 面（#535 计费⑤，ADR-0047「后台账户运营」——机机签名五头
 * HMAC 强制闸，见 {@link com.semgravio.aiplatform.config.WebMvcConfig}）：赠送/
 * 手动充值录入/余额调整/扣减源调整/计费参数调整，全部 append-only 操作者留痕
 * （变更前后快照同事务，{@code bil_operator_actions}——错误码前缀
 * {@code BILL_}）。
 *
 * <p>向项目池入账（赠送/手动充值）即选定该项目扣减源（动作即选择，前台零呈现
 * ——销售演示动线：提前为客户项目充值，客户免登录直接确认扣减）。手动充值
 * 仿凭证核验留痕（实到金额/到账时间/依据必填，缺失即拒 BILL_021）。余额调整
 * 承载平台故障退款返还（人工线，ADR-0047 退款两线）。X-User-Id/X-User-Name
 * 透传头自动落痕（缺头落空——#151 口径）。</p>
 */
@RestController
@RequestMapping("/api/backoffice/billing")
@RequireSignature
@Tag(name = "Backoffice Billing", description = "后台账户运营：赠送/手动充值/余额调整/扣减源调整/参数（机机签名）")
public class BackofficeBillingController {

    private final BackofficeBillingAppService appService;

    public BackofficeBillingController(BackofficeBillingAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/grant")
    @Operation(summary = "赠送（默认入账户池、可指定项目池）",
            description = "入账＋流水＋动作留痕三行同事务；向项目池入账即选定该项目扣减源"
                    + "（动作即选择）。目标账户与项目恰选其一（双缺省/双给 400 BILL_001）；"
                    + "金额非正 400 BILL_002。X-User-Id/X-User-Name 透传头自动落痕（缺头落空）。"
                    + "需要机机签名（五头 HMAC），无签名/错签 401")
    @ErrorCodes({"BILL_001", "BILL_002"})
    public ApiResponse<BackofficeActionView> grant(@RequestBody GrantCommand command) {
        ActionReceipt receipt = appService.grant(command.accountId(), command.projectId(),
                command.amountFen() == null ? 0 : command.amountFen(), command.note(),
                currentOperator());
        return ApiResponse.ok(toView(GRANT, receipt));
    }

    @PostMapping("/manual-topups")
    @Operation(summary = "手动充值录入（对公转账——仿凭证核验留痕）",
            description = "实到金额/到账时间/依据三字段必填（任一缺失即拒 400 BILL_021、"
                    + "零副作用——必填即拒单一口径）；核验字段随动作明细（detail JSONB）"
                    + "落痕。入账口径与赠送同（项目池入账即选源）；备注缺省落"
                    + "「对公转账手动充值」。实到金额显式非正 400 BILL_002；目标不恰一 "
                    + "400 BILL_001。需要机机签名")
    @ErrorCodes({"BILL_001", "BILL_002", "BILL_021"})
    public ApiResponse<BackofficeActionView> manualTopup(@RequestBody ManualTopupCommand command) {
        ActionReceipt receipt = appService.recordManualTopup(command.accountId(),
                command.projectId(), command.receivedAmountFen(),
                command.receivedAt(), command.evidence(), command.note(), currentOperator());
        return ApiResponse.ok(toView(MANUAL_TOPUP, receipt));
    }

    @PostMapping("/adjustments")
    @Operation(summary = "余额调整（平台故障退款返还与纠错同载体）",
            description = "目标池必须已存在（从未入账 404 BILL_022——调整不是入账）；"
                    + "调整额非零带符号（0 400 BILL_003）；原因必填（400 BILL_005）。"
                    + "池变动＋流水调整行＋动作留痕三行同事务。需要机机签名")
    @ErrorCodes({"BILL_001", "BILL_003", "BILL_005", "BILL_022"})
    public ApiResponse<BackofficeActionView> adjust(@RequestBody BalanceAdjustCommand command) {
        ActionReceipt receipt = appService.adjustBalance(command.accountId(), command.projectId(),
                command.delta() == null ? 0 : command.delta(), command.reason(), currentOperator());
        return ApiResponse.ok(toView(BALANCE_ADJUST, receipt));
    }

    @PostMapping("/projects/{projectId}/deduction-source")
    @Operation(summary = "扣减源调整（用户选错池的后台救济通道）",
            description = "覆盖既有选定＋操作者留痕（bil_project_billing 源列＋动作行旧/新"
                    + "源快照）；选「账户余额」须带账户定格（400 BILL_009）；源码缺失/"
                    + "未知 400 BILL_024。源调整不动账（流水/池/余额三列落空）。"
                    + "需要机机签名")
    @ErrorCodes({"BILL_009", "BILL_024"})
    public ApiResponse<BackofficeActionView> reselectSource(
            @PathVariable Long projectId, @RequestBody ReselectSourceCommand command) {
        Long actionId = appService.reselectSource(projectId, resolveSource(command.source()),
                command.accountId(), currentOperator());
        return ApiResponse.ok(BackofficeActionView.sourceOnly(actionId));
    }

    @PostMapping("/params/{paramKey}")
    @Operation(summary = "计费运营参数调整（全局折算率）",
            description = "键不存在 404 BILL_015；值非正 400 BILL_016。参数行 UPDATE 自带"
                    + "操作者留痕（bil_params 非 append-only）；调整只影响此后结算"
                    + "（扣减行快照不漂移）。需要机机签名")
    @ErrorCodes({"BILL_015", "BILL_016"})
    public ApiResponse<Void> adjustParam(@PathVariable String paramKey,
            @RequestBody AdjustParamCommand command) {
        appService.adjustParam(paramKey, command.value(), currentOperator());
        return ApiResponse.ok(null);
    }

    /**
     * 当前操作者（#151 口径）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（admin 侧管理员标识，签名面明示信任、不校验真实
     * 性）；缺头/无上下文为 {@code null}——落空口径。Id 两形转换在此一次完成
     * （上下文 Long → 外域标识字符串）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }

    private static BackofficeActionView toView(OperatorActionType type, ActionReceipt receipt) {
        return new BackofficeActionView(receipt.actionId(), type.getCode(), type.getName(),
                receipt.entryId(), receipt.poolId(), receipt.balanceAfter());
    }

    /** 扣减源码映射：缺失/未知码统一 400 BILL_024（admin 输入面必填守卫）。 */
    private static DeductionSource resolveSource(Integer code) {
        if (code == null) {
            throw new ApplicationException(BillingMessage.SOURCE_CODE_INVALID);
        }
        try {
            return DeductionSource.ofCode(code);
        } catch (IllegalArgumentException e) {
            throw new ApplicationException(BillingMessage.SOURCE_CODE_INVALID);
        }
    }
}
