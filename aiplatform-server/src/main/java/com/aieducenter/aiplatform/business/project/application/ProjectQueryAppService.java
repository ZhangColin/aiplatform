package com.aieducenter.aiplatform.business.project.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageQueryPort;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceContentPackage;
import com.aieducenter.aiplatform.business.order.application.OrderQueryAppService;
import com.aieducenter.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.BinaryExecResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.application.dto.response.DesignItemResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.GenerationSegmentResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFileContentResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFileDownloadResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFileRawResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFilesPackage;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFilesResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.DesignScopeResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignItem;
import com.aieducenter.aiplatform.business.project.domain.aggregate.GenerationSegment;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.DesignScopeType;
import com.aieducenter.aiplatform.business.project.domain.enums.GenerationState;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.DesignScope;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignItemRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.GenerationSegmentRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 项目读侧用例：详情、列表（状态过滤 ACTIVE/ARCHIVED/缺省 all）+ 用量（总量 +
 * 平台成本 + 分模型 + 分智能体）+ PRD 读（直读工作区文件事实源）。写侧（生命周期/
 * 归档/改名）归 {@link ProjectLifecycleAppService}，读拼装集中一处。详情含生成态
 * 四态投影（#222）——轨道表＋generated_at＋在途标记派生，与 SSE 会话态无关。
 */
@Service
public class ProjectQueryAppService {

