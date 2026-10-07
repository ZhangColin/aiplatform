package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.application.ConvergenceFace;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceConvergenceAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.skills.application.SkillDraftAppService;
import com.aieducenter.aiplatform.business.order.application.OrderQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.SwitchEndpointTypeCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.DesignScopeType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.DesignScope;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignItemRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignSpecRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.GenerationSegmentRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目生命周期用例：建项目 = 工作区副作用先行落定 → 一事务建 Project（占位名）
 * → SSE 通知 → 前缀段自动开主智能体对话（初始描述即开场输入）
 * + 异步 LLM 取名。
 *
 * <p>创建精简（spec 0002 §3.1 一句话创建）：入参只剩 requirement——类型单模板
 * 服务端缺省、项目名创建即落占位 {@link Project#PLACEHOLDER_NAME} 后由
 * {@link ProjectNamingAppService} 异步 LLM 取名落位（响应不等取名，前端
 * invalidate 自然见到新名）。</p>
 *
 * <p>事务形态（照片1b workspace 的形态）：Docker 副作用在业务事务外先行，库记录
 * 收进短事务；落库失败回收已落定的工作区不留孤儿容器。删除真删级联（A3 §4）：
 * 工作区物理销毁（尽力而为，失败不阻断记录删除）→ prj_* 行级联（FK CASCADE）
 * → 软引用表显式清（知识/对话史/生成段＋未终结技能草稿——#264 T6 终态与库行
 * 不随删）→ SSE workspace-destroyed（编排层发射制：副作用真实落定后，ADR-0001）。
 * 归档与源码包下载归本服务（动作与交付物）；读拼装（详情/列表/用量）归
 * {@link ProjectQueryAppService}。</p>
 */
@Service
@Slf4j
public class ProjectLifecycleAppService {

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final WorkspaceConvergenceAppService workspaceConvergenceAppService;
    private final MainAgentAppService mainAgentAppService;
    private final ProjectRepository projectRepository;
    private final ProjectQueryAppService queryAppService;
    private final EventsAppService eventsAppService;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final ProjectNamingAppService namingService;
    private final ConversationHistoryAppService conversationHistory;
    private final GenerationSegmentRepository generationSegments;
    private final DesignItemRepository designItems;
    private final DesignSpecRepository designSpecs;
    private final SkillDraftAppService skillDrafts;
    private final OrderQueryAppService orderQueryAppService;
    private final TransactionTemplate transactionTemplate;

    public ProjectLifecycleAppService(WorkspaceLifecycleAppService workspaceLifecycleAppService,
                                      WorkspaceConvergenceAppService workspaceConvergenceAppService,
                                      MainAgentAppService mainAgentAppService,
                                      ProjectRepository projectRepository,
                                      ProjectQueryAppService queryAppService,
                                      EventsAppService eventsAppService,
                                      ProjectKnowledgeAppService knowledgeAppService,
                                      ProjectNamingAppService namingService,
                                      ConversationHistoryAppService conversationHistory,
                                      GenerationSegmentRepository generationSegments,
                                      DesignItemRepository designItems,
                                      DesignSpecRepository designSpecs,
                                      SkillDraftAppService skillDrafts,
                                      OrderQueryAppService orderQueryAppService,
                                      TransactionTemplate transactionTemplate) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.workspaceConvergenceAppService = workspaceConvergenceAppService;
        this.mainAgentAppService = mainAgentAppService;
        this.projectRepository = projectRepository;
        this.queryAppService = queryAppService;
        this.eventsAppService = eventsAppService;
        this.knowledgeAppService = knowledgeAppService;
        this.namingService = namingService;
        this.conversationHistory = conversationHistory;
        this.generationSegments = generationSegments;
        this.designItems = designItems;
        this.designSpecs = designSpecs;
        this.skillDrafts = skillDrafts;
        this.orderQueryAppService = orderQueryAppService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 建项目（只传 requirement）：dev 工作区落定 → 一事务 Project（占位名 + 类型
     * 服务端缺省）→ SSE workspace-created → 异步 LLM 取名（不等结果，失败保占位）
     * → 自动开始主智能体对话（经 {@link MainAgentAppService}，欢迎语 + 首个澄清
     * 问题）。起跑失败不回滚建项目（项目已成立，失败原因经 error 事件/日志表达）。
     */
    public ProjectCreatedResponse create(CreateProjectCommand command) {
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        Project project;
        try {
            project = transactionTemplate.execute(status -> projectRepository.save(Project.create(
                    Project.PLACEHOLDER_NAME, null,
                    Long.parseLong(workspace.workspaceId()), RequestContext.getUserId())));
        } catch (RuntimeException e) {
            // 落库失败：回收已落定的工作区，不留与记录脱节的容器/卷（照片1b 兜底）
            log.error("项目记录入库失败，回收工作区 {}", workspace.workspaceId(), e);
            destroyWorkspaceQuietly(workspace.workspaceId());
            throw e;
        }

        // SSE（副作用真实落定后发射，ADR-0001；归属路由键取自项目聚合，ADR-0018）
        eventsAppService.publishNotification(ProjectEventTypes.WORKSPACE_CREATED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                ProjectEventTypes.PROJECT_NAME_FIELD, project.getName(),
                ProjectEventTypes.CONTAINER_FIELD, workspace.containerName(),
                ProjectEventTypes.PROJECT_TYPE_FIELD, project.getType().name(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId())));

        // 异步 LLM 取名（占位名先落，取名后台完成落位；空 requirement 不取名）
        namingService.nameAsync(project.getId(), command.requirement());

        // 前缀段自动：建项目即开始主智能体对话（初始描述即开场输入；会话建立轮做
        // 知识命中注入——query = 初始需求原文）
        String prompt = command.requirement() == null || command.requirement().isBlank()
                ? AgentProfile.DEFAULT_KICKOFF_PROMPT : command.requirement();
        MainAgentAppService.MainAgentRun run;
        try {
            run = mainAgentAppService.startConversation(project.getId(), prompt);
        } catch (RuntimeException e) {
            log.warn("项目 {} 自动对话起跑失败（项目已成立，不回滚）", project.getId(), e);
            return new ProjectCreatedResponse(queryAppService.detail(project.getId()), null);
        }
        return new ProjectCreatedResponse(queryAppService.detail(project.getId()),
                run.runId());
    }

    /**
     * 归档（单向终点）：落 archived_at，不清工作区。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 重复归档（409）
     */
    public ProjectDetailResponse archive(Long projectId) {
        Project project = requireProject(projectId);
        project.archive(); // 单向不变量在聚合（重复归档 DomainException PRJ_013）
        projectRepository.save(project);
        return queryAppService.detail(projectId);
    }

    /**
     * 改名（需求端右栏 inline 改名）：非生命周期动作——不设状态限制（归档项目
     * 照改），不发射 SSE（单账号场景，REST 响应即触达，前端 invalidate projects 域）。
     * 空白拒绝在聚合（PRJ_005 与建项目同口径）；长度上限归命令层（100）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_005 名空白（400，聚合抛出）
     */
    public ProjectDetailResponse rename(Long projectId, String name) {
        Project project = requireProject(projectId);
        project.rename(name); // 取名落位与用户改名共用同一行为
        projectRepository.save(project);
        return queryAppService.detail(projectId);
    }

    /**
     * 切换终点类型（#285，设置 tab 终点控件＝项目内唯一变更位，ADR-0024/0025）：
     * 下单前可变——归档关闭（PRJ_013）、未终结订单冻结（ORD_006，取消即解冻）；
     * 同目标幂等无操作（不派重产轮）。切换落库后派主智能体切换重产轮
     * （{@link MainAgentAppService#requestEndpointShift}——PRD 清单章随终点类型
     * 走形重产，访谈期即转向通告）；挂起问答守卫先于落库同步判定（拒绝即零副作用）。
     * 设计范围（系统→设计类切换受理时选）：仅系统＋设计落库（设计主线的范围由
     * PRD 设计物清单章承载）；重产指令携范围（勾选标签＝功能清单条目原文）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档；ORD_006 订单
     *                              处理中；PRJ_024 挂起问答待答；PRJ_040 设计范围
     *                              缺选；PRJ_041 勾选页面空集
     */
    public ProjectDetailResponse switchEndpoint(Long projectId, SwitchEndpointTypeCommand command) {
        Project project = requireProject(projectId);
        ProjectEndpointType previous = project.getEndpointType();
        ProjectEndpointType target = command.endpointType();
        if (target == previous) {
            return queryAppService.detail(projectId); // 同目标幂等：无操作不派轮（先于守卫——无变更即无冻结/挂起可言）
        }
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        orderQueryAppService.requireNoActiveOrder(projectId);
        mainAgentAppService.requireEndpointShiftable(projectId);

        DesignScope inputScope = resolveScope(command,
                /* required= */ previous == ProjectEndpointType.SYSTEM
                        && target.designInvolved() && project.getPrdProducedAt() != null);
        // 指令范围：输入优先；系统＋设计→设计（无输入）沿用已存范围锚定设计物
        DesignScope directiveScope = inputScope != null ? inputScope
                : (previous == ProjectEndpointType.SYSTEM_DESIGN ? project.designScope() : null);

        project.switchEndpoint(target, target == ProjectEndpointType.SYSTEM_DESIGN ? inputScope : null);
        projectRepository.save(project);

        String prompt = MainAgentAppService.endpointShiftPrompt(previous, target, directiveScope,
                project.getPrdProducedAt() != null);
        mainAgentAppService.requestEndpointShift(projectId, prompt);
        return queryAppService.detail(projectId);
    }

    /**
     * 设计范围命令解析：作用域缺选按需判 PRJ_040（系统→设计类且功能清单在——
     * 范围无从锚定才必填，访谈期切换无清单可勾不逼选）；勾选形空集 PRJ_041
     * （聚合抛出）；全部页面形无标签集。
     */
    private DesignScope resolveScope(SwitchEndpointTypeCommand command, boolean required) {
        if (command.scopeType() == null) {
            if (required) {
                throw new ApplicationException(ProjectMessage.ENDPOINT_SCOPE_REQUIRED);
            }
            return null;
        }
        if (command.scopeType() == DesignScopeType.SELECTED_PAGES) {
            return DesignScope.selected(command.scopePages());
        }
        return DesignScope.allPages();
    }

    /**
     * 源码包（交付物 = 源码包 + 仓内文档，端点常开）：打包项目 dev 工作区为
     * tar.gz 字节流（排除 .env 机密与 node_modules）；文件名/HTTP 头归 REST 层。
     *
     * @throws ApplicationException PRJ_001 项目不存在；工作区故障 WSP_（容器已亡等）
     */
    public byte[] sourcePackage(Long projectId) {
        Project project = requireProject(projectId);
        return workspaceLifecycleAppService.packSource(Long.toString(project.getWorkspaceId()));
    }

    /**
     * 用户面源码包下载（#287 支付门对齐——现状无门→有门，行为变更：ADR-0027
     * 打磨期自用零存量影响、上线前复核）：门判定与文案归
     * {@link OrderQueryAppService#requireDownloadable} 单点（曾支付/已归档即开放）。
     * 后台镜像端点（运营侧取件）走 {@link #sourcePackage} 内核不受门——票面口径
     * 「后台面不受用户支付门约束」。
     *
     * @throws ApplicationException PRJ_001 项目不存在；ORD_015 未支付（门语义）；
     *                              工作区故障 WSP_（容器已亡等）
     */
    public byte[] downloadableSourcePackage(Long projectId) {
        // 守卫序＝项目存在 → 门（与单文件下载面同序：寻址失败如实 404 PRJ_001，
        // 不被门语义 402 吞掉）；sourcePackage 内核自带 requireProject 幂等无害
        requireProject(projectId);
        orderQueryAppService.requireDownloadable(projectId);
        return sourcePackage(projectId);
    }

    /**
     * 删除项目（真删级联）：工作区物理销毁（容器/卷，尽力而为）→ prj_* 行
     * 删除（历史子表随 FK 级联）→ knw_chunks 与对话史级联清理（软引用显式清，
     * 尽力而为）→ 未终结技能草稿清理（#264 T6：只在途随删，终态留档、已晋升
     * 库行是平台资产不动）→ SSE workspace-destroyed。
     */
    public void delete(Long projectId) {
        Project project = requireProject(projectId);
        destroyWorkspaceQuietly(Long.toString(project.getWorkspaceId()));
        transactionTemplate.executeWithoutResult(status -> projectRepository.delete(project));
        knowledgeAppService.purgeByProject(projectId);
        conversationHistory.purgeByProject(projectId);
        generationSegments.deleteByProjectId(projectId);
        designItems.deleteByProjectId(projectId);
        designSpecs.deleteByProjectId(projectId);
        skillDrafts.purgeByProject(projectId);
        eventsAppService.publishNotification(ProjectEventTypes.WORKSPACE_DESTROYED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId())));
    }

    /**
     * 预览（#45 渐进口径；#105 URL 事件驱动；#170 唤醒待期）：探活工作区应用端口
     * ——通过（run 执行体已起服）→ SSE {@code preview-ready} → 返回 URL；置备/唤醒
     * 进行中 → 503 WSP_013（系统启动中，前端轮询续探）；已生成项目的应用未起服
     * （WSP_012，容器在而应用死——#168 残留场景）→ 触发平台拉起（8081 应用拉起自此
     * 是平台职责）后同样按 WSP_013 待期；未生成项目保持 WSP_012 原口径（未生成态，
     * 不拉起、无静态兜底）。
     */
    public ProjectPreviewResponse preview(Long projectId) {
        Project project = requireProject(projectId);
        URI url;
        try {
            url = workspaceLifecycleAppService
                    .exposePreview(Long.toString(project.getWorkspaceId()));
        } catch (ApplicationException e) {
            if (e.getCodeMessage() == WorkspaceMessage.PREVIEW_NOT_SERVING
                    && project.getGeneratedAt() != null) {
                // 已生成项目的应用死而复起：平台拉起（互斥异步）+ 待期口径
                workspaceConvergenceAppService.requestAppStart(
                        new WorkspaceId(project.getWorkspaceId()));
                throw new ApplicationException(WorkspaceMessage.WORKSPACE_STARTING);
            }
            throw e;
        }
        eventsAppService.publishNotification(ProjectEventTypes.PREVIEW_READY, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString(),
                ProjectEventTypes.URL_FIELD, url.toString(),
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId())));
        return new ProjectPreviewResponse(url.toString());
    }

    /**
     * 项目域触碰（#170 唤醒触发面；#196 起收敛判定归收敛模块 TOUCH 面）：拨工作区
     * last-touch + 异步探查沙箱实态——容器缺失/被杀则自动唤醒重建（已生成项目连带
     * 应用拉起）至预览可用。REST 拦截器对 {@code /api/projects/**} 每请求调用；尽力
     * 而为（失败不阻断业务请求，下次触碰再试），非项目域不触发。
     */
    public void touchProject(Long projectId) {
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null) {
                return;
            }
            workspaceConvergenceAppService.convergeAsync(
                    new WorkspaceId(project.getWorkspaceId()), ConvergenceFace.TOUCH,
                    project.getGeneratedAt() != null);
        } catch (RuntimeException e) {
            log.warn("项目 {} 触碰自愈未成（不阻断请求，下次触碰再试）", projectId, e);
        }
    }

    // ---------- 内部 ----------

    /** 工作区销毁（尽力而为）：失败记日志不阻断——真删级联优先，物理残留可重试销毁。 */
    private void destroyWorkspaceQuietly(String workspaceId) {
        try {
            workspaceLifecycleAppService.destroy(workspaceId);
        } catch (RuntimeException e) {
            log.warn("工作区 {} 物理销毁失败（记录照删，物理残留可重试销毁）：{}",
                    workspaceId, e.getMessage());
        }
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
