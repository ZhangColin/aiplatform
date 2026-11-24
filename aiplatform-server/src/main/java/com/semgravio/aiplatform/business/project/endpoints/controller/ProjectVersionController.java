package com.semgravio.aiplatform.business.project.endpoints.controller;

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

import com.semgravio.aiplatform.business.project.application.ProjectVersionAppService;
import com.semgravio.aiplatform.business.project.application.VersionSnapshotAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionRollbackStartResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionViewStartResponse;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.support.Tsid;

/**
 * 版本 REST 面（#91 版本层地基；#605 起收口成版提交退役；#626/#627 阶段四重建）：
 * 版本列表（git log 即正本，新→旧；成员判据＝平台版本标记/回滚标记）、版本详情
 * （元数据）、查看当时（起快照容器，归 #628 拆除）与回到某版（#627 平台侧异步：
 * restore→停→build→起→探活→成版）。版本是项目子资源，独立控制器分列（照
 * OrderController 先例——ProjectController 已满）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/versions")
@Validated
@Tag(name = "Versions", description = "版本：容器内 git log 的用户面时间线（#626 探活通过即自动成版，单轴新→旧）")
public class ProjectVersionController {

    private final ProjectVersionAppService versionAppService;
    private final VersionSnapshotAppService snapshotAppService;

    public ProjectVersionController(ProjectVersionAppService versionAppService,
            VersionSnapshotAppService snapshotAppService) {
        this.versionAppService = versionAppService;
        this.snapshotAppService = snapshotAppService;
    }

    @GetMapping
    @Operation(summary = "版本列表（单轴时间线，新→旧）",
            description = "git log 即版本序列（无便利表）：带 Platform-Version trailer 的用户面版本"
                    + "入列（#626 探活通过即自动成版，携版本号 vN 与 run 标识），回滚版带 "
                    + "Rollback-From trailer 亦入列；跑前快照无标记不入列。首条＝当前版"
                    + "（current=true），confirmed 标记已确认用于签约的版本。零版本 = 空列表非错误。"
                    + "项目不存在 404 PRJ_001；环境故障 WSP_002")
    public ApiResponse<List<VersionResponse>> list(@PathVariable String projectId) {
        return ApiResponse.ok(versionAppService.list(Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @GetMapping("/{ref}")
    @Operation(summary = "版本详情",
            description = "版本元数据（hash / 摘要 / 锚定 run / 成版时刻）；收尾卡载荷字段 closing "
                    + "随 Run-Id 联接退役（#605）恒空，留至阶段四版本重建。ref = commit hash（hex）——"
                    + "非 hash 形态 404 PRJ_028 且不触工作区（shell 注入防线）。项目不存在 404 PRJ_001")
    public ApiResponse<VersionDetailResponse> detail(@PathVariable String projectId,
            @PathVariable String ref) {
        return ApiResponse.ok(versionAppService.detail(Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), ref));
    }

    @PostMapping("/{ref}/view")
    @Operation(summary = "查看当时（起快照容器）",
            description = "起该版本（ref = commit hash）的运行态快照容器：同卷只读 + 数据副本 + "
                    + "检出当时代码起应用，返回 viewId 与快照预览 URL；逛完经 DELETE 关闭销毁。"
                    + "版本不存在 404 PRJ_028；同项目并发查看达上限 409 PRJ_029；环境故障 WSP_002")
    public ApiResponse<VersionViewStartResponse> startView(@PathVariable String projectId,
            @PathVariable String ref) {
        return ApiResponse.ok(snapshotAppService.startView(Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), ref));
    }

    @PostMapping("/{ref}/rollback")
    @Operation(summary = "回到某版（异步整回＋build＋重启＋探活）",
            description = "平台侧异步动作：校验版本存在、项目未归档、无 run 在途后受理，后台把"
                    + "系统代码整回该版本（ref = commit hash，只 restore tracked 文件——数据/依赖/.env"
                    + " 原样保留、活库不倒带）、停 8081 旧进程、重新 build（删旧 .next）、按平台起服"
                    + "路径重启、8081 探活；通过则记为新版本 vN+1（主题「回到 vN」）并发 preview-ready。"
                    + "进度复用工作消息起止呈现（标题「回到版本 vN」），失败经 run-failed 明确展示"
                    + "（不记版本）。只拦 run 在途——脏工作树允许被回滚覆盖。立即返回 runId 与版本标签。"
                    + "版本不存在 404 PRJ_028（含非 hash 形态 ref，不触工作区）；项目已归档 409 PRJ_013；"
                    + "在途 run 409 PRJ_031；环境故障 WSP_002")
    public ApiResponse<VersionRollbackStartResponse> rollback(@PathVariable String projectId,
            @PathVariable String ref) {
        return ApiResponse.ok(versionAppService.rollback(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), ref));
    }

    @DeleteMapping("/{ref}/view/{viewId}")
    @Operation(summary = "关闭查看会话（销毁快照容器）",
            description = "销毁 viewId 对应的快照容器（副本随容器可写层消失，工作区零变化）。"
                    + "ref 仅为 URL 对称占位（寻址锚是 viewId）；会话不存在 404 PRJ_030")
    public ApiResponse<Void> stopView(@PathVariable String projectId,
            @PathVariable String ref, @PathVariable String viewId) {
        snapshotAppService.stopView(Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), viewId);
        return ApiResponse.ok();
    }
}
