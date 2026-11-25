package com.semgravio.aiplatform.base.knowledge.endpoints.controller;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.knowledge.application.BackofficeKnowledgeAppService;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeKnowledgeRetrievalResponse;

/**
 * 后台命中留痕只读读面（#639，机机签名——五头 HMAC 强制闸，见
 * {@link com.semgravio.aiplatform.config.WebMvcConfig}）：每次主智能体开场检索
 * 一条记录——项目、时间、命中条目（类别／标题／来源项目展示名／片段）、是否
 * 降级。「检索成功但 0 条」与「向量服务不可用/检索异常」由此可区分。
 */
@RestController
@RequestMapping("/api/backoffice/knowledge-retrievals")
@RequireSignature
@Tag(name = "Backoffice Knowledge Retrievals", description = "后台命中留痕只读读面：项目／检索时间过滤＋分页（机机签名）")
public class BackofficeKnowledgeRetrievalController {

    private final BackofficeKnowledgeAppService appService;

    public BackofficeKnowledgeRetrievalController(BackofficeKnowledgeAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "命中留痕清单（按项目/时间过滤，分页）",
            description = "主智能体开场检索留痕（#639）：每次检索一条（项目、时间、"
                    + "命中条目、是否降级），检索成功但 0 条也记（hits 空、degraded=false）。"
                    + "两过滤维度可组合、均可缺省（缺省＝全量）：① projectId 项目 id "
                    + "精确（查无＝空清单 200）；② from/to 检索时间闭区间（闭区间含两"
                    + "端，ISO-8601 Instant，如 2026-09-01T00:00:00Z）。排序服务端定死"
                    + "＝检索时间倒序（id 倒序稳定）。行字段：项目／时间／命中条目（类别、"
                    + "标题、来源项目展示名、片段）／是否降级。page 1 基（缺省 1）、size "
                    + "缺省 20（上界 100）。只读，不写任何表。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"BAD_REQUEST", "NOT_FOUND"})
    public ApiResponse<PageResponse<BackofficeKnowledgeRetrievalResponse>> retrievals(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Pagination pagination) {
        return ApiResponse.ok(appService.retrievals(projectId, from, to, pagination));
    }
}
