package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentToolkitSupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.FinishFixFacts;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

import io.agentscope.core.tool.Toolkit;

/**
 * 按角色的工具集装配（智能体资产归业务侧，#43 工具面收紧 + #46 结束工具 +
 * #47 助理只读集）：BA = ask_user（每轮一问的挂起源）+ savePrd（PRD 落盘 +
 * 业务登记）——派发工具已撤（BA 无派发权，链的收口在平台代码）；CODER =
 * finish_fix（修正收口结束工具——「要不要动系统」的判定面，其余编码工具由
 * harness 内核自带）；ASSISTANT = 只读三件（文件树 / 文件内容 / 项目事实），
 * 随只读工作区注册（该形态内核文件/shell 工具已关——写面结构性不存在）；
 * 其余角色 / 本地兜底工作区 / 无角色语境 = 空集（模型不可见）。
 */
@Component
public class RoleToolkitSupplier implements AgentToolkitSupplier {

    private final PrdArtifactAdapter prdArtifacts;
    private final FinishFixFacts finishFacts;
    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public RoleToolkitSupplier(PrdArtifactAdapter prdArtifacts, FinishFixFacts finishFacts,
            ProjectRepository projectRepository,
            WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.prdArtifacts = prdArtifacts;
        this.finishFacts = finishFacts;
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    @Override
    public Toolkit toolkitFor(String agentRole, AgentWorkspace workspace) {
        Toolkit toolkit = new Toolkit();
        if (RolePreset.BA.name().equals(agentRole)
                && workspace instanceof AgentWorkspace.ProjectDev dev) {
            toolkit.registerAgentTool(new AskUserTool());
            toolkit.registerAgentTool(new SavePrdTool(prdArtifacts.workspacePath(),
                    dev.workspaceId(), dev.containerName(), prdArtifacts));
        }
        if (RolePreset.CODER.name().equals(agentRole)
                && workspace instanceof AgentWorkspace.ProjectDev dev) {
            toolkit.registerAgentTool(new FinishFixTool(dev.workspaceId(), finishFacts));
        }
        if (RolePreset.ASSISTANT.name().equals(agentRole)
                && workspace instanceof AgentWorkspace.ProjectReadOnly ro) {
            toolkit.registerAgentTool(new ListWorkspaceFilesTool(ro.containerName()));
            toolkit.registerAgentTool(new ReadWorkspaceFileTool(ro.containerName()));
            toolkit.registerAgentTool(new ProjectFactsTool(ro.workspaceId(),
                    projectRepository, workspaceLifecycleAppService));
        }
        return toolkit;
    }
}
