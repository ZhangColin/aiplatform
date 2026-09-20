package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentToolkitSupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.BuildPlanFacts;
import com.aieducenter.aiplatform.business.project.application.FinishEditFacts;
import com.aieducenter.aiplatform.business.project.application.PrdRevisionFacts;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.port.ExternalContentFetcher;
import com.aieducenter.aiplatform.business.project.domain.port.WebSearchProvider;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

import io.agentscope.core.tool.Toolkit;

/**
 * 按智能体配置的工具集装配（智能体资产归业务侧；#86 角色预设收敛为配置——
 * 职能是配置不是结构）：{@link AgentProfile#MAIN 主智能体} = ask_user（每轮一问
 * 的挂起源）+ savePrd（PRD 落盘 + 业务登记 + 修订事实登记，#52——需求侧判定的
 * 观测面）+ saveBuildPlan（切片计划事实登记，ADR 0009——生成编排的切片输入）+
 * 只读五件（文件树 / 文件内容 / 项目事实 + 外部地址抓取 fetch_url + 联网搜索
 * web_search，答询查证与自主调研用），
 * 随只读工作区注册（#86 对话姿态：内核文件/shell 工具已关——写面结构性不存在，
 * PRD 写入走 savePrd 自带通道）；{@link AgentProfile#EXECUTOR run 执行体} = finish_edit
 * （更新收口结束工具——「要不要动系统」的判定面；其余编码工具——含内核 shell——
 * 由 harness 内核自带，#219 透明面化后破坏性命令直通不确认）；其余配置 / 本地兜底
 * 工作区 / 无配置语境 = 空集（模型不可见）。
 */
@Component
public class ProfileToolkitSupplier implements AgentToolkitSupplier {

    private final PrdArtifactAdapter prdArtifacts;
    private final FinishEditFacts finishFacts;
    private final PrdRevisionFacts prdRevisions;
    private final BuildPlanFacts buildPlanFacts;
    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final ExternalContentFetcher externalContentFetcher;
    private final WebSearchProvider webSearchProvider;

    public ProfileToolkitSupplier(PrdArtifactAdapter prdArtifacts, FinishEditFacts finishFacts,
            PrdRevisionFacts prdRevisions, BuildPlanFacts buildPlanFacts,
            ProjectRepository projectRepository, WorkspaceLifecycleAppService workspaceLifecycleAppService,
            ExternalContentFetcher externalContentFetcher, WebSearchProvider webSearchProvider) {
        this.prdArtifacts = prdArtifacts;
        this.finishFacts = finishFacts;
        this.prdRevisions = prdRevisions;
        this.buildPlanFacts = buildPlanFacts;
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.externalContentFetcher = externalContentFetcher;
        this.webSearchProvider = webSearchProvider;
    }

    @Override
    public Toolkit toolkitFor(String agentKey, AgentWorkspace workspace) {
        Toolkit toolkit = new Toolkit();
        if (AgentProfile.MAIN.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectReadOnly ro) {
            toolkit.registerAgentTool(new AskUserTool());
            toolkit.registerAgentTool(new SavePrdTool(prdArtifacts.workspacePath(),
                    ro.workspaceId(), ro.containerName(), prdArtifacts, prdRevisions));
            toolkit.registerAgentTool(new SaveBuildPlanTool(ro.workspaceId(), buildPlanFacts));
            toolkit.registerAgentTool(new ListWorkspaceFilesTool(ro.containerName()));
            toolkit.registerAgentTool(new ReadWorkspaceFileTool(ro.containerName()));
            toolkit.registerAgentTool(new ProjectFactsTool(ro.workspaceId(),
                    projectRepository, workspaceLifecycleAppService));
            // #213 抓取闭环：贴 URL 读外部资料（只读 GET + 四条安全底线在取数口兑现）
            toolkit.registerAgentTool(new FetchUrlTool(externalContentFetcher));
            // #215 调研闭环：自主搜索补缺口（供数方接口 + 平台配置实例化）
            toolkit.registerAgentTool(new WebSearchTool(webSearchProvider));
        }
        if (AgentProfile.EXECUTOR.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectDev dev) {
            toolkit.registerAgentTool(new FinishEditTool(dev.workspaceId(), finishFacts));
        }
        return toolkit;
    }
}
