package com.aieducenter.aiplatform.business.project.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.aieducenter.aiplatform.base.process.domain.model.ExitGate;
import com.aieducenter.aiplatform.base.process.domain.model.StageEntry;
import com.aieducenter.aiplatform.base.process.domain.service.StageAdvanceService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Iteration;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.IterationStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMainChain;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.IterationRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 项目读侧用例（片5c，A3 §5 / A1 §2.5 / A6 §3）：详情（期位置 + 主链定义数据 +
 * 门就绪 + 派生状态）、列表（状态过滤 ACTIVE/PENDING/ARCHIVED/缺省 all，#34 收敛为
 * Integer code）、用量（总量 + 平台成本 + 分模型 + 分角色 + 按期）+ PRD 读（#41，
 * 直读工作区文件事实源）。写侧（生命周期/门操作/需求池）各自成服务，读拼装集中
 * 一处——门就绪的裁决（计数 ∧ 业务谓词）与列表 pending 派生（期门就绪 ∨ 工作区
 * 待处理等待点，A2 §63）同源，避免两处口径漂移。
 */
@Service
public class ProjectQueryAppService {

    /** 计量维度键（写侧单点定义在 {@link ProjectAgentTaskAppService}，读侧对齐引用）。 */
    private static final String DIM_ROLE = ProjectAgentTaskAppService.DIM_ROLE;

    /** 见上（期维度：A6 §3 run 发起时快照；期后修复 run 不带）。 */
    private static final String DIM_ITERATION = ProjectAgentTaskAppService.DIM_ITERATION;

    /**
     * PRD 在 dev 容器内的绝对路径（主链产物单一事实锚定；#41 grilling 定案——
     * PRD = 工作区文件，编码智能体同视图直读，写入即进源码包）。
     */
    private static final String PRD_CONTAINER_PATH =
            "/workspace/" + ProjectMainChain.PRD_ARTIFACT;

    /**
     * 一次 exec 取齐 mtime + 正文：{@code stat -c %Y} 首行 epoch 秒、{@code cat}
     * 余文即 markdown 正文；文件不存在（test -f 失败）退出码恰 1。路径为常量，
     * 无用户可控片段。
     */
    private static final String READ_PRD_COMMAND = "test -f '" + PRD_CONTAINER_PATH
            + "' && stat -c %Y '" + PRD_CONTAINER_PATH + "' && cat '" + PRD_CONTAINER_PATH + "'";

