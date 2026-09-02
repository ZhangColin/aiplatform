package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentToolkitSupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

import io.agentscope.core.tool.Toolkit;

/**
 * 按角色的工具集装配（智能体资产归业务侧，#43 工具面收紧）：BA = ask_user（每轮
 * 一问的挂起源）+ savePrd（PRD 落盘 + 业务登记）——派发工具已撤（BA 无派发权，
 * 链的收口在平台代码）；CODER = 空集（编码工具由 harness 内核自带，业务工具一个
 * 不挂——ask_user/savePrd 挂在编码智能体上是泄漏）；其余角色 / 本地兜底工作区 /
 * 无角色语境 = 空集（模型不可见）。
 */
@Component
public class RoleToolkitSupplier implements AgentToolkitSupplier {

    private final PrdArtifactAdapter prdArtifacts;

    public RoleToolkitSupplier(PrdArtifactAdapter prdArtifacts) {
        this.prdArtifacts = prdArtifacts;
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
        return toolkit;
    }
}
