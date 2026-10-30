package com.semgravio.aiplatform.business.order.endpoints.controller;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.order.application.PaymentPlanAppService;
import com.semgravio.aiplatform.business.order.application.dto.command.EnterPaymentNodesCommand;
import com.semgravio.aiplatform.business.order.application.dto.command.RejectPaymentCommand;
import com.semgravio.aiplatform.business.order.application.dto.command.VerifyPaymentCommand;
import com.semgravio.aiplatform.business.order.application.dto.response.BackofficePaymentPlanDetailResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.PaymentPlanResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.PaymentVoucherContent;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;
import com.semgravio.aiplatform.support.BinaryResponses;
import com.semgravio.aiplatform.support.Tsid;

/**
 * 后台付款 REST 面（#337 凭证核验承接，机机签名——五头 HMAC 强制闸，admin 页面
 * 归跨仓 semgravio-admin#100）：付款计划详情（节点/凭证/核验留痕时间线＋历史
 * 计划行）＋节点录入/开工门设定＋到账核验录入（实到金额/时间/关联凭证/渠道
 * 流水号）＋打回（依据必填留痕）＋凭证内容点取。核验/打回操作者必携
 * （X-User-Id/X-User-Name 透传头，ORD_043——审计本体动作不落无名留痕）。错误码
 * 前缀 ORD_（计划 ORD_036、节点 ORD_037、状态守卫 ORD_038、录入校验 ORD_039/
 * 041/042、凭证 ORD_040/045、操作者 ORD_043、重录窗口 ORD_044）。
 */
@RestController
@RequestMapping("/api/backoffice")
@RequireSignature
@Validated
@Tag(name = "Backoffice Payments", description = "后台付款：计划详情 / 节点录入与开工门设定 / 到账核验 / 打回 / 凭证内容（机机签名）")
public class BackofficePaymentController {

    private final PaymentPlanAppService appService;

