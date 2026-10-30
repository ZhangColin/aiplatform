package com.semgravio.aiplatform.business.order.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.business.order.application.ContractAppService;
import com.semgravio.aiplatform.business.order.application.dto.command.ContractActionCommand;
import com.semgravio.aiplatform.business.order.application.dto.command.RecordContractCommand;
import com.semgravio.aiplatform.business.order.application.dto.command.SignContractCommand;
import com.semgravio.aiplatform.business.order.application.dto.response.BackofficeContractDetailResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.BackofficeContractSummaryResponse;
import com.semgravio.aiplatform.business.order.application.dto.response.ContractResponse;
import com.semgravio.aiplatform.business.order.domain.enums.ContractStatus;
import com.semgravio.aiplatform.business.order.domain.error.OrderMessage;
import com.semgravio.aiplatform.business.order.domain.model.Operator;
import com.semgravio.aiplatform.support.Tsid;

/**
 * 后台合同 REST 面（#336 合同域六态承接，机机签名——五头 HMAC 强制闸，admin
 * 页面归跨仓 semgravio-admin#100）：清单/详情/重建创建＋六态人工推进（推进/
 * 失败或过期/撤回/作废）＋签署标记＋草稿重录——每次动作依据必填、操作人随行
 * append-only 留痕。错误码前缀 ORD_（合同不存在 ORD_020、状态守卫 ORD_021、
 * 依据 ORD_022/023、活跃唯一 ORD_024、前置门 ORD_025/026、签署守卫 ORD_027/
 * 028、重录窗口 ORD_031、内容超长 ORD_032）。
 */
@RestController
@RequestMapping("/api/backoffice/contracts")
@RequireSignature
@Tag(name = "Backoffice Contracts", description = "后台合同：清单 / 详情 / 重建创建 / 推进 / 失败或过期 / 撤回 / 作废 / 签署标记 / 草稿重录（机机签名）")
public class BackofficeContractController {

    private final ContractAppService appService;