    private final ProjectRepository projectRepository;
    private final IterationRepository iterationRepository;
    private final StageAdvanceService stageAdvanceService;
    private final AgentWaitAppService agentWaitAppService;
    private final UsageQueryPort usageQueryPort;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public ProjectQueryAppService(ProjectRepository projectRepository,
                                  IterationRepository iterationRepository,
                                  StageAdvanceService stageAdvanceService,
                                  AgentWaitAppService agentWaitAppService,
                                  UsageQueryPort usageQueryPort,
                                  WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
        this.stageAdvanceService = stageAdvanceService;
        this.agentWaitAppService = agentWaitAppService;
        this.usageQueryPort = usageQueryPort;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 项目详情（A3 §5：期位置 + 主链定义数据 + 门就绪 + 派生状态——足够前端
     * 渲染进度条与点亮按钮）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public ProjectDetailResponse detail(Long projectId) {
        Project project = loadProject(projectId);
        Iteration iteration = Iteration
                .currentOf(iterationRepository.findByProjectId(projectId)).orElse(null);
        return toDetail(project, iteration, gateView(project, iteration));
    }

    /**
     * 项目列表（创建时间倒序）+ 状态过滤（Integer code，框架 converter 绑定）：
     * ACTIVE（未归档 ∧ 有 OPEN 期）、PENDING（未归档 ∧ 存在 dev 待办：期门就绪
     * ∨ 工作区待处理等待点）、ARCHIVED（已归档）；缺省 all。不合法 code 在端点
     * 层 400 PRJ_014（project 端点层类型不匹配兜底），本层只收合法枚举。
     */
    public List<ProjectResponse> list(ProjectStatusFilter status) {
        // pending 一次取全量待处理工作区（跨项目待办查询面，A2 §60），不在循环里逐项目查
        Set<Long> pendingWorkspaces = status == ProjectStatusFilter.PENDING
                ? agentWaitAppService.pendingWorkspaceIds()
                : Set.of();
        Map<Long, List<Iteration>> iterationsByProject = iterationsByProject();
        return projectsNewestFirst().stream()
                .filter(project -> matches(project, iterationsByProject.get(project.getId()),
                        status, pendingWorkspaces))
                .map(project -> toResponse(project, Iteration
                        .currentOf(iterationsByProject.get(project.getId())).orElse(null)))
                .toList();
    }

    /**
     * 项目用量（A1 §2.5 + A6 §3）：经计量查询端口按 subject=projectId 聚合——
     * 总量 + 平台成本（币种分桶 + 未配价标注）+ 分模型 + 分角色（dims.role 过滤）
     * + 按期（dims.iterationId 过滤；期后修复 run 无该维度，入总量不入期桶）。
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
        List<ProjectUsageResponse.RoleUsage> byRole = summary.byDims().stream()
                .filter(dim -> DIM_ROLE.equals(dim.dimKey()))
                .map(dim -> new ProjectUsageResponse.RoleUsage(dim.dimValue(),
                        RolePreset.byName(dim.dimValue()).map(RolePreset::getName).orElse(null),
                        dim.tokens()))
                .toList();
        List<ProjectUsageResponse.IterationUsage> byIteration = byIteration(summary);
        return new ProjectUsageResponse(Long.toString(projectId), summary.total(), cost,
                unpriced, byModel, byRole, byIteration);
    }

    /**
     * 项目 PRD 当前版（#41）：直读项目 dev 工作区的 {@code docs/PRD.md}（源码包
     * 下载同口径——容器常开，PRD 是工作区的一部分），返回 markdown 正文 + 文件
     * mtime（与正文同一事实源，秒精度；v1 无版本链只最新版）。「PRD 已产出」
     * 状态位（G1 门谓词输入，#49）另行落库，本端点不依赖它。
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

    /** 按期聚合映射：dims.iterationId 桶 + 期序号补全（「第 N 期」展示素材）+
     *  iterationId 数值升序（TSID 时序 ≈ 开期顺序；非数值异常值排末位）。 */
    private List<ProjectUsageResponse.IterationUsage> byIteration(UsageSummary summary) {
        List<UsageSummary.DimUsage> iterationDims = summary.byDims().stream()
                .filter(dim -> DIM_ITERATION.equals(dim.dimKey()))
                .toList();
        if (iterationDims.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> seqByIterationId = iterationRepository
                .findByProjectId(Long.valueOf(summary.subject())).stream()
                .collect(Collectors.toMap(iteration -> iteration.getId().toString(),
                        Iteration::getSeq));
        return iterationDims.stream()
                .map(dim -> new ProjectUsageResponse.IterationUsage(dim.dimValue(),
                        seqByIterationId.get(dim.dimValue()), dim.tokens()))
                .sorted(Comparator.comparingLong(
                        (ProjectUsageResponse.IterationUsage usage) -> iterationSortKey(
                                usage.iterationId()))
                        .thenComparing(ProjectUsageResponse.IterationUsage::iterationId))
                .toList();
    }

    /** 期序号排序键：dim 值来自透传，非数值（异常维度值）排末位不炸端点。 */
    private static long iterationSortKey(String iterationId) {
        try {
            return Long.parseLong(iterationId);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    // ---------- 全局读装载（列表共用前奏） ----------

    /** 期按项目分组（查询收口：每项目只取当前期候选——OPEN ∪ 无 OPEN 时 max-seq
     * 闭期，currentOf 选取语义与全量等价；列表/门就绪清单一次装载共用）。 */
    private Map<Long, List<Iteration>> iterationsByProject() {
        return iterationRepository.findCurrentPerProject().stream()
                .collect(Collectors.groupingBy(Iteration::getProjectId));
    }

    /** 全量项目，创建时间倒序。 */
    private List<Project> projectsNewestFirst() {
        return projectRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    // ---------- 门就绪（详情与列表 pending 派生共用的唯一口径） ----------

    /**
     * 当前阶段门就绪（A3 §5：计数门禁 ∧ 业务谓词）：无 OPEN 期 / 终态 / 无门段
     * 返回 null（无按钮可点亮）；G1（需求梳理段）另 ∧ 「PRD 已产出」（查项目
     * 状态位不查文件系统，#49——PRD 产出前门不 ready）。
     */
    private ProjectDetailResponse.GateView gateView(Project project, Iteration iteration) {
        if (iteration == null || iteration.getStatus() != IterationStatus.OPEN) {
            return null;
        }
        StageEntry stage = ProjectMainChain.definition().find(iteration.getStage()).orElse(null);
        if (stage == null || stage.terminal() || stage.exitGate() == null) {
            return null;
        }
        ExitGate gate = stage.exitGate();
        boolean ready = stageAdvanceService.gateOpen(ProjectMainChain.definition(),
                iteration.getStage(), iteration.getStageTaskCount());
        if (ready && ProjectMainChain.STAGE_BA.equals(iteration.getStage())) {
            ready = project.getPrdProducedAt() != null;
        }
        return new ProjectDetailResponse.GateView(gate.actor(), ready);
    }

    // ---------- 列表过滤 ----------

    private boolean matches(Project project, List<Iteration> iterations,
                            ProjectStatusFilter filter, Set<Long> pendingWorkspaces) {
        if (filter == null) {
            return true;
        }
        if (filter == ProjectStatusFilter.ARCHIVED) {
            return project.getArchivedAt() != null;
        }
        // ACTIVE/PENDING 都是「在办」视角：归档项目不再出现（归档是单向终点，A3 §4）
        if (project.getArchivedAt() != null) {
            return false;
        }
        if (filter == ProjectStatusFilter.ACTIVE) {
            return Iteration.currentOf(iterations)
                    .map(iteration -> iteration.getStatus() == IterationStatus.OPEN)
                    .orElse(false);
        }
        Iteration current = Iteration.currentOf(iterations).orElse(null);
        ProjectDetailResponse.GateView gate = gateView(project, current);
        return (gate != null && gate.ready())
                || pendingWorkspaces.contains(project.getWorkspaceId());
    }

    // ---------- 响应拼装 ----------

    /** 详情拼装：列表字段 + 主链定义数据 + 门就绪。 */
    private ProjectDetailResponse toDetail(Project project, Iteration iteration,
                                           ProjectDetailResponse.GateView gate) {
        ProjectResponse base = toResponse(project, iteration);
        List<ProjectDetailResponse.StageView> stages = ProjectMainChain.definition().stages()
                .stream()
                .map(stage -> new ProjectDetailResponse.StageView(stage.name(), stage.label(),
                        stage.defaultRole(),
                        stage.exitGate() != null ? stage.exitGate().actor() : null,
                        stage.terminal()))
                .toList();
        return new ProjectDetailResponse(base.id(), base.name(), base.type(), base.typeName(),
                base.engine(), base.workspaceId(), base.stage(), base.stageLabel(),
                base.status(), base.statusName(), base.stageTaskCount(), base.archived(),
                base.createdAt(), stages, gate);
    }

    /** 列表项拼装：期位置（stage/标签；收口后为 CLOSED）+ 派生项目状态（归档 >
     * 开发中/已交付，A3 §4 三态）+ 计数（收口后不展示——门禁输入，过程已结束）。 */
    private ProjectResponse toResponse(Project project, Iteration iteration) {
        boolean archived = project.getArchivedAt() != null;
        boolean open = !archived && iteration != null
                && iteration.getStatus() == IterationStatus.OPEN;
        String stage = iteration != null ? iteration.getStage() : null;
        String stageLabel = stage != null
                ? ProjectMainChain.definition().find(stage).map(StageEntry::label).orElse(null)
                : null;
        ProjectStatus status = archived ? ProjectStatus.ARCHIVED
                : open ? ProjectStatus.IN_PROGRESS : ProjectStatus.DELIVERED;
        return new ProjectResponse(
                project.getId().toString(),
                project.getName(),
                project.getType(),
                project.getType().getName(),
                project.getEngine(),
                project.getWorkspaceId().toString(),
                stage,
                stageLabel,
                status,
                status.getName(),
                open ? iteration.getStageTaskCount() : null,
                archived,
                project.getCreatedAt());
    }

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
