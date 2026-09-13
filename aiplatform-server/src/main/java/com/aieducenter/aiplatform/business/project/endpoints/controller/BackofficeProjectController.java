package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.business.project.application.BackofficeProjectAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeProjectSummaryResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 后台项目 REST 面（#159 项目域只读，机机签名）：cartisan-openapi 五头 HMAC
 * （X-Api-Key/X-Timestamp/X-Nonce/X-Body-Digest/X-Sign），类级
 * {@code @RequireSignature} 强制闸——无签名/错签 401；该前缀经 WebMvcConfig
 * 排除会话拦截（机机调用无用户会话）。清单＝状态三档单选＋创建时间区间＋账号
 * （externalId 入参服务端换算）＋项目 id 精确（用户报障贴链接场景），排序
 * id 倒序定死不开放；详情带订单引用（activeOrder/latestOrder 照用户面先例，
 * 与订单域互链）。归档项目全状态照读（清单缺省含），已删项目真删无墓碑——
 * 任何后台读面自然不可见。错误码前缀 PRJ_（项目不存在 PRJ_001、清单过滤参数
 * PRJ_014）。
 */
@RestController
@RequestMapping("/api/backoffice/projects")
@RequireSignature
@Tag(name = "Backoffice Projects", description = "后台项目：清单检索 / 详情带订单引用（机机签名，只读）")
public class BackofficeProjectController {

    private final BackofficeProjectAppService appService;

    public BackofficeProjectController(BackofficeProjectAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "项目清单（四维检索，分页）",
            description = "监管工作清单：新项目在前（TSID 倒序）。四维可组合、均可缺省（缺省＝全量）："
                    + "① status 状态三档单选，Integer code（1=进行中 3=已归档；缺省＝全部，"
                    + "归档项目缺省含——照用户面状态过滤先例，与订单清单状态多选有意不同）；"
                    + "② createdFrom/createdTo 创建时间区间（ISO-8601，含两端，"
                    + "如 2026-09-01T00:00:00）；③ externalId 归属账号（对外正身，"
                    + "服务端换算，换算不到＝该用户无建档→空清单 200）；④ projectId "
                    + "项目 id 精确（TSID 十进制，用户报障贴链接场景；查无/非数值→"
                    + "空清单 200）。行带 ownerDisplayName（归属账号缺档/无主为 null）。"
                    + "不做项目名模糊。page 1 基（缺省 1）、size 缺省 20（上界 100），"
                    + "排序服务端定死不开放。已删项目不可见（真删无墓碑）。"
                    + "过滤参数绑定失败（非法 code/时间/分页值）400 PRJ_014。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_014"})
    public ApiResponse<PageResponse<BackofficeProjectSummaryResponse>> projects(
            @RequestParam(required = false) ProjectStatusFilter status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(required = false) String externalId,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(appService.projects(status, createdFrom, createdTo,
                externalId, projectId, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情（后台面，带订单引用）",
            description = "清单字段全量＋归属账号显示名（缺档/无主为 null）＋订单引用（与订单域"
                    + "互链）：activeOrder＝未终结订单摘要（有值即冻结迭代，1=待报价 "
                    + "2=已报价），latestOrder＝最近一张任意状态订单（支付归档后 "
                    + "activeOrder 转空、本字段承接完整记录取单面；从未下单两者皆空）"
                    + "——照用户面先例。归档项目照读（工作区保留）；已删项目不可见"
                    + "（真删无墓碑）。需要机机签名；项目不存在 404 PRJ_001")
    @ErrorCodes({"PRJ_001"})
    public ApiResponse<BackofficeProjectDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(ProjectIds.parse(id)));
    }

    /**
     * 清单参数绑定失败的兜底：非法状态 code/时间/分页值在本层就是 400，映射回
     * PRJ_014 保持错误码前缀口径（#159 后本 controller 可绑定参数是 status/
     * createdFrom/createdTo/page/size，统一「无效的项目过滤参数」——同
     * BackofficeOrderController ORD_010 形制；用户面本就只绑 status，文案
     * 泛化对其无行为影响）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleFilterMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ProjectMessage.PROJECT_FILTER_UNKNOWN));
    }
}
