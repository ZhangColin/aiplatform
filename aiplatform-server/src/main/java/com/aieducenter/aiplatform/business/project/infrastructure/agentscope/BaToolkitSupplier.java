package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentToolkitSupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

import io.agentscope.core.tool.Toolkit;

/**
 * BA 访谈工具集装配（智能体资产归业务侧）：项目 dev 工作区注册 ask_user（每轮一问
 * 的挂起源）+ savePrd（PRD 落盘 + 业务登记）；本地兜底工作区无项目语境，空集
 * （模型不可见）。编码智能体的工具集随生成环另配，差异只在资产不在内核。
 */
@Component
public class BaToolkitSupplier implements AgentToolkitSupplier {

    private final PrdArtifactAdapter prdArtifacts;

    public BaToolkitSupplier(PrdArtifactAdapter prdArtifacts) {
        this.prdArtifacts = prdArtifacts;
    }

    @Override
    public Toolkit toolkitFor(AgentWorkspace workspace) {
        Toolkit toolkit = new Toolkit();
        if (workspace instanceof AgentWorkspace.ProjectDev dev) {
            toolkit.registerAgentTool(new AskUserTool());
            toolkit.registerAgentTool(new SavePrdTool(prdArtifacts.workspacePath(),
                    dev.workspaceId(), dev.containerName(), prdArtifacts));
        }
        return toolkit;
    }
}