    public BackofficePaymentController(PaymentPlanAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/orders/{orderId}/payment-plan")
    @Operation(summary = "付款计划详情（核验承接全量面）",
            description = "订单名下活跃计划（最新行）全量：节点五态/金额/附言码/开工门标志/"
                    + "渠道位/累计已核到账＋凭证清单（追加保留）＋核验留痕时间线（新 → 旧："
                    + "到账核验〔实到金额/实到时间/关联凭证/依据〕与打回各一行 append-only）"
                    + "＋历史计划行摘要（撤回重建/范围重冻后旧计划随旧合同留痕）。无付款计划"
                    + " 404 ORD_036；订单不存在 404 ORD_001。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"ORD_001", "ORD_036"})
    public ApiResponse<BackofficePaymentPlanDetailResponse> detail(@PathVariable String orderId) {
        return ApiResponse.ok(appService.detailForBackoffice(
                Tsid.resolve(orderId, OrderMessage.ORDER_NOT_FOUND)));
    }

    @PostMapping("/orders/{orderId}/payment-plan/nodes")
    @Operation(summary = "付款节点录入（整组替换，含开工门设定）",
            description = "「付款节点录入/开工门设定」后台承接口：整组提交——每条＝名称/"
                    + "金额（绝对额〔分〕）/开工门标志（布尔、录入时定；开工款≠默认全款）。"
                    + "序号按列表序派生（录入序即展示序）、附言码自合同号定格（<合同号>"
                    + "-K<序>）、渠道起步线下转账（v1 唯一走通路径，扫码占位）。支付活动"
                    + "未开始（全部待支付且无凭证无留痕）是重录唯一窗口，已开始 409 ORD_044"
                    + "（变动走合同侧撤回重建）；录入无效 400 ORD_039。需要机机签名")
    @ErrorCodes({"ORD_001", "ORD_036", "ORD_039", "ORD_044"})
    public ApiResponse<PaymentPlanResponse> enterNodes(@PathVariable String orderId,
            @RequestBody EnterPaymentNodesCommand command) {
        return ApiResponse.ok(appService.enterNodes(
                Tsid.resolve(orderId, OrderMessage.ORDER_NOT_FOUND), command.nodes()));
    }

    @PostMapping("/orders/{orderId}/payment-nodes/{nodeId}/verify")
    @Operation(summary = "到账核验录入（全额判定，依据留痕）",
            description = "凭证核验路径第二跳（后台人工录入）：实到金额（分，正数）/实到时间"
                    + "（ISO-8601 业务时间）/关联凭证（可空，须属该节点——404 ORD_045）/"
                    + "渠道流水号（可空落值）/备注（可空）随行 append-only 留痕。全额判定＝"
                    + "累计核到账 ≥ 节点金额：足额转「已到账」（receivedAt＝实到时间）；"
                    + "不足留痕＋回「待支付」（累计不清零，前台 remainingAmount 推导还需"
                    + "支付）。仅「线下转账待核验」态可达（409 ORD_038）；录入无效 400 "
                    + "ORD_041；操作者必携（X-User-Id/X-User-Name 透传头，400 ORD_043）。"
                    + "需要机机签名")
    @ErrorCodes({"ORD_036", "ORD_037", "ORD_038", "ORD_041", "ORD_043", "ORD_045"})
    public ApiResponse<PaymentPlanResponse> verify(@PathVariable String orderId,
            @PathVariable String nodeId, @RequestBody VerifyPaymentCommand command) {
        return ApiResponse.ok(appService.verifyArrival(
                Tsid.resolve(orderId, OrderMessage.ORDER_NOT_FOUND),
                Tsid.resolve(nodeId, OrderMessage.PAYMENT_NODE_NOT_FOUND), command,
                currentOperator()));
    }

    @PostMapping("/orders/{orderId}/payment-nodes/{nodeId}/reject")
    @Operation(summary = "打回（依据必填留痕，回待支付）",
            description = "凭证主张被否（回单不清、对不上账）：回「待支付」＋依据必填留痕"
                    + "（400 ORD_042——打回依据是审计正身）；关联凭证可空（同核验校验，"
                    + "404 ORD_045）。打回不动累计已核到账（核到账只经核验录入累积）。仅"
                    + "「线下转账待核验」态可达（409 ORD_038）；操作者必携（ORD_043）。"
                    + "需要机机签名")
    @ErrorCodes({"ORD_036", "ORD_037", "ORD_038", "ORD_042", "ORD_043", "ORD_045"})
    public ApiResponse<PaymentPlanResponse> reject(@PathVariable String orderId,
            @PathVariable String nodeId, @RequestBody RejectPaymentCommand command) {
        return ApiResponse.ok(appService.reject(
                Tsid.resolve(orderId, OrderMessage.ORDER_NOT_FOUND),
                Tsid.resolve(nodeId, OrderMessage.PAYMENT_NODE_NOT_FOUND), command,
                currentOperator()));
    }

    @GetMapping("/payment-vouchers/{voucherId}/content")
    @Operation(summary = "凭证内容（后台核验看证，二进制直出）",
            description = "凭证文件字节直出（元数据行上的 fileName/contentType 随行）——后台"
                    + "核验前看证的唯一触字节口。响应为二进制文件流（不走 ApiResponse JSON "
                    + "信封，先例＝源码包端点）。凭证不存在 404 ORD_045。需要机机签名")
    @ErrorCodes({"ORD_045"})
    public ResponseEntity<ByteArrayResource> voucherContent(@PathVariable String voucherId) {
        PaymentVoucherContent content = appService.voucherWithContent(
                Tsid.resolve(voucherId, OrderMessage.PAYMENT_VOUCHER_NOT_FOUND));
        return BinaryResponses.attachment(content.content(), content.contentType(),
                content.fileName());
    }

    /**
     * 当前操作者（口径同 {@link BackofficeContractController#currentOperator}）：
     * {@code X-User-Id}/{@code X-User-Name} 透传头经 RequestContext 读出落痕
     * （admin 侧管理员标识，签名面明示信任）；缺头/无上下文为 {@code null}——
     * 核验动作以操作者为审计正身，{@code null} 在聚合守卫被 ORD_043 拒。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
