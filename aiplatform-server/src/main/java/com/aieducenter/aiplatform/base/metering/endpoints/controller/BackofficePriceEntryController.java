package com.aieducenter.aiplatform.base.metering.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
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
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.metering.application.BackofficePriceEntryAppService;
import com.aieducenter.aiplatform.base.metering.application.dto.command.OpenPriceEntryCommand;
import com.aieducenter.aiplatform.base.metering.application.dto.command.RepricePriceEntryCommand;
import com.aieducenter.aiplatform.base.metering.application.dto.response.UnitPriceEntryRepriceResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.UnitPriceEntryResponse;
import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;
import com.aieducenter.aiplatform.base.metering.domain.model.Operator;

/**
 * 后台单价表 REST 面（#160 成本运营＋#165 写口唯一化，机机签名）：单价表＝平台
 * 成本换算用单价数据（模型 × token 档位 × 币种 × 生效区间）。cartisan-openapi
 * 五头 HMAC，类级 {@code @RequireSignature} 强制闸；该前缀经 WebMvcConfig 排除
 * 会话拦截。错误码前缀 METER_（行不存在 METER_006、非当前行 METER_007、区间
 * 重叠 METER_008、过滤参数 METER_009、字段/币种 METER_004/010、关行时点
 * METER_005）。启动 Seeder 已随 #165 退役——单价表写路径全部收在本面（初始化
 * 经开行端点＋幂等签名脚本）。
 */
@RestController
@RequestMapping("/api/backoffice/price-entries")
@RequireSignature
@Tag(name = "Backoffice Price Entries", description = "后台单价表：行清单 / 开行 / 原子改价 / 停用（机机签名）")
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
                    + "无头请求——含种子脚本种入行——落 null）。page 1 基（缺省 1）、size 缺省 20（上界 100）。"
                    + "过滤参数绑定失败（非法分页值）400 METER_009。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"METER_009"})
    public ApiResponse<PageResponse<UnitPriceEntryResponse>> entries(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            Pagination pagination) {
        return ApiResponse.ok(appService.entries(provider, model, pagination));
    }

    @PostMapping
    @Operation(summary = "开行（空键首行——种子脚本通道）",
            description = "对指定匹配键（provider × model × tokenKind）新开一行敞口"
                    + "区间——写口唯一化到管理 API 后唯一的初始插入通道（#165：种子"
                    + "数据经幂等签名脚本走本端点种入，脚本侧幂等＝匹配键已有任意行"
                    + "即不再开行）。effectiveFrom 可回溯（种子口径 2026-01-01 敞口"
                    + "覆盖存量事件）、可指定未来时点（预发布），缺省即时。服务端补"
                    + "同键生效区间重叠校验（改价同款）。tokenKind 契约为 Integer"
                    + " code（1=input 2=output 3=cache_read 4=cache_write 5=reasoning）。"
                    + "X-User-Id/X-User-Name 透传头自动落痕新行（缺头落空——种子脚本"
                    + "即落空口径）。字段不完整/单价负数 400 METER_004；币种非 ISO 4217"
                    + " 400 METER_010；区间重叠（跨区间或同起点）409 METER_008。"
                    + "需要机机签名")
    @ErrorCodes({"METER_004", "METER_008", "METER_010"})
    public ApiResponse<UnitPriceEntryResponse> open(@RequestBody OpenPriceEntryCommand command) {
        return ApiResponse.ok(appService.open(command, currentOperator()));
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
     * 清单参数绑定失败的兜底：非数值分页值（{@link Pagination} record 构造绑定
     * 失败走 BindException 族，含 MethodArgumentNotValidException）在本层就是
     * 400，映射回 METER_009 保持错误码前缀口径（可绑定参数是 provider/model/
     * page/size，统一「无效的单价行过滤参数」——同 BackofficeOrderController
     * ORD_010 形制；provider/model 为 String，TypeMismatch 档现不可达，留作
     * 后续过滤维度加类型化标量时即复活，与兄弟 controller 同形制；本类三个
     * 写口命令体无 bean 校验注解，BindException 落点不会与 @Valid 校验信封
     * 抢道）。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleFilterMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(MeteringMessage.PRICE_ENTRY_FILTER_UNKNOWN));
    }
}
