package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.BinaryExecResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignItem;
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignSpec;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.DesignItemStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.DesignPackages;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignItemRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignSpecRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 设计资产包（#297，ADR-0023/0027——设计面交付物的下单冻结与取件）：设计单
 * 交付物＝PRD 快照＋设计资产包（选件式 tar：选定稿自包含形态＋设计规范文件＋
 * 帧衍生资产，落选稿不入——规范文件出自一致性桥正本 {@code prj_design_specs}，
 * 硬依赖 #295）；系统＋设计单＝同一冻结件内统一部件容器（源码整树＋选定设计
 * 部件，类型可区分）；系统单零冻结（源码包实时取口径不变）。交易机制零改版：
 * 报价/改价/状态机/取消不感知本服务，取消残留不清理（后台取件正本）。
 *
 * <p>冻结序＝暂存→落库→落名（编排归 {@code OrderAppService#place}）：暂存打包
 * 在订单落库<b>前</b>——打包失败＝下单失败零残留（重试零障碍，不产生「有单无包」
 * 的半成态）；落名（mv 原子改名）在落库后、以落库分配的订单 id 定格该单冻结件。
 * 取件（{@link #frozenPackage}）读冻结件字节——支付门判定归订单侧
 * {@code OrderQueryAppService#requireDownloadable} 单点（曾支付/已归档即开放），
 * 门语义不进本服务。</p>
 */
@Service
@Slf4j
public class DesignPackageAppService {

    private final ProjectRepository projectRepository;
    private final DesignItemRepository designItems;
    private final DesignSpecRepository designSpecs;
    private final DesignSpecAppService designSpecAppService;
    private final ProjectRenderAppService renderAppService;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public DesignPackageAppService(ProjectRepository projectRepository,
            DesignItemRepository designItems,
            DesignSpecRepository designSpecs,
            DesignSpecAppService designSpecAppService,
            ProjectRenderAppService renderAppService,
            WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.projectRepository = projectRepository;
        this.designItems = designItems;
        this.designSpecs = designSpecs;
        this.designSpecAppService = designSpecAppService;
        this.renderAppService = renderAppService;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /** 暂存冻结件（订单落库前的打包产物锚）：工作区标识 + 暂存路径两锚随行。 */
    public record StagedPackage(String workspaceId, String stagingPath) {
    }

    /**
     * 下单冻结·暂存（{@code OrderAppService#place} 落库前接线，仅设计面单）：
     * 规范文件落盘（正本直陈，无规范项目零写入——包内无 DESIGN.md 如实）→
     * 界面类定稿稿渲帧衍生（quietly——衍生可再生，失败回落 HTML 形态不反噬下单，
     * 对偶 {@code ProjectRenderAppService#renderPrintDrafts} 口径）→ 目录清单 →
     * 选件打包到项目名暂存件。容器形态按项目终点类型分岔（设计＝纯选件；
     * 系统＋设计＝find 剪枝整树选件＋设计部件显式追加的统一容器）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；WSP_002 打包/写入失败
     *                              （环境故障——下单失败零残留，重试零障碍）
     */
    public StagedPackage stageForOrder(Long projectId) {
        Project project = requireProject(projectId);
        String workspaceId = Long.toString(project.getWorkspaceId());
        List<String> finalizedPaths = finalizedPathsOf(projectId);
        boolean withSystemParts = project.getEndpointType() == ProjectEndpointType.SYSTEM_DESIGN;

        LinkedHashSet<String> members = new LinkedHashSet<>();
        DesignSpec spec = designSpecs.findByProjectId(projectId).orElse(null);
        if (spec != null) {
            designSpecAppService.materializeRulesFile(workspaceId, spec);
            // 系统＋设计：DESIGN.md 随源码整树面入包（已落工作区根）；设计单＝显式成员
            if (!withSystemParts) {
                members.add(WorkspaceLayout.DESIGN_MD);
            }
        }
        ExecResultResponse listing = exec(workspaceId, DesignPackages.listingCommand());
        if (listing.exitCode() != 0) {
            // 命令体自带 find 级容错（|| true），非 0＝docker 层故障——如实炸，
            // 不静默空清单（空清单会冻出空包）
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "设计线目录清单读取失败: " + listing.stderr());
        }
        List<String> listed = DesignPackages.listedRelativeOf(listing.stdout());
        members.addAll(DesignPackages.selectionOf(finalizedPaths, listed));
        members.addAll(renderFrameDerivatives(projectId, finalizedPaths, listed));

        String stagingPath = DesignPackages.stagingPathOf(projectId);
        ExecResultResponse packed = exec(workspaceId, DesignPackages.tarCommand(stagingPath,
                List.copyOf(members), withSystemParts));
        if (packed.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "设计资产包打包失败: " + packed.stderr());
        }
        return new StagedPackage(workspaceId, stagingPath);
    }

    /**
     * 下单冻结·落名（{@code OrderAppService#place} 落库后接线）：暂存件原子改名
     * 定格为该单冻结件（{@code exports/design-package-{orderId}.tar.gz}）。落库后
     * 失败（同目录改名，仅容器中途消亡可达）如实上抛——订单已立、暂存残留由下次
     * 同项目下单覆盖，取消/重下不清理（残留备案口径）。
     */
    public void sealForOrder(StagedPackage staged, Long orderId) {
        ExecResultResponse sealed = exec(staged.workspaceId(), DesignPackages.sealCommand(
                staged.stagingPath(), DesignPackages.packagePathOf(orderId)));
        if (sealed.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "设计资产包落名失败: " + sealed.stderr());
        }
    }

    /**
     * 该单冻结件取件（#297 用户面下载/后台取件的共用内核，字节止调用方）：
     * 读 {@code exports/design-package-{orderId}.tar.gz} 原始字节。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_051 冻结件不存在
     *                              （不以空产物顶替）；WSP_002 读取失败（环境故障）
     */
    public byte[] frozenPackage(Long projectId, Long orderId) {
        Project project = requireProject(projectId);
        BinaryExecResponse bytes = workspaceLifecycleAppService.execBinary(
                Long.toString(project.getWorkspaceId()),
                new WorkspaceExecCommand(ProjectFiles.downloadCommand(
                        DesignPackages.packagePathOf(orderId))));
        if (bytes.exitCode() == 1) {
            throw new ApplicationException(ProjectMessage.DESIGN_PACKAGE_NOT_FOUND);
        }
        if (bytes.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "设计资产包读取失败: " + bytes.stderr());
        }
        return bytes.stdout();
    }

    /** 定稿锚清单（件序）：已定稿且有定稿稿锚的件行（未定稿/无锚不入——如实）。 */
    private List<String> finalizedPathsOf(Long projectId) {
        return designItems.findByProjectIdOrderByOrdAsc(projectId).stream()
                .filter(item -> item.getStatus() == DesignItemStatus.FINALIZED
                        && item.getFinalizedPath() != null)
                .map(DesignItem::getFinalizedPath)
                .toList();
    }

    /**
     * 界面类定稿稿的帧衍生（#297 包内「衍生资产」面的确定性来源）：对 design/
     * 锚定 HTML 定稿逐张渲 1280×800 帧图（#294 下载图同律同画幅）落 exports/。
     * 已在场的衍生跳过（确定性渲染同果，不重复花渲染）；单张失败 quietly——
     * 衍生可再生、该稿回落 HTML 形态入包，不反噬下单。
     */
    private List<String> renderFrameDerivatives(Long projectId, List<String> finalizedPaths,
            List<String> listed) {
        List<String> rendered = new ArrayList<>();
        for (String anchored : finalizedPaths) {
            String relative = ProjectFiles.relativeFormOf(anchored);
            if (relative == null || !ProjectFiles.isDraftHtml(relative)) {
                continue; // 平面类位图正身即稿，无帧衍生面
            }
            String target = DesignPackages.framePngOf(DesignPackages.stemOf(relative));
            if (listed.contains(target)) {
                continue;
            }
            try {
                renderAppService.renderHtmlPng(projectId, relative, target,
                        DesignPackages.FRAME_PNG_WIDTH, DesignPackages.FRAME_PNG_HEIGHT);
                rendered.add(target);
            }
            catch (RuntimeException e) {
                log.warn("[design-package] 项目 {} 定稿稿 {} 帧衍生渲染失败（稿回落 HTML 形态入包）：{}",
                        projectId, anchored, e.toString());
            }
        }
        return rendered;
    }

    private ExecResultResponse exec(String workspaceId, String command) {
        return workspaceLifecycleAppService.exec(workspaceId, new WorkspaceExecCommand(command));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
