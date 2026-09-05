package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.ProjectVersionAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionResponse;

/**
 * 版本 REST 面（#91 版本层地基）：每轮 run 收口自动成版的只读面——版本列表
 * （git log 即正本，新→旧）与版本详情（元数据 + 锚定的收尾卡载荷）。版本是
 * 项目子资源，独立控制器分列（照 OrderController 先例——ProjectController 已满）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/versions")
@Validated
@Tag(name = "Versions", description = "版本：每轮 run 收口自动成版（容器内 git）的列表与详情（锚定收尾卡）")
public class ProjectVersionController {

    private final ProjectVersionAppService versionAppService;

    public ProjectVersionController(ProjectVersionAppService versionAppService) {
        this.versionAppService = versionAppService;
    }

    @GetMapping
    @Operation(summary = "版本列表（新→旧）",
            description = "git log 即版本序列（无便利表）：每轮编码 run 收口自动成版（commit 主题 = "
                    + "收口摘要、Run-Id trailer 锚定收尾卡）。零版本（尚无收口）= 空列表非错误。"
                    + "项目不存在 404 PRJ_001；环境故障 WSP_002")
    public ApiResponse<List<VersionResponse>> list(@PathVariable String projectId) {
        return ApiResponse.ok(versionAppService.list(ProjectIds.parse(projectId)));
    }

    @GetMapping("/{ref}")
    @Operation(summary = "版本详情（锚定收尾卡）",
            description = "版本元数据（hash / 摘要 / 锚定 run / 成版时刻）+ 收尾卡载荷（Run-Id 联接对话史 "
                    + "closing 条目；收尾卡缺位时 closing 为 null）。ref = commit hash（hex）——"
                    + "非 hash 形态 404 PRJ_028 且不触工作区（shell 注入防线）。项目不存在 404 PRJ_001")
    public ApiResponse<VersionDetailResponse> detail(@PathVariable String projectId,
            @PathVariable String ref) {
        return ApiResponse.ok(versionAppService.detail(ProjectIds.parse(projectId), ref));
    }
}