    public BackofficeContractController(ContractAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "合同清单（多维检索，分页）",
            description = "运营工作清单：新合同在前（TSID 倒序）。维度可组合、均可缺省（缺省＝全量）："
                    + "① status 状态多选，Integer code 逗号分隔单值（如 status=1,3；1=准备中 "
                    + "2=待核对与签署 3=签署进行中 4=签署完成 5=签署失败或过期 6=已撤回或作废）；"
                    + "② createdFrom/createdTo 创建时间区间（ISO-8601，含两端）；③ orderId 所属"
                    + "订单精确（TSID 十进制，非数值→空清单 200）；④ contractNo 合同号精确"
                    + "（SG- 串）。行带编号/状态/版本/冻结金额。page 1 基（缺省 1）、size 缺省 20"
                    + "（上界 100），排序服务端定死不开放。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"BAD_REQUEST", "NOT_FOUND"})
    public ApiResponse<PageResponse<BackofficeContractSummaryResponse>> contracts(
            @RequestParam(required = false) List<ContractStatus> status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String contractNo,
            Pagination pagination) {
        return ApiResponse.ok(appService.contracts(status, createdFrom, createdTo,
                orderId, contractNo, pagination));
    }

    @GetMapping("/{id}")
    @Operation(summary = "合同详情（后台承接全量面）",
            description = "全字段（含「需更新」派生标志与范围快照冻结面）＋PRD 冻结全文冗余拷贝"
                    + "＋动作留痕时间线（新 → 旧，append-only 全量——动作/两侧状态/依据/操作者/时间）。"
                    + "需要机机签名；合同不存在 404 ORD_020")
    @ErrorCodes({"ORD_020"})
    public ApiResponse<BackofficeContractDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detailForBackoffice(
                Tsid.resolve(id, OrderMessage.ORDER_CONTRACT_NOT_FOUND)));
    }

    @PostMapping("/orders/{orderId}")
    @Operation(summary = "创建/重建合同（确认范围冻结集取现值）",
            description = "撤回/作废后的重建口（新号新合同、旧合同终态保留）——与用户「确认范围并"
                    + "准备合同」共用同一创建正本：冻结集取现值（最新价目行＋范围文本工作副本）。"
                    + "前置门同款：订单未报价 409 ORD_025；范围未录入 409 ORD_026；已有活跃合同"
                    + " 409 ORD_024（预检或并发撞部分唯一索引同译）。需要机机签名；订单不存在"
                    + " 404 ORD_001")
    @ErrorCodes({"ORD_001", "ORD_021", "ORD_024", "ORD_025", "ORD_026"})
    public ApiResponse<ContractResponse> createForOrder(@PathVariable String orderId) {
        return ApiResponse.ok(appService.confirmScope(
                Tsid.resolve(orderId, OrderMessage.ORDER_NOT_FOUND)));
    }

    @PostMapping("/{id}/advance")
    @Operation(summary = "推进（线性单步，依据必填留痕）",
            description = "准备中 → 待核对与签署 → 签署进行中 → 签署完成（末边须双方签署位均已"
                    + "落签）。每次推进 append-only 留痕（X-User-Id/X-User-Name 透传头自动落痕，"
                    + "缺头落空）。无推进边（终态）409 ORD_021；双方未均签 409 ORD_027；依据缺失"
                    + " 400 ORD_022；依据超长 400 ORD_023。需要机机签名")
    @ErrorCodes({"ORD_020", "ORD_021", "ORD_022", "ORD_023", "ORD_027", "ORD_035"})
    public ApiResponse<ContractResponse> advance(@PathVariable String id,
                                                 @RequestBody ContractActionCommand command) {
        return ApiResponse.ok(appService.advance(
                Tsid.resolve(id, OrderMessage.ORDER_CONTRACT_NOT_FOUND),
                command.basis(), currentOperator()));
    }

    @PostMapping("/{id}/fail")
    @Operation(summary = "失败或过期（旁落终态，依据必填留痕）",
            description = "自待核对与签署/签署进行中可达（用户放弃核对或超期未签的如实终局）；"
                    + "其余态 409 ORD_021。需要机机签名")
    @ErrorCodes({"ORD_020", "ORD_021", "ORD_022", "ORD_023"})
    public ApiResponse<ContractResponse> fail(@PathVariable String id,
                                              @RequestBody ContractActionCommand command) {
        return ApiResponse.ok(appService.markFailed(
                Tsid.resolve(id, OrderMessage.ORDER_CONTRACT_NOT_FOUND),
                command.basis(), currentOperator()));
    }

    @PostMapping("/{id}/withdraw")
    @Operation(summary = "撤回（旁落终态，依据必填留痕）",
            description = "自任何非终态可达——此后换新号重建（POST /orders/{orderId}）。终态再动"
                    + " 409 ORD_021。需要机机签名")
    @ErrorCodes({"ORD_020", "ORD_021", "ORD_022", "ORD_023"})
    public ApiResponse<ContractResponse> withdraw(@PathVariable String id,
                                                  @RequestBody ContractActionCommand command) {
        return ApiResponse.ok(appService.withdraw(
                Tsid.resolve(id, OrderMessage.ORDER_CONTRACT_NOT_FOUND),
                command.basis(), currentOperator()));
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "作废（旁落终态，依据必填留痕）",
            description = "守卫与去向同撤回（同一终态「已撤回或作废」，动作语义分立——区分只在"
                    + "留痕行 action）。需要机机签名")
    @ErrorCodes({"ORD_020", "ORD_021", "ORD_022", "ORD_023"})
    public ApiResponse<ContractResponse> voidContract(@PathVariable String id,
                                                      @RequestBody ContractActionCommand command) {
        return ApiResponse.ok(appService.voidContract(
                Tsid.resolve(id, OrderMessage.ORDER_CONTRACT_NOT_FOUND),
                command.basis(), currentOperator()));
    }

    @PostMapping("/{id}/sign")
    @Operation(summary = "签署标记（落一方签署位，依据必填留痕）",
            description = "电子签渠道真接前的后台承接口径：仅签署进行中态（409 ORD_021），落一方"
                    + "签署位＋时点；已落签的签署位幂等拒（ORD_028）。不自动迁移——「签署完成」"
                    + "由 advance 显式推进（双方均签守卫 ORD_027）。party：1=甲方（客户方）"
                    + " 2=乙方（服务方）。需要机机签名")
    @ErrorCodes({"ORD_020", "ORD_021", "ORD_022", "ORD_023", "ORD_028"})
    public ApiResponse<ContractResponse> sign(@PathVariable String id,
                                              @RequestBody SignContractCommand command) {
        return ApiResponse.ok(appService.sign(
                Tsid.resolve(id, OrderMessage.ORDER_CONTRACT_NOT_FOUND),
                command.party(), command.basis(), currentOperator()));
    }

    @PostMapping("/{id}/record")
    @Operation(summary = "草稿重录（同号重新冻结＋版本递增，依据必填留痕）",
            description = "「需更新」处置正路：改价/改范围后对草稿（准备中/待核对与签署）同号"
                    + "重录——预览字段集整组录入＋冻结集取订单现值重新定格＋合同版本 +1＋"
                    + "「需更新」清零。签署进行中起 409 ORD_031（变动走撤回/作废重建）；内容"
                    + "超长 400 ORD_032。需要机机签名")
    @ErrorCodes({"ORD_020", "ORD_022", "ORD_023", "ORD_025", "ORD_026", "ORD_031", "ORD_032"})
    public ApiResponse<ContractResponse> record(@PathVariable String id,
                                                @RequestBody RecordContractCommand command) {
        return ApiResponse.ok(appService.record(
                Tsid.resolve(id, OrderMessage.ORDER_CONTRACT_NOT_FOUND),
                command.partyA(), command.partyB(), command.projectScope(),
                command.paymentPlan(), currentOperator(), command.basis()));
    }

    /**
     * 当前操作者（口径同 {@link BackofficeOrderController#currentOperator}）：
     * {@code X-User-Id}/{@code X-User-Name} 透传头经 RequestContext 读出落痕
     * （admin 侧管理员标识，签名面明示信任）；缺头/无上下文为 {@code null}——
     * 合同动作以操作者为审计正身，{@code null} 在聚合守卫被 ORD_035 拒（与订单
     * 写口 #155 落空口径有意分立）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
