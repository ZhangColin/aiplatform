package com.aieducenter.aiplatform.business.order.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import com.aieducenter.aiplatform.business.order.application.BackofficeOrderAppService;
import com.aieducenter.aiplatform.business.order.application.OrderAppService;
import com.aieducenter.aiplatform.business.order.application.dto.command.CancelOrderCommand;
import com.aieducenter.aiplatform.business.order.application.dto.command.SubmitQuoteCommand;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderDetailResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.BackofficeOrderSummaryResponse;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderResponse;
import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.order.domain.model.Operator;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 后台订单 REST 面（#29 交易环②，机机签名——五头 HMAC 强制闸，见
 * {@link com.aieducenter.aiplatform.config.WebMvcConfig}）。前端无任何后台操作
 * 入口，联调走 scripts/backoffice-quote.sh。错误码前缀 ORD_（订单不存在 ORD_001、
 * 报价守卫 ORD_007/008/009、运营取消 ORD_005/013/014、
 * 重试归档 ORD_012——项目已归档 PRJ_013 为跨 BC 既有码透传）。
 */
@RestController
@RequestMapping("/api/backoffice/orders")
@RequireSignature
@Tag(name = "Backoffice Orders", description = "后台订单：四维清单 / 详情 / 源码包 / 报价 / 运营取消 / 重试归档（机机签名）")
public class BackofficeOrderController {

    private final BackofficeOrderAppService queryAppService;
    private final OrderAppService appService;

    public BackofficeOrderController(BackofficeOrderAppService queryAppService, OrderAppService appService) {
        this.queryAppService = queryAppService;
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "订单清单（四维检索，分页）",
            description = "运营工作清单：新单在前（TSID 倒序）。四维可组合、均可缺省（缺省＝全量）："
                    + "① status 状态多选，Integer code 逗号分隔单值（如 status=1,5；1=待报价 "
                    + "2=已报价 3=已支付 4=已归档 5=已取消）——签名协议按 query 参数名去重，"
                    + "同名重复参数（status=1&status=2）只有末值入签，勿用；"
                    + "② createdFrom/createdTo 创建时间区间（ISO-8601，含两端，"
                    + "如 2026-09-01T00:00:00）；③ externalId 下单账号（对外正身，服务端换算，"
                    + "换算不到＝该用户无建档→空清单 200）；④ orderId 订单号精确（TSID 十进制，"
                    + "查无/非数值→空清单 200）。行带 ownerDisplayName（下单账号缺档为 null）。"
                    + "page 1 基（缺省 1）、size 缺省 20（上界 100），排序服务端定死不开放。"
                    + "过滤参数绑定失败走框架统一信封：非法状态 code 400（带合法取值表）、"
                    + "非数值分页 400（带字段明细）、时间类型不匹配 404。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"BAD_REQUEST", "NOT_FOUND"})
    public ApiResponse<PageResponse<BackofficeOrderSummaryResponse>> orders(
            @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(required = false) String externalId,
            @RequestParam(required = false) String orderId,
            Pagination pagination) {
        return ApiResponse.ok(queryAppService.orders(status, createdFrom, createdTo,
                externalId, orderId, pagination));
    }

    @GetMapping("/{id}")
    @Operation(summary = "订单详情（后台面）",
            description = "报价依据全量：状态、金额+最新备注、价目历史（append-only 全量，新→旧，"
                    + "每条带操作者——存量行操作者为空）、PRD 快照正文（下单冻结）、项目名、"
                    + "下单用户昵称、状态时点组。需要机机签名；订单不存在 404 ORD_001")
    @ErrorCodes({"ORD_001"})
    public ApiResponse<BackofficeOrderDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.detail(Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND)));
    }

    @GetMapping("/{id}/source-package")
    @Operation(summary = "订单源码包（tar.gz 二进制流）",
            description = "交付取件：经项目工作区实时打包（排除 node_modules/.env/data/.platform），"
                    + "不占订单快照。需要机机签名；订单不存在 404 ORD_001；打包失败 500 WSP_002")
    @ErrorCodes({"ORD_001", "WSP_002"})
    public ResponseEntity<ByteArrayResource> sourcePackage(@PathVariable String id) {
        Long orderId = Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND);
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
                    + "X-User-Id/X-User-Name 透传头自动落痕价目行（缺头落空，#155）。"
                    + "限未支付态：已支付/已终结 409 ORD_007；金额非正 400 ORD_008；"
                    + "备注超长 400 ORD_009。需要机机签名")
    @ErrorCodes({"ORD_001", "ORD_007", "ORD_008", "ORD_009"})
    public ApiResponse<OrderResponse> quote(@PathVariable String id,
                                            @RequestBody SubmitQuoteCommand command) {
        return ApiResponse.ok(appService.submitQuote(Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND),
                command.amount(), command.note(), currentOperator()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "运营取消订单（未支付态）",
            description = "语义与用户取消完全一致：订单落已取消、项目解冻回迭代、用户可继续对话与"
                    + "再次下单。取消原因必填——运营内部口径留档，不呈现任何用户面读面，"
                    + "后台订单详情可见。X-User-Id/X-User-Name 透传头自动落痕订单行"
                    + "（缺头落空，#157）。限未支付态：已支付/已归档/已取消 409 ORD_005"
                    + "（退款/售后另议）；原因缺失 400 ORD_013；原因超长（至多 1000 字）"
                    + "400 ORD_014。需要机机签名")
    @ErrorCodes({"ORD_001", "ORD_005", "ORD_013", "ORD_014"})
    public ApiResponse<OrderResponse> cancel(@PathVariable String id,
                                             @RequestBody CancelOrderCommand command) {
        return ApiResponse.ok(appService.cancelByBackoffice(Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND),
                command.reason(), currentOperator()));
    }

    @PostMapping("/{id}/retry-archive")
    @Operation(summary = "重试归档（已支付未归档的卡单补归档）",
            description = "对支付成功但归档失败的卡单手动补完结：一事务内订单落已归档"
                    + "＋项目归档，成功后补发「已归档」通知并触发知识沉淀（成交 PRD "
                    + "入知识库，best-effort 不炸主流程）。幂等由既有守卫保证——"
                    + "重复触发/非已支付态 409 ORD_012；项目已归档 409 PRJ_013"
                    + "（不产生重复素材）。X-User-Id/X-User-Name 透传头自动落痕订单行"
                    + "（缺头落空，#158；支付链自动归档操作者为空）。需要机机签名")
    @ErrorCodes({"ORD_001", "ORD_012", "PRJ_013"})
    public ApiResponse<OrderResponse> retryArchive(@PathVariable String id) {
        return ApiResponse.ok(appService.retryArchive(Tsid.resolve(id, OrderMessage.ORDER_NOT_FOUND), currentOperator()));
    }

    /**
     * 当前操作者（#155）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（admin 侧管理员标识，签名面明示信任、不校验真实
     * 性）；缺头/无上下文为 {@code null}——落空口径，价目行（#155）/订单取消
     * 行（#157）/订单归档行（#158）操作者两列落 NULL。
     * Id 两形转换在此一次完成（上下文 Long → 外域标识字符串，存储不混型）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
