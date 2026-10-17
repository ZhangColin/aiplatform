package com.aieducenter.aiplatform.business.order.endpoints.controller;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.order.application.PaymentPlanAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.PaymentPlanResponse;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 订单付款 REST 面（#337 付款计划与凭证核验·用户腿）：付款计划查询（付款面板
 * 取数面——节点五态/附言码/「还需支付」推导）＋转账凭证上传（凭证核验路径
 * 第一跳，上传仅代表已提交）。挂订单子资源位、独立控制器分列（照
 * {@code ProjectMaterialController} 先例——{@link OrderController} 已满）。后台
 * 承接面（节点录入/开工门设定/到账核验/打回/看证）归
 * {@link BackofficePaymentController}。
 */
@RestController
@Validated
@Tag(name = "Order Payments", description = "订单付款：付款计划查询 / 转账凭证上传（凭证核验路径·用户腿）")
public class OrderPaymentController {

    private final PaymentPlanAppService appService;

    public OrderPaymentController(PaymentPlanAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/api/orders/{id}/payment-plan")
    @Operation(summary = "付款计划（订单名下活跃计划，付款面板取数面）",
            description = "挂订单下、随合同冻结同刻创建（#337）：节点清单（录入序）——每节点"
                    + "序号/名称/金额（绝对额〔分〕＋占比整数百分比展示换算）/五态"
                    + "（1=待支付 2=支付处理中 3=支付失败或关闭 4=线下转账待核验 5=已到账）"
                    + "/附言码（<合同号>-K<序>，转账回执关联位）/开工门标志/渠道类型＋流水号"
                    + "（可空）/累计已核到账与 remainingAmount（「还需支付金额与对应节点」的"
                    + "推导面）＋本人凭证清单（追加保留、上传仅代表已提交）。总额/累计已核到账"
                    + "在计划面。活跃计划＝订单名下最新行（撤回重建/范围重冻后即新行）。节点"
                    + "空表＝待后台录入。订单名下无付款计划（尚无合同）404 ORD_036（前端"
                    + "缺席＝不渲染面板）；订单不存在 404 ORD_001")
    @ErrorCodes({"ORD_001", "ORD_036"})
    public ApiResponse<PaymentPlanResponse> paymentPlan(@PathVariable String id) {
        return ApiResponse.ok(appService.planOfOrder(
                Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND)));
    }

    @PostMapping(value = "/api/orders/{id}/payment-nodes/{nodeId}/vouchers",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传转账凭证（凭证核验路径第一跳）",
            description = "线下转账后上传银行电子回单/水单（multipart 单文件，part 名 file）："
                    + "节点翻「线下转账待核验」，后台核验后转已到账或打回——上传仅代表"
                    + "已提交、不构成到账事实；多次上传追加保留不覆盖。待支付/待核验态可传"
                    + "（已到账 409 ORD_038）；凭证收 pdf/png/jpg/jpeg、单文件 ≤10MB"
                    + "（超限 400 ORD_040 如实报错不静默压缩）。响应＝更新后的付款计划"
                    + "（面板整卡刷新）。节点不存在或不属活跃计划 404 ORD_037（旧计划节点"
                    + "随旧合同留痕、不可动作）；已终结订单（已归档/已取消）409 ORD_038；"
                    + "订单不存在 404 ORD_001")
    @ErrorCodes({"ORD_001", "ORD_036", "ORD_037", "ORD_038", "ORD_040"})
    public ApiResponse<PaymentPlanResponse> uploadVoucher(@PathVariable String id,
            @PathVariable String nodeId, @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(appService.uploadVoucher(
                Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND),
                Tsid.resolve(nodeId, OrderMessage.PAYMENT_NODE_NOT_FOUND), file));
    }
}
