package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.ProjectVersionAppService;
import com.aieducenter.aiplatform.business.project.application.VersionSnapshotAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionViewStartResponse;

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
    private final VersionSnapshotAppService snapshotAppService;

    public ProjectVersionController(ProjectVersionAppService versionAppService,
            VersionSnapshotAppService snapshotAppService) {
        this.versionAppService = versionAppService;
        this.snapshotAppService = snapshotAppService;
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

    @PostMapping("/{ref}/view")
    @Operation(summary = "查看当时（起快照容器）",
            description = "起该版本（ref = commit hash）的运行态快照容器：同卷只读 + 数据副本 + "
                    + "检出当时代码起应用，返回 viewId 与快照预览 URL；逛完经 DELETE 关闭销毁。"
                    + "版本不存在 404 PRJ_028；同项目并发查看达上限 409 PRJ_029；环境故障 WSP_002")
    public ApiResponse<VersionViewStartResponse> startView(@PathVariable String projectId,
            @PathVariable String ref) {
        return ApiResponse.ok(snapshotAppService.startView(ProjectIds.parse(projectId), ref));
    }

    @PostMapping("/{ref}/rollback")
    @Operation(summary = "回滚到此（追加新版本）",
            description = "把系统代码复位到该版本（ref = commit hash）、追加为新版本——历史只追加"
                    + "不改写（rebase/force 零使用）、只回代码不回数据；回滚后迭代照常（下一轮 run"
                    + "基于回滚后代码）。返回追加出的新版本（runId 空、rollbackFrom 锚定源版本）。"
                    + "版本不存在 404 PRJ_028（含非 hash 形态 ref，不触工作区）；环境故障 WSP_002")
    public ApiResponse<VersionResponse> rollback(@PathVariable String projectId,
            @PathVariable String ref) {
        return ApiResponse.ok(versionAppService.rollback(ProjectIds.parse(projectId), ref));
    }

    @DeleteMapping("/{ref}/view/{viewId}")
    @Operation(summary = "关闭查看会话（销毁快照容器）",
            description = "销毁 viewId 对应的快照容器（副本随容器可写层消失，工作区零变化）。"
                    + "ref 仅为 URL 对称占位（寻址锚是 viewId）；会话不存在 404 PRJ_030")
    public ApiResponse<Void> stopView(@PathVariable String projectId,
            @PathVariable String ref, @PathVariable String viewId) {
        snapshotAppService.stopView(ProjectIds.parse(projectId), viewId);
        return ApiResponse.ok();
    }
}
