package com.aieducenter.aiplatform.business.order.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.business.order.application.BackofficeOrderAppService;
import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.command.SubmitQuoteCommand;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderDetailResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderSummaryResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;

/**
 * 后台订单 REST 面（#29 交易环②，机机签名）：cartisan-openapi 五头 HMAC
 * （X-Api-Key/X-Timestamp/X-Nonce/X-Body-Digest/X-Sign），类级
 * {@code @RequireSignature} 强制闸——无签名/错签 401；该前缀经 WebMvcConfig
 * 排除会话拦截（机机调用无用户会话）。前端无任何后台操作入口，联调走
 * scripts/backoffice-quote.sh。错误码前缀 ORD_（订单不存在 ORD_001、
 * 报价守卫 ORD_007/008/009）。
 */
@RestController
@RequestMapping("/api/backoffice/orders")
@RequireSignature
@Tag(name = "Backoffice Orders", description = "后台订单：清单 / 详情 / 源码包 / 报价（机机签名）")
public class BackofficeOrderController {

    private final BackofficeOrderAppService queryAppService;
    private final OrderAppService appService;

    public BackofficeOrderController(BackofficeOrderAppService queryAppService, OrderAppService appService) {
        this.queryAppService = queryAppService;
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "订单清单（按状态过滤，分页）",
            description = "报价工作清单：新单在前（TSID 倒序）。page 1 基（缺省 1）、size 缺省 20（上界 100）；"
                    + "status 可选（Integer code：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消），"
                    + "缺省拉全量。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"ORD_010"})
    public ApiResponse<PageResponse<BackofficeOrderSummaryResponse>> orders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryAppService.orders(status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "订单详情（后台面）",
            description = "报价依据全量：状态、金额+最新备注、PRD 快照正文（下单冻结）、项目名、"
                    + "下单用户昵称、状态时点组。需要机机签名；订单不存在 404 ORD_001")
    @ErrorCodes({"ORD_001"})
    public ApiResponse<BackofficeOrderDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.detail(OrderIds.parseOrder(id)));
    }

    @GetMapping("/{id}/source-package")
    @Operation(summary = "订单源码包（tar.gz 二进制流）",
            description = "交付取件：经项目工作区实时打包（排除 node_modules/.env/data/.platform），"
                    + "不占订单快照。需要机机签名；订单不存在 404 ORD_001；打包失败 500 WSP_002")
    @ErrorCodes({"ORD_001", "WSP_002"})
    public ResponseEntity<ByteArrayResource> sourcePackage(@PathVariable String id) {
        Long orderId = OrderIds.parseOrder(id);
        byte[] bytes = queryAppService.sourcePackage(orderId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/gzip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(orderId + "-source.tar.gz").build());
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(bytes));
    }

    @PostMapping("/{id}/quote")
    @Operation(summary = "提交报价（已报价态重复提交 = 改价）",
            description = "待报价态首次提交 = 报价（→已报价）；已报价态重复提交 = 改价（状态不变，"
                    + "append-only 价目行留痕、订单现值取最新行，改价历史用户面可见）。"
                    + "限未支付态：已支付/已终结 409 ORD_007；金额非正 400 ORD_008；"
                    + "备注超长 400 ORD_009。需要机机签名")
    @ErrorCodes({"ORD_001", "ORD_007", "ORD_008", "ORD_009"})
    public ApiResponse<OrderResponse> quote(@PathVariable String id,
                                            @RequestBody SubmitQuoteCommand command) {
        return ApiResponse.ok(appService.submitQuote(OrderIds.parseOrder(id),
                command.amount(), command.note()));
    }

    /**
     * status/page/size 绑定失败的兜底：非法 code/非数值在本层就是 400，映射回
     * ORD_010 保持错误码前缀口径（本 controller 唯一可绑定枚举参数是 status，
     * 兜底不越界——同 ProjectController PRJ_014 形制）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatusMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(OrderMessage.ORDER_STATUS_FILTER_UNKNOWN));
    }
}
