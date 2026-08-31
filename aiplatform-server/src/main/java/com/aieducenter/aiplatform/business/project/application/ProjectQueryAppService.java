package com.aieducenter.aiplatform.business.project.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.order.application.OrderQueryAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFileContentResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFilesResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 项目读侧用例：详情、列表（状态过滤 ACTIVE/ARCHIVED/缺省 all）+ 用量（总量 +
 * 平台成本 + 分模型 + 分智能体）+ PRD 读（直读工作区文件事实源）。写侧（生命周期/
 * 归档/改名）归 {@link ProjectLifecycleAppService}，读拼装集中一处。
 */
@Service
public class ProjectQueryAppService {

    /**
     * PRD 在 dev 容器内的绝对路径（事实锚定——PRD = 工作区文件，编码智能体同视图
     * 直读，写入即进源码包；由布局常量表派生，根不散落字面量）。
     */
    private static final String PRD_CONTAINER_PATH = WorkspaceLayout.absolute(ProjectArtifacts.PRD);

    /**
     * 一次 exec 取齐 mtime + 正文：{@code stat -c %Y} 首行 epoch 秒、{@code cat}
     * 余文即 markdown 正文；文件不存在（test -f 失败）退出码恰 1。路径为常量，
     * 无用户可控片段。
     */
    private static final String READ_PRD_COMMAND = "test -f '" + PRD_CONTAINER_PATH
            + "' && stat -c %Y '" + PRD_CONTAINER_PATH + "' && cat '" + PRD_CONTAINER_PATH + "'";

    private final ProjectRepository projectRepository;
    private final UsageQueryPort usageQueryPort;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final OrderQueryAppService orderQueryAppService;

    public ProjectQueryAppService(ProjectRepository projectRepository,
                                  UsageQueryPort usageQueryPort,
                                  WorkspaceLifecycleAppService workspaceLifecycleAppService,
                                  OrderQueryAppService orderQueryAppService) {
        this.projectRepository = projectRepository;
        this.usageQueryPort = usageQueryPort;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.orderQueryAppService = orderQueryAppService;
    }

