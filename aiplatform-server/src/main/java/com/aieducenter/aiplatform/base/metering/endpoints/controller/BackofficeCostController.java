package com.aieducenter.aiplatform.base.metering.endpoints.controller;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.base.metering.application.BackofficeCostAppService;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeCostOverviewResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeUnpricedUsageResponse;
import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;

/**
 * 后台平台成本观测 REST 面（#161 成本运营，机机签名）：全局总览 + unpriced 全局
 * 警示。纯平台 token 成本观测——与报价脱钩（无建议售价推导）、按币种分桶直读
 * 不折算；采集无订单维度（subject = projectId），按订单成本不做（下钻按项目，
 * 归项目域详情）。cartisan-openapi 五头 HMAC，类级 {@code @RequireSignature}
 * 强制闸；该前缀经 WebMvcConfig 排除会话拦截。错误码前缀 METER_（时间窗参数
 * METER_011）。
 */
@RestController
@RequestMapping("/api/backoffice/costs")
@RequireSignature
@Tag(name = "Backoffice Costs", description = "后台平台成本观测：全局总览 / unpriced 全局警示（机机签名）")
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
                    + "事件 dims.agentKind 原值（写侧终态口径 main/executor，展示名"
                    + "归 admin 侧映射），无维度的事件不参与该分桶（总量/byModel 照含）。"
                    + "from/to 时间窗半开区间 [from, to)（ISO-8601 Instant，UTC 带 Z，"
                    + "如 2026-09-01T00:00:00Z），均可缺省（缺省＝该侧不限）；空窗/"
                    + "无数据返回全零 total 与空分桶，不是错误。时间窗参数绑定失败"
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
                    + "该侧不限）；空窗/无未配价用量返回空 items，不是错误。时间窗参数"
                    + "绑定失败 400 METER_011。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"METER_011"})
    public ApiResponse<BackofficeUnpricedUsageResponse> unpriced(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(appService.unpriced(from, to));
    }

    /**
     * 时间窗参数绑定失败的兜底：from/to 非 ISO-8601 Instant 在本层就是 400，映射回
     * METER_011 保持错误码前缀口径（同 BackofficeOrderController ORD_010 形制——
     * 本 controller 可绑定参数是 from/to）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleWindowMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(MeteringMessage.COST_WINDOW_INVALID));
    }
}
