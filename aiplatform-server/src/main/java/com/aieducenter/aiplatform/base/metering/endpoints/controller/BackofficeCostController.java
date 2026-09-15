package com.aieducenter.aiplatform.base.metering.endpoints.controller;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.metering.application.BackofficeCostAppService;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeCostOverviewResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeProjectCostDetailResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeProjectCostResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeUnpricedUsageResponse;
import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;

/**
 * 后台平台成本观测 REST 面（#161/#164 成本运营，机机签名）：全局总览 + unpriced
 * 全局警示 + 项目成本清单 + 单项目下钻。纯平台 token 成本观测——与报价脱钩
 * （无建议售价推导）、按币种分桶直读不折算；采集无订单维度（subject =
 * projectId），按订单成本不做（订单→成本经项目）。cartisan-openapi 五头 HMAC，
 * 类级 {@code @RequireSignature} 强制闸；该前缀经 WebMvcConfig 排除会话拦截。
 * 错误码前缀 METER_（查询参数 METER_011——#164 起可绑定参数含分页，消息泛化
 * 「无效的成本查询参数」，code 不变契约不动）。
 */
@RestController
@RequestMapping("/api/backoffice/costs")
@RequireSignature
@Tag(name = "Backoffice Costs", description = "后台平台成本观测：全局总览 / unpriced 全局警示 / 项目成本清单 / 单项目下钻（机机签名）")
public class BackofficeCostController {

    private final BackofficeCostAppService appService;

    public BackofficeCostController(BackofficeCostAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/overview")
    @Operation(summary = "平台成本全局总览（时间窗）",
            description = "全平台跨项目观测模型开销构成：总量 + 平台成本（token × 事件"
                    + "时点生效单价，币种分桶直读不折算、键 = ISO 4217 币种码）+ 分模型"
                    + "+ 分智能体。与报价脱钩——纯平台付出金额，无建议售价推导；改价"
                    + "不溯及（历史事件按当时价，成本不漂移）。无生效单价的分量不进"
                    + " cost（不伪装 0），未配价观测走 unpriced 端点。byAgentKind 取"
                    + "事件 dims.agentKind 原值（写侧终态口径 main/executor）+ "
                    + "agentKindName 中文名随行（#186：主链经智能体配置回解，naming/"
                    + "classify 等辅助标记为 null——消费端落「—」桶），无维度的事件"
                    + "不参与该分桶（总量/byModel 照含）。"
                    + "from/to 时间窗半开区间 [from, to)（ISO-8601 Instant，UTC 带 Z，"
                    + "如 2026-09-01T00:00:00Z），均可缺省（缺省＝该侧不限）；空窗/"
                    + "无数据返回全零 total 与空分桶，不是错误。查询参数绑定失败"
                    + "400 METER_011。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"METER_011"})
    public ApiResponse<BackofficeCostOverviewResponse> overview(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(appService.overview(from, to));
    }

    @GetMapping("/unpriced")
    @Operation(summary = "unpriced 全局警示（用量驱动）",
            description = "窗口内有 token 用量且事件时点无生效单价的 (provider, model, "
                    + "档位) 按档位汇总 token（只计无价分量——同档位部分有价部分无价"
                    + "时只计无价部分）。用量驱动：已配价档位与无用量档位不出现，静态"
                    + "配价缺口清单不做（无用量＝无实际损失）；据此发现漏配价并及时"
                    + "补价（补价只影响此后事件，历史成本不漂移）。from/to 时间窗半开"
                    + "区间 [from, to)（ISO-8601 Instant，UTC 带 Z），均可缺省（缺省＝"
                    + "该侧不限）；空窗/无未配价用量返回空 items，不是错误。查询参数"
                    + "绑定失败 400 METER_011。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"METER_011"})
    public ApiResponse<BackofficeUnpricedUsageResponse> unpriced(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(appService.unpriced(from, to));
    }

    @GetMapping("/projects")
    @Operation(summary = "项目成本清单（窗口聚合，成本降序分页）",
            description = "运营扫一眼谁费钱：窗口内有 token 用量的各项目成本汇总——"
                    + "总量 + 平台成本（token × 事件时点生效单价，币种分桶直读不"
                    + "折算、键 = ISO 4217 币种码）。排序服务端定死：成本降序（排序"
                    + "标量＝币种桶金额直加，单价表单币种时＝精确），全未配价项目"
                    + "（有用量但无任何已配价分量，成本标量缺失）排后且 allUnpriced="
                    + "true 标注，同序按 projectId 升序稳定。用量驱动：无用量项目不"
                    + "出现在清单（空窗＝空清单 200）；已删项目的历史花费照列（成本"
                    + "观测不抹历史，行 projectId 不解释存在性，项目名归 admin 侧按"
                    + "id 互查）。page 1 基（缺省 1）、size 缺省 20（上界 100）。"
                    + "from/to 时间窗半开区间 [from, to)（ISO-8601 Instant），均可"
                    + "缺省。查询参数（含分页）绑定失败 400 METER_011。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"METER_011"})
    public ApiResponse<PageResponse<BackofficeProjectCostResponse>> projectCosts(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pagination pagination) {
        return ApiResponse.ok(appService.projectCosts(from, to, pagination));
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "单项目成本下钻（byModel/byAgentKind 分解）",
            description = "单项目成本构成分解，复用 bySubject 聚合口径（与全局总览/"
                    + "项目清单同一换算规则）：总量 + 平台成本（币种分桶直读不折算）"
                    + "+ 未配价标注清单（窗口内有用量且时点无生效价的 provider/"
                    + "model/档位，与 cost 互补不重叠）+ 分模型 + 分智能体"
                    + "（dims.agentKind 原值 + agentKindName 中文名随行——#186，口径"
                    + "同全局总览；无维度事件不参与该分桶）。projectId = 计量 subject 原值（写侧口径 projectId "
                    + "十进制串，底座不解释存在性）：无用量/查无此号返回全零 total 与"
                    + "空结构（明确空态，非错误、不 404）。from/to 时间窗半开区间"
                    + " [from, to)（ISO-8601 Instant），均可缺省（缺省＝项目全量）。"
                    + "查询参数绑定失败 400 METER_011。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"METER_011"})
    public ApiResponse<BackofficeProjectCostDetailResponse> projectCostDetail(
            @PathVariable String projectId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(appService.projectCostDetail(projectId, from, to));
    }

    /**
     * 查询参数绑定失败的兜底：from/to 非 ISO-8601 Instant（标量参数，类型不匹配）
     * 与非数值分页值（{@link Pagination} record 构造绑定失败走 BindException 族，
     * 含 MethodArgumentNotValidException）在本层就是 400，映射回 METER_011 保持
     * 错误码前缀口径（同 BackofficeOrderController ORD_010 形制；#164 可绑定
     * 参数含分页、消息泛化「无效的成本查询参数」，code 不变契约不动——同 #159
     * PRJ_014 先例；本类四个读口无命令体、零 bean 校验注解，BindException 落点
     * 不会与 @Valid 校验信封抢道）。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleQueryMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(MeteringMessage.COST_WINDOW_INVALID));
    }
}