    /**
     * 项目详情。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public ProjectDetailResponse detail(Long projectId) {
        return toDetail(loadProject(projectId));
    }

    /**
     * 一批项目 → 项目名（跨 BC 查名面：order 上下文后台订单视图嵌入用）：缺档
     * 项目不在映射中，调用方容缺呈现（软引用无 FK，历史行缺档是合法状态）。
     */
    public Map<Long, String> namesOf(Collection<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        return projectRepository.findAllById(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Project::getName, (left, right) -> left));
    }

    /**
     * 项目列表（创建时间倒序）+ 状态过滤（Integer code，框架 converter 绑定）：
     * ACTIVE（未归档）/ ARCHIVED（已归档）；缺省 all。不合法 code 在端点层
     * 400 PRJ_014，本层只收合法枚举。
     */
    public List<ProjectResponse> list(ProjectStatusFilter status) {
        List<Project> projects = projectsNewestFirst();
        Map<Long, OrderBriefResponse> activeOrders =
                orderQueryAppService.activeOrdersOf(projects.stream().map(Project::getId).toList());
        return projects.stream()
                .filter(project -> matches(project, status))
                .map(project -> toResponse(project, activeOrders.get(project.getId())))
                .toList();
    }

    /**
     * 项目用量：经计量查询端口按 subject=projectId 聚合——总量 + 平台成本
     * （币种分桶 + 未配价标注）+ 分模型 + 分智能体（dims.agentKind 过滤，写侧
     * {@link UsageDims} 同键）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public ProjectUsageResponse usage(Long projectId) {
        loadProject(projectId);
        UsageSummary summary = usageQueryPort.bySubject(Long.toString(projectId), null, null);
        Map<String, BigDecimal> cost = summary.cost().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Currency::getCurrencyCode)))
                .collect(Collectors.toMap(entry -> entry.getKey().getCurrencyCode(),
                        Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
        List<ProjectUsageResponse.UnpricedUsage> unpriced = summary.unpriced().stream()
                .map(usage -> new ProjectUsageResponse.UnpricedUsage(usage.provider(),
                        usage.model(), usage.tokenKind(), usage.tokenKind().getName()))
                .toList();
        List<ProjectUsageResponse.ModelUsage> byModel = summary.byModel().stream()
                .map(model -> new ProjectUsageResponse.ModelUsage(
                        model.provider(), model.model(), model.tokens()))
                .toList();
        List<ProjectUsageResponse.AgentKindUsage> byAgentKind = summary.byDims().stream()
                .filter(dim -> UsageDims.KEY_AGENT_KIND.equals(dim.dimKey()))
                .map(dim -> new ProjectUsageResponse.AgentKindUsage(dim.dimValue(),
                        RolePreset.byName(dim.dimValue()).map(RolePreset::getName).orElse(null),
                        dim.tokens()))
                .toList();
        return new ProjectUsageResponse(Long.toString(projectId), summary.total(), cost,
                unpriced, byModel, byAgentKind);
    }

    /**
     * 项目 PRD 当前版：直读项目 dev 工作区的 {@code docs/PRD.md}（源码包下载同口径
     * ——容器常开，PRD 是工作区的一部分），返回 markdown 正文 + 文件 mtime（与正文
     * 同一事实源，秒精度；v1 无版本链只最新版）。「PRD 已产出」状态位另行落库，
     * 本端点不依赖它。
     *
     * <p>刻意不加 {@code @Transactional(readOnly = true)}（编写规范 §4.1 读操作
     * 缺省项）：docker exec 在方法体内，注解会把 exec 圈进事务占住连接（最长
     * 30s 超时）——Docker 副作用不进业务事务是本仓既有形制；projectId 装载只是
     * 单次 findById，自带短事务足够。</p>
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_015 PRD 未产出（工作区
     *                              无该文件，前端据此区分「还没产出」）；WSP_002
     *                              环境故障（docker exec 自身失败，退出码 125/126；
     *                              cat 权限错亦落 1 与未产出同口径，可接受取舍）
     */
    public PrdResponse prd(Long projectId) {
        Project project = loadProject(projectId);
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(READ_PRD_COMMAND));
        if (result.exitCode() == 1) {
            throw new ApplicationException(ProjectMessage.PRD_NOT_PRODUCED);
        }
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "PRD 读取失败: " + result.stderr());
        }
        int newline = result.stdout().indexOf('\n');
        try {
            long mtimeSeconds = Long.parseLong(result.stdout().substring(0, newline));
            return new PrdResponse(projectId.toString(),
                    result.stdout().substring(newline + 1), Instant.ofEpochSecond(mtimeSeconds));
        } catch (RuntimeException e) {
            // stat 首行（epoch 秒）缺失/畸形：stat 成功时不可达，防御性如实暴露
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "PRD 读取结果畸形: " + result.stdout());
        }
    }

    /**
     * 项目文件树（#27 文件模式）：一次 exec 列交付文件（find 源头剪枝非交付物，
     * 与源码包同口径），解析为按路径稳定排序的条目——随生成/修正后的工作区
     * 实时长出，无版本化。事务注解取舍同 {@link #prd}（exec 不进事务）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；WSP_002 环境故障
     */
    public ProjectFilesResponse files(Long projectId) {
        Project project = loadProject(projectId);
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(ProjectFiles.listCommand()));
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "文件树读取失败: " + result.stderr());
        }
        List<ProjectFilesResponse.FileEntry> entries = ProjectFiles.parseEntries(result.stdout()).stream()
                .map(entry -> new ProjectFilesResponse.FileEntry(entry.path(), entry.size()))
                .toList();
        return new ProjectFilesResponse(projectId.toString(), entries);
    }

    /**
     * 文本文件内容（#27 文件模式「点看」）：{@code path} 为工作区相对路径，先过
     * 可浏览判定（非交付物/逃逸/空白一律 400，不触工作区），再一次 exec 读取
     * （大小上限在容器侧 cat 前拦截）。退出码语义：1 = 不存在、2 = 超限、
     * 0 = 首行字节大小 + 余文正文；正文含 NUL 判非文本（无扩展名面，二进制可靠
     * 信号）。事务注解取舍同 {@link #prd}。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_020 路径不可浏览；
     *                              PRJ_021 文件不存在；PRJ_022 超大小上限；
     *                              PRJ_023 非文本；WSP_002 环境故障
     */
    public ProjectFileContentResponse fileContent(Long projectId, String path) {
        Project project = loadProject(projectId);
        if (!ProjectFiles.isViewable(path)) {
            throw new ApplicationException(ProjectMessage.FILE_PATH_INVALID);
        }
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(ProjectFiles.contentCommand(path)));
        if (result.exitCode() == 1) {
            throw new ApplicationException(ProjectMessage.FILE_NOT_FOUND);
        }
        if (result.exitCode() == 2) {
            throw new ApplicationException(ProjectMessage.FILE_TOO_LARGE);
        }
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "文件读取失败: " + result.stderr());
        }
        int newline = result.stdout().indexOf('\n');
        if (newline < 0) {
            // 大小首行缺失：printf 恒带换行，stat 成功时不可达，防御性如实暴露
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "文件读取结果畸形: " + result.stdout());
        }
        String content = result.stdout().substring(newline + 1);
        if (content.indexOf('\0') >= 0) {
            throw new ApplicationException(ProjectMessage.FILE_NOT_TEXTUAL);
        }
        return new ProjectFileContentResponse(path, content);
    }

    // ---------- 装载与过滤 ----------

    /** 全量项目，创建时间倒序。 */
    private List<Project> projectsNewestFirst() {
        return projectRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private boolean matches(Project project, ProjectStatusFilter filter) {
        if (filter == null) {
            return true;
        }
        if (filter == ProjectStatusFilter.ARCHIVED) {
            return project.getArchivedAt() != null;
        }
        // ACTIVE 是在办视角：归档项目不再出现（归档是单向终点）
        return project.getArchivedAt() == null;
    }

    // ---------- 响应拼装 ----------

    /** 详情拼装：列表字段全量 + PRD 产出时点（成果区长出判据）+ 首次生成时点
     * + 未终结订单摘要（锁定式矩阵推导输入）。 */
    private ProjectDetailResponse toDetail(Project project) {
        ProjectResponse base = toResponse(project,
                orderQueryAppService.activeOrderOf(project.getId()).orElse(null));
        return new ProjectDetailResponse(base.id(), base.name(), base.type(), base.typeName(),
                base.workspaceId(), base.status(), base.statusName(),
                base.archived(), base.createdAt(), base.updatedAt(), project.getPrdProducedAt(),
                project.getGeneratedAt(), base.activeOrder());
    }

    /** 列表项拼装：派生项目状态（归档 > 进行中）+ 未终结订单摘要。 */
    private ProjectResponse toResponse(Project project, OrderBriefResponse activeOrder) {
        boolean archived = project.getArchivedAt() != null;
        ProjectStatus status = archived ? ProjectStatus.ARCHIVED : ProjectStatus.IN_PROGRESS;
        return new ProjectResponse(
                project.getId().toString(),
                project.getName(),
                project.getType(),
                project.getType().getName(),
                project.getWorkspaceId().toString(),
                status,
                status.getName(),
                archived,
                project.getCreatedAt(),
                project.getUpdatedAt(),
                activeOrder);
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
