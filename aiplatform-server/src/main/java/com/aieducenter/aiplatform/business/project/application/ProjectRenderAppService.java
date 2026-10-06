package com.aieducenter.aiplatform.business.project.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.RenderedFileResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.DesignPrints;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.model.WorkspaceRenders;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 位图出口渲染入口（#284，ADR-0026/0027）：项目锚定的平台侧可复用面——出稿即渲
 * PNG（#292）、下载图位图化（#294）、导出衍生（#297）等触发点在此接线，各自带
 * 业务语义（事件、支付门、打包），本层只归一「容器里渲一张 PNG」的技术面。
 * 两条路：HTML→PNG 走 chromium 保真渲染（画幅随载荷）、SVG→PNG 走 resvg 零浏览器
 * 旁路；命令构造与守卫归 {@link WorkspaceRenders} 单点。退出码归一：1 = 源不在
 * （PRJ_021，与文件读侧同口径）、其余非 0 = 渲染失败（PRJ_039 技术失败）。无落库
 * ——事务注解取舍同读侧（工作区文件面不经数据库）。
 */
@Service
@Slf4j
public class ProjectRenderAppService {

    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public ProjectRenderAppService(ProjectRepository projectRepository,
                                   WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * HTML 设计稿 → PNG（chromium 保真渲染）：{@code source}/{@code target} 为工作区
     * 相对路径，画幅（像素）＝固定画幅帧（#278 语义，PRD 设计物清单的尺寸）。
     * 产物落工作区（存储正本口径），返回路径 + 字节大小引用。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_020 出入路径不可浏览；
     *                              PRJ_021 源文件不存在；PRJ_039 渲染失败；WSP_002 回执畸形
     */
    public RenderedFileResponse renderHtmlPng(Long projectId, String sourcePath,
            String targetPath, int width, int height) {
        requireViewablePaths(sourcePath, targetPath);
        return render(projectId,
                WorkspaceRenders.htmlToPngCommand(sourcePath, targetPath, width, height),
                sourcePath, targetPath);
    }

    /**
     * SVG 原生物件 → PNG 衍生（resvg 零浏览器旁路，#284）：原生尺寸渲染（多分辨率
     * 衍生是备案项）。路径与错误口径同 {@link #renderHtmlPng}。
     */
    public RenderedFileResponse renderSvgPng(Long projectId, String sourcePath, String targetPath) {
        requireViewablePaths(sourcePath, targetPath);
        return render(projectId,
                WorkspaceRenders.svgToPngCommand(sourcePath, targetPath),
                sourcePath, targetPath);
    }

    /**
     * 平面稿出稿即渲（#292 代码出图路——设计会话收口的接线点，ADR-0027 位图出口
     * 触发点）：对本场新落的设计 HTML 稿逐张判平面（首行画幅声明——{@link DesignPrints}
     * 规则单点；界面稿无声明零渲染，v1 零截图）并渲成同名 PNG（HTML 源与 PNG 双
     * 形态同存，stitch 同构）。单张失败 quietly——PNG 是可再生衍生，该稿回落 HTML
     * 形态如实、留日志，不反噬 run（对偶收口成版失败先例；出图技术失败的有限重试
     * 口径归图片模型路 {@code ImageGenerationAppService}，渲败不重试——确定性
     * 环节重试同果）。
     *
     * @param htmlDraftPaths 本场新落的设计 HTML 稿（工作区锚定形，如 {@code /design/x.html}）
     * @return 渲成映射（HTML 锚定路径 → PNG 锚定路径；界面稿/未渲/失败不在场）
     */
    public Map<String, String> renderPrintDrafts(Long projectId, List<String> htmlDraftPaths) {
        if (htmlDraftPaths.isEmpty()) {
            return Map.of();
        }
        Project project = loadProject(projectId);
        Map<String, String> rendered = new LinkedHashMap<>();
        for (String anchored : htmlDraftPaths) {
            String relative = anchored.startsWith("/") ? anchored.substring(1) : anchored;
            try {
                ExecResultResponse head = workspaceLifecycleAppService.exec(
                        Long.toString(project.getWorkspaceId()),
                        new WorkspaceExecCommand(DesignPrints.headFirstLineCommand(relative)));
                Optional<DesignPrints.PrintSize> size = DesignPrints.declaredSizeOf(head.stdout());
                if (size.isEmpty()) {
                    continue; // 界面稿（无画幅声明）——零渲染
                }
                String pngRelative = DesignPrints.pngPathOf(relative);
                renderHtmlPng(projectId, relative, pngRelative,
                        size.get().width(), size.get().height());
                rendered.put(anchored, "/" + pngRelative);
            }
            catch (RuntimeException e) {
                log.warn("[render] 项目 {} 平面稿 {} 渲 PNG 失败（稿回落 HTML 形态）：{}",
                        projectId, anchored, e.toString());
            }
        }
        return rendered;
    }

    /** 出入路径前置判定（PRJ_020 在任何容器交互之前，与文件读侧同口径）。 */
    private void requireViewablePaths(String sourcePath, String targetPath) {
        if (!ProjectFiles.isViewable(sourcePath) || !ProjectFiles.isViewable(targetPath)) {
            throw new ApplicationException(ProjectMessage.FILE_PATH_INVALID);
        }
    }

    /** 两路公共骨架：exec → 退出码归一（1 = 源不在、非 0 = 渲染失败）→ 字节回执解析。 */
    private RenderedFileResponse render(Long projectId, String command,
            String sourcePath, String targetPath) {
        Project project = loadProject(projectId);
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(command));
        if (result.exitCode() == 1) {
            throw new ApplicationException(ProjectMessage.FILE_NOT_FOUND);
        }
        if (result.exitCode() != 0) {
            throw new ApplicationException(ProjectMessage.RENDER_FAILED,
                    sourcePath + " 位图渲染失败: " + result.stderr());
        }
        long sizeBytes;
        try {
            sizeBytes = Long.parseLong(result.stdout().trim());
        } catch (NumberFormatException e) {
            // exit 0 而 stdout 非字节数回执：渲染器契约破坏，防御性如实暴露
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "渲染结果畸形: " + result.stdout());
        }
        return new RenderedFileResponse(targetPath, sizeBytes);
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
