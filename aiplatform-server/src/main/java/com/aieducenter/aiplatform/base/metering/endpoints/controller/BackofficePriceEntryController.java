package com.aieducenter.aiplatform.base.metering.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.metering.application.BackofficePriceEntryAppService;
import com.aieducenter.aiplatform.base.metering.application.dto.command.RepricePriceEntryCommand;
import com.aieducenter.aiplatform.base.metering.application.dto.response.UnitPriceEntryRepriceResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.UnitPriceEntryResponse;
import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;
import com.aieducenter.aiplatform.base.metering.domain.model.Operator;

/**
 * 后台单价表 REST 面（#160 成本运营，机机签名）：单价表＝平台成本换算用单价
 * 数据（模型 × token 档位 × 币种 × 生效区间）。cartisan-openapi 五头 HMAC，类级
 * {@code @RequireSignature} 强制闸；该前缀经 WebMvcConfig 排除会话拦截。错误码
 * 前缀 METER_（行不存在 METER_006、非当前行 METER_007、区间重叠 METER_008、
 * 过滤参数 METER_009、字段/币种 METER_004/010、关行时点 METER_005）。
 */
@RestController
@RequestMapping("/api/backoffice/price-entries")
@RequireSignature
@Tag(name = "Backoffice Price Entries", description = "后台单价表：行清单 / 原子改价 / 停用（机机签名）")
public class BackofficePriceEntryController {

    private final BackofficePriceEntryAppService appService;

    public BackofficePriceEntryController(BackofficePriceEntryAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "单价行清单（含历史行，分页）",
            description = "现行与历史行全量（价史全貌），排序服务端定死＝生效起点倒序"
                    + "（新段在前，同起点 id 倒序稳定）。provider/model 均为匹配键成分＝"
                    + "精确等值过滤、均可缺省（缺省＝全量行）；effectiveTo 为 null 即"
                    + "当前行。行带操作者两列（该行最近管理动作——开行或停用；存量行/"
                    + "种子行/无头落 null）。page 1 基（缺省 1）、size 缺省 20（上界 100）。"
                    + "过滤参数绑定失败（非法分页值）400 METER_009。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"METER_009"})
    public ApiResponse<PageResponse<UnitPriceEntryResponse>> entries(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(appService.entries(provider, model, page, size));
    }

    @PostMapping("/{id}/reprice")
    @Operation(summary = "原子改价（单调用关当前行＋开新行）",
            description = "同事务两步：被关行落 effectiveTo＝新起点（保留其原开行操作者，"
                    + "不被改写），新行沿用匹配键、单价/币种取命令、敞口生效。"
                    + "effectiveFrom 可指定（ISO-8601 Instant，如 2026-09-14T16:00:00Z）"
                    + "——含未来时点＝预发布（对齐供应商凌晨调价，窗口前旧价仍生效）；"
                    + "缺省＝即时。服务端补同键生效区间重叠校验（唯一约束只防同起点、"
                    + "不防跨区间重叠——重叠行会重复计费）。X-User-Id/X-User-Name 透传头"
                    + "自动落痕新行（缺头落空，#160）。中途任一守卫失败两行都不动。"
                    + "行不存在 404 METER_006；字段不完整/单价负数 400 METER_004；"
                    + "币种非 ISO 4217 400 METER_010；起点早于被关行起点 400 METER_005；"
                    + "目标非当前行 409 METER_007；区间重叠（跨区间或同起点）409 "
                    + "METER_008。需要机机签名")
    @ErrorCodes({"METER_004", "METER_005", "METER_006", "METER_007", "METER_008", "METER_010"})
    public ApiResponse<UnitPriceEntryRepriceResponse> reprice(
            @PathVariable String id, @RequestBody RepricePriceEntryCommand command) {
        return ApiResponse.ok(appService.reprice(parseEntry(id), command, currentOperator()));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "停用（即时生效，关行不接新行）",
            description = "关当前行（effectiveTo＝现在），不开新行——此后该匹配键用量进 "
                    + "unpriced（缺价不伪装 0、不阻断聚合）。对未生效的预发布行停用＝"
                    + "钳到自身起点成空区间（从未生效）。X-User-Id/X-User-Name 透传头"
                    + "自动落痕被关行（停用不接新行，被关行是唯一落点；缺头落空，#160）。"
                    + "行不存在 404 METER_006；目标非当前行 409 METER_007。"
                    + "需要机机签名")
    @ErrorCodes({"METER_006", "METER_007"})
    public ApiResponse<UnitPriceEntryResponse> deactivate(@PathVariable String id) {
        return ApiResponse.ok(appService.deactivate(parseEntry(id), currentOperator()));
    }

    /**
     * 当前操作者（#160）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（admin 侧管理员标识，签名面明示信任、不校验真实
     * 性）；缺头/无上下文为 {@code null}——落空口径，改价新行/停用被关行的操作者
     * 两列落 NULL。Id 两形转换在此一次完成（上下文 Long → 外域标识字符串）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }

    /**
     * 寻址解析：路径段（TSID 十进制字符串）→ Long。非数值/非正数即不存在的
     * 标识，语义上同 404（与 OrderIds/parseId 口径一致）。
     */
    private static Long parseEntry(String entryId) {
        try {
            long parsed = Long.parseLong(entryId);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
        }
        throw new ApplicationException(MeteringMessage.PRICE_ENTRY_NOT_FOUND);
    }

    /**
     * 清单参数绑定失败的兜底：非法分页值在本层就是 400，映射回 METER_009 保持
     * 错误码前缀口径（同 BackofficeOrderController ORD_010 形制——本 controller
     * 可绑定参数是 page/size）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleFilterMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(MeteringMessage.PRICE_ENTRY_FILTER_UNKNOWN));
    }
}