    /**
     * PRD 在 dev 容器内的绝对路径（事实锚定——PRD = 工作区文件，run 执行体同视图
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
    private final GenerationSegmentRepository generationSegments;
    private final DesignItemRepository designItems;
    private final CodingRunTrack codingRunTrack;

    public ProjectQueryAppService(ProjectRepository projectRepository,
                                  UsageQueryPort usageQueryPort,
                                  WorkspaceLifecycleAppService workspaceLifecycleAppService,
                                  OrderQueryAppService orderQueryAppService,
                                  GenerationSegmentRepository generationSegments,
                                  DesignItemRepository designItems,
                                  CodingRunTrack codingRunTrack) {
        this.projectRepository = projectRepository;
        this.usageQueryPort = usageQueryPort;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.orderQueryAppService = orderQueryAppService;
        this.generationSegments = generationSegments;
        this.designItems = designItems;
        this.codingRunTrack = codingRunTrack;
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
     * 400（框架统一信封，带合法取值表），本层只收合法枚举。
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
     * 项目用量：经计量查询端口按 subject=projectId 聚合——总量 + 分模型 +
     * 分智能体（dims.agentKind 过滤，写侧 {@link UsageDims} 同键）。
     * 平台成本不进用户面（#167 收口：成本归运营口径，后台成本读面已就位）。
     *
     * @throws ApplicationException PRJ_001 项目不存在
     */
    public ProjectUsageResponse usage(Long projectId) {
        loadProject(projectId);
        UsageSummary summary = usageQueryPort.bySubject(Long.toString(projectId), null, null);
        List<ProjectUsageResponse.ModelUsage> byModel = summary.byModel().stream()
                .map(model -> new ProjectUsageResponse.ModelUsage(
                        model.provider(), model.model(), model.tokens()))
                .toList();
        List<ProjectUsageResponse.AgentKindUsage> byAgentKind = summary.byDims().stream()
                .filter(dim -> UsageDims.KEY_AGENT_KIND.equals(dim.dimKey()))
                .map(dim -> new ProjectUsageResponse.AgentKindUsage(dim.dimValue(),
                        AgentProfile.displayNameOf(dim.dimValue()), dim.tokens()))
                .toList();
        return new ProjectUsageResponse(Long.toString(projectId), summary.total(),
                byModel, byAgentKind);
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
     * 项目文件包（#174 后台下载）：已封存项目直取封存包（整卷口径——卷已删，
     * 包是唯一事实）；未封存项目即时导出源码包（交付口径，与订单源码包同一
     * packSource 内核——订单流程不动）。沙箱休眠中会先同步唤醒重建再打包
     * （分钟内）。事务注解取舍同 {@link #prd}（docker 副作用不进事务）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；WSP_016 封存包不存在或
     *                              不可读；WSP_002 环境故障
     */
    public ProjectFilesPackage filesPackage(Long projectId) {
        Project project = loadProject(projectId);
        WorkspaceContentPackage contentPackage = workspaceLifecycleAppService.contentPackageOf(
                Long.toString(project.getWorkspaceId()));
        return new ProjectFilesPackage(projectId.toString(), contentPackage.content(),
                contentPackage.fromSealArchive());
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
        requireFileReadSuccess(result.exitCode(), result.stderr(), "文件读取");
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

    /**
     * raw 直出文件字节（#283 点看图片，ADR-0027 点看判定对图片放行；#293 起扩
     * 设计稿 HTML 伺服——稿伺服通道）：{@code path} 为工作区相对路径，先过可浏览
     * 判定（非交付物/逃逸一律 400、工作区不被触达）、再过伺服判定（图片扩展名，
     * 或 design/ 锚定的设计稿 HTML——设计稿画布固定画幅帧取件；其余照旧走
     * {@link #fileContent}，含 NUL 的真二进制非图片件在那里仍如实拒收 PRJ_023），
     * 再一次 execBinary 读取（25 MiB 上限在容器侧 cat 前拦截，退出码语义与文本读
     * 同构：1 = 不存在、2 = 超限）。字节不经字符集解释，content-type 按扩展名。
     * 事务注解取舍同 {@link #prd}。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_020 路径不可浏览；
     *                              PRJ_038 非伺服面扩展名（非图片且非设计稿 HTML）；
     *                              PRJ_021 文件不存在；PRJ_022 超直出查看上限；
     *                              WSP_002 环境故障
     */
    public ProjectFileRawResponse fileRaw(Long projectId, String path) {
        Project project = loadProject(projectId);
        if (!ProjectFiles.isViewable(path)) {
            throw new ApplicationException(ProjectMessage.FILE_PATH_INVALID);
        }
        if (!ProjectFiles.isImagePath(path) && !ProjectFiles.isDraftHtml(path)) {
            throw new ApplicationException(ProjectMessage.FILE_NOT_IMAGE);
        }
        BinaryExecResponse result = workspaceLifecycleAppService.execBinary(
                Long.toString(project.getWorkspaceId()),
                new WorkspaceExecCommand(ProjectFiles.rawInlineCommand(path)));
        requireFileReadSuccess(result.exitCode(), result.stderr(), "文件直出读取");
        return new ProjectFileRawResponse(result.stdout(), ProjectFiles.contentTypeOf(path));
    }

    /**
     * 单文件下载字节（#287 通用下载，ADR-0027 支付门——体验免费、带走才付费）：
     * {@code path} 为工作区相对路径，守卫序＝项目 → 支付门（曾支付/已归档即放行，
     * 未支付 ORD_015 如实告知门语义）→ 可浏览判定（非交付物/机密/逃逸一律 400，
     * 工作区不被触达）→ execBinary 读取。<strong>不挑类型</strong>——文件区一切
     * 可浏览文件皆可带走（点看/预览照旧免费，门只盖下载面）；无大小上限（下载＝
     * 带走，与源码包整卷同通道同口径）。content-type 按扩展名（图片真实 MIME、
     * 其余 octet-stream），attachment 头归 REST 层。事务注解取舍同 {@link #prd}。
     *
     * @throws ApplicationException PRJ_001 项目不存在；ORD_015 未支付（门语义）；
     *                              PRJ_020 路径不可浏览；PRJ_021 文件不存在；
     *                              WSP_002 环境故障
     */
    public ProjectFileDownloadResponse fileDownload(Long projectId, String path) {
        Project project = loadProject(projectId);
        orderQueryAppService.requireDownloadable(projectId);
        if (!ProjectFiles.isViewable(path)) {
            throw new ApplicationException(ProjectMessage.FILE_PATH_INVALID);
        }
        BinaryExecResponse result = workspaceLifecycleAppService.execBinary(
                Long.toString(project.getWorkspaceId()),
                new WorkspaceExecCommand(ProjectFiles.downloadCommand(path)));
        requireFileReadSuccess(result.exitCode(), result.stderr(), "文件读取");
        return new ProjectFileDownloadResponse(result.stdout(), ProjectFiles.contentTypeOf(path));
    }

    /**
     * 文件读命令的退出码阶梯（#287 收口——#284 备案触发器「第四处同形」已燃：
     * content/raw/download 三面同码同形抽共享，渲染面 PRJ_039 语义不同不并）：
     * 1 = 不存在（PRJ_021）、2 = 超限（PRJ_022——无上限面〔下载〕不产生此码，
     * 阶梯保留以共形）、其余非 0 = 环境故障（WSP_002，动作语随调用面）。
     */
    private static void requireFileReadSuccess(int exitCode, String stderr, String action) {
        if (exitCode == 1) {
            throw new ApplicationException(ProjectMessage.FILE_NOT_FOUND);
        }
        if (exitCode == 2) {
            throw new ApplicationException(ProjectMessage.FILE_TOO_LARGE);
        }
        if (exitCode != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    action + "失败: " + stderr);
        }
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
     * + 生成态四态投影（#222）+ 未终结订单摘要（锁定式矩阵推导输入）+ 最近订单
     * 摘要（归档终态「完整记录」取单面，#30）+ 生成轨道片清单（#225 计划区）
     * + 设计轨道件清单（#290 计划区，对偶片清单）。 */
    private ProjectDetailResponse toDetail(Project project) {
        ProjectResponse base = toResponse(project,
                orderQueryAppService.activeOrderOf(project.getId()).orElse(null));
        GenerationState generationState = generationStateOf(project);
        return new ProjectDetailResponse(base.id(), base.name(), base.type(), base.typeName(),
                project.getEndpointType(), project.getEndpointType().getName(),
                designScopeOf(project),
                base.workspaceId(), base.status(), base.statusName(),
                base.archived(), base.createdAt(), base.updatedAt(), project.getPrdProducedAt(),
                project.getGeneratedAt(), generationState, generationState.getName(),
                base.activeOrder(),
                orderQueryAppService.latestOrderOf(project.getId()).orElse(null),
                segmentsOf(project),
                designItemsOf(project));
    }

    /** 设计范围响应拼装（#285）：无页面锚定（null 聚合读面）即 null。 */
    private DesignScopeResponse designScopeOf(Project project) {
        DesignScope scope = project.designScope();
        if (scope == null) {
            return null;
        }
        return new DesignScopeResponse(scope.type(), scope.type().getName(),
                scope.type() == DesignScopeType.SELECTED_PAGES ? scope.pages() : List.of());
    }

    /**
     * 生成轨道片清单读模型（#225 计划区只读透出）：轨道表当前片集按 ord 升序映射
     * ——现行计划（PRD 版本锚门 {@link GenerationSegment#planMatchesPrd} 单点）才
     * 透出；锚不一致（PRD 已演进、旧计划是过期结构）或无片行返回 null（过期计划
     * 的进度不是现行事实，不拿旧计划对进度）。
     */
    private List<GenerationSegmentResponse> segmentsOf(Project project) {
        List<GenerationSegment> segments =
                generationSegments.findByProjectIdOrderByOrdAsc(project.getId());
        if (!GenerationSegment.planMatchesPrd(segments, project.getPrdProducedAt())) {
            return null;
        }
        return segments.stream()
                .map(segment -> new GenerationSegmentResponse(segment.getOrd(),
                        segment.getDescription(), segment.getStatus(), segment.getStatus().getName()))
                .toList();
    }

    /**
     * 设计轨道件清单读模型（#290 计划区只读透出，对偶 {@link #segmentsOf}）：轨道表
     * 当前件集按 ord 升序映射——现行清单（PRD 版本锚门 {@link DesignItem#
     * checklistMatchesPrd} 单点）才透出；锚不一致（PRD 已演进、旧清单是过期结构）
     * 或无件行返回 null（过期清单的进度不是现行事实，不拿旧清单对进度）。
     */
    private List<DesignItemResponse> designItemsOf(Project project) {
        List<DesignItem> items = designItems.findByProjectIdOrderByOrdAsc(project.getId());
        if (!DesignItem.checklistMatchesPrd(items, project.getPrdProducedAt())) {
            return null;
        }
        return items.stream()
                .map(item -> new DesignItemResponse(item.getOrd(), item.getTitle(),
                        item.getStatus(), item.getStatus().getName(), item.getFinalizedPath()))
                .toList();
    }

    /**
     * 生成态四态投影（#222，ADR-0020 裁决四——派生序即优先序）：
     * <ol>
     * <li>已生成：{@code generated_at} 落位恒赢——迭代/修正 run 在途不改变生成态
     * （四态是生成面的呈现态，生成完成即定格）；</li>
     * <li>生成中：编码 run 在途（{@link CodingRunTrack} 进程内标记，含已提交未起跑
     * 的排队段）——重启丢标记即落到中断档，正是「中断不判死」的投影面；</li>
     * <li>生成中断：轨道表有片行而未生成不在途——失败终态、进程重启、PRD 演进致
     * 旧计划过期同档，「继续生成」出口挂本档；</li>
     * <li>从未生成：无片行不在途（含存量中断项目——轨道表之前的在途项目，恢复走
     * 计划重派，「继续生成」同一出口）。</li>
     * </ol>
     */
    private GenerationState generationStateOf(Project project) {
        if (project.getGeneratedAt() != null) {
            return GenerationState.GENERATED;
        }
        if (codingRunTrack.isInFlight(project.getId())) {
            return GenerationState.GENERATING;
        }
        return generationSegments.existsByProjectId(project.getId())
                ? GenerationState.INTERRUPTED
                : GenerationState.NEVER_GENERATED;
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
