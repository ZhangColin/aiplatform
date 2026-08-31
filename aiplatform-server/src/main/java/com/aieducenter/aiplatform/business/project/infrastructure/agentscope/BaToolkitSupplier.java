package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentToolkitSupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.application.IterationAppService;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

import io.agentscope.core.tool.Toolkit;

/**
 * BA 访谈工具集装配（智能体资产归业务侧）：项目 dev 工作区注册 ask_user（每轮一问
 * 的挂起源）+ savePrd（PRD 落盘 + 业务登记）+ startFixRun（意见判定的派发出口，
 * #26 迭代环）；本地兜底工作区无项目语境，空集（模型不可见）。编码智能体的
 * 工具集随生成环另配，差异只在资产不在内核。
 *
 * <p>迭代编排经 {@link ObjectProvider} 晚绑定：智能体工厂 → 工具装配 → 迭代编排
 * → 引擎客户端 → 智能体工厂 是构造期环（startFixRun 派修正 run 复用引擎客户端），
 * 工具装配发生在 run 时（上下文已就绪），取用时解析即解环。</p>
 */
@Component
public class BaToolkitSupplier implements AgentToolkitSupplier {

    private final PrdArtifactAdapter prdArtifacts;
    private final ObjectProvider<IterationAppService> iterationAppServices;

    public BaToolkitSupplier(PrdArtifactAdapter prdArtifacts,
            ObjectProvider<IterationAppService> iterationAppServices) {
        this.prdArtifacts = prdArtifacts;
        this.iterationAppServices = iterationAppServices;
    }

    @Override
    public Toolkit toolkitFor(AgentWorkspace workspace) {
        Toolkit toolkit = new Toolkit();
        if (workspace instanceof AgentWorkspace.ProjectDev dev) {
            toolkit.registerAgentTool(new AskUserTool());
            toolkit.registerAgentTool(new SavePrdTool(prdArtifacts.workspacePath(),
                    dev.workspaceId(), dev.containerName(), prdArtifacts));
            toolkit.registerAgentTool(new StartFixRunTool(dev.workspaceId(),
                    iterationAppServices.getObject()));
        }
        return toolkit;
    }
}
