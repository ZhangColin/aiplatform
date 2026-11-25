package com.semgravio.aiplatform.base.knowledge.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.base.knowledge.application.BackofficeKnowledgeAppService;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeVectorHealthResponse;

/**
 * 后台向量服务健康只读读面（#639，机机签名——五头 HMAC 强制闸，见
 * {@link com.semgravio.aiplatform.config.WebMvcConfig}）：探活向量小服务（服务
 * 已有 {@code /health} 路径、短超时），返回可用／不可用、检查时间、耗时——
 * 命中为空时后台可一眼区分「真没有」与「服务挂了」。探活失败只报不可用，不影响
 * 检索或入库。
 */
@RestController
@RequestMapping("/api/backoffice/knowledge-health")
@RequireSignature
@Tag(name = "Backoffice Knowledge Health", description = "后台向量服务健康读面：可用／不可用、检查时间、耗时（机机签名）")
public class BackofficeKnowledgeHealthController {

    private final BackofficeKnowledgeAppService appService;

    public BackofficeKnowledgeHealthController(BackofficeKnowledgeAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "向量服务健康探活（当次结果）",
            description = "探活本机向量小服务（#639）：走服务已有健康路径、短超时，"
                    + "返回 available 可用／不可用、checkedAt 检查时间、elapsedMs 耗时。"
                    + "只读；探活失败不影响检索或入库。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"BAD_REQUEST"})
    public ApiResponse<BackofficeVectorHealthResponse> health() {
        return ApiResponse.ok(appService.vectorHealth());
    }
}
