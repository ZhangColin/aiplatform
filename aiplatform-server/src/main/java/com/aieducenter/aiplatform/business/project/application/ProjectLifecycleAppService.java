package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目生命周期用例：建项目 = 工作区副作用先行落定 → 一事务建 Project（占位名）
 * → SSE 通知 → 前缀段自动开 BA 访谈（「建项目即自动跑 BA」，初始描述即开场输入）
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
 * → SSE workspace-destroyed（编排层发射制：副作用真实落定后，ADR-0001）。归档
 * 与源码包下载归本服务（动作与交付物）；读拼装（详情/列表/用量）归
 * {@link ProjectQueryAppService}。</p>
 */
@Service
@Slf4j
public class ProjectLifecycleAppService {

    /** 智能体栈单栈常量（多引擎概念已出局；列随 Flyway squash（#18）处置）。 */
    private static final String ENGINE = AgentscopeAgentClient.ENGINE;

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final BaInterviewAppService baInterviewAppService;
    private final ProjectRepository projectRepository;
    private final ProjectQueryAppService queryAppService;
    private final PlatformNotificationAppService notificationAppService;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final ProjectNamingAppService namingService;
    private final TransactionTemplate transactionTemplate;

    public ProjectLifecycleAppService(WorkspaceLifecycleAppService workspaceLifecycleAppService,
                                      BaInterviewAppService baInterviewAppService,
                                      ProjectRepository projectRepository,
                                      ProjectQueryAppService queryAppService,
                                      PlatformNotificationAppService notificationAppService,
                                      ProjectKnowledgeAppService knowledgeAppService,
                                      ProjectNamingAppService namingService,
                                      TransactionTemplate transactionTemplate) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.baInterviewAppService = baInterviewAppService;
        this.projectRepository = projectRepository;
        this.queryAppService = queryAppService;
        this.notificationAppService = notificationAppService;
        this.knowledgeAppService = knowledgeAppService;
        this.namingService = namingService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 建项目（只传 requirement）：dev 工作区落定 → 一事务 Project（占位名 + 类型
     * 服务端缺省）→ SSE workspace-created → 异步 LLM 取名（不等结果，失败保占位）
     * → 自动开始 BA 访谈（经 {@link BaInterviewAppService}，欢迎语 + 首个澄清
     * 问题）。BA 起跑失败不回滚建项目（项目已成立，失败原因经 error 事件/日志表达）。
     */
    public ProjectCreatedResponse create(CreateProjectCommand command) {
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        Project project;
        try {
            project = transactionTemplate.execute(status -> projectRepository.save(Project.create(
                    Project.PLACEHOLDER_NAME, null, ENGINE,
                    Long.parseLong(workspace.workspaceId()), RequestContext.getUserId())));
        } catch (RuntimeException e) {
            // 落库失败：回收已落定的工作区，不留与记录脱节的容器/卷（照片1b 兜底）
            log.error("项目记录入库失败，回收工作区 {}", workspace.workspaceId(), e);
            destroyWorkspaceQuietly(workspace.workspaceId());
            throw e;
        }

        // SSE（副作用真实落定后发射，ADR-0001）
        notificationAppService.publish(ProjectEventTypes.WORKSPACE_CREATED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                ProjectEventTypes.PROJECT_NAME_FIELD, project.getName(),
                ProjectEventTypes.CONTAINER_FIELD, workspace.containerName(),
                ProjectEventTypes.PROJECT_TYPE_FIELD, project.getType().name(),
                ProjectEventTypes.ENGINE_FIELD, project.getEngine()));

        // 异步 LLM 取名（占位名先落，取名后台完成落位；空 requirement 不取名）
        namingService.nameAsync(project.getId(), command.requirement());

        // 前缀段自动：建项目即开始 BA 访谈（初始描述即开场输入）
        String prompt = command.requirement() == null || command.requirement().isBlank()
                ? RolePreset.DEFAULT_KICKOFF_PROMPT : command.requirement();
        BaInterviewAppService.InterviewRun run;
        try {
            run = baInterviewAppService.runInterviewTurn(project.getId(), prompt);
        } catch (RuntimeException e) {
            log.warn("项目 {} 自动 BA 起跑失败（项目已成立，不回滚）", project.getId(), e);
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
     * 删除项目（真删级联）：工作区物理销毁（容器/卷，尽力而为）→ prj_* 行
     * 删除（历史子表随 FK 级联）→ knw_chunks 级联清理（尽力而为）→
     * SSE workspace-destroyed。
     */
    public void delete(Long projectId) {
        Project project = requireProject(projectId);
        destroyWorkspaceQuietly(Long.toString(project.getWorkspaceId()));
        transactionTemplate.executeWithoutResult(status -> projectRepository.delete(project));
        knowledgeAppService.purgeByProject(projectId);
        notificationAppService.publish(ProjectEventTypes.WORKSPACE_DESTROYED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString()));
    }

    /**
     * 预览：工作区端口真实暴露（docker publish，可访问 URL）→ SSE
     * {@code preview-ready} → 返回 URL。产物可访问即预期效果，未起服务时 URL
     * 返回连接拒绝属真实状态。
     */
    public ProjectPreviewResponse preview(Long projectId) {
        Project project = requireProject(projectId);
        URI url = workspaceLifecycleAppService
                .exposePreview(Long.toString(project.getWorkspaceId()));
        notificationAppService.publish(ProjectEventTypes.PREVIEW_READY, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString(),
                ProjectEventTypes.URL_FIELD, url.toString()));
        return new ProjectPreviewResponse(url.toString());
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
