package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.application.IterationAppService;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

/**
 * BA 工具集装配：ask_user / savePrd / startFixRun 仅随项目 dev 工作区注册——
 * 本地兜底工作区无项目语境，空集（模型不可见）；savePrd 锚定项目（工作区 + 业务
 * 登记经 {@link PrdArtifactAdapter}），startFixRun 锚定项目（迭代编排经
 * {@link IterationAppService}，ObjectProvider 晚绑定解构造期环）。
 */
class BaToolkitSupplierTest {

    private final PrdArtifactAdapter prdArtifacts = mock(PrdArtifactAdapter.class);
    private final IterationAppService iterationAppService = mock(IterationAppService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<IterationAppService> iterationAppServices =
            mock(ObjectProvider.class);

    private BaToolkitSupplier supplier() {
        when(iterationAppServices.getObject()).thenReturn(iterationAppService);
        return new BaToolkitSupplier(prdArtifacts, iterationAppServices);
    }

    @Test
    void given_project_dev_workspace_when_toolkit_then_ba_tools_registered() {
        when(prdArtifacts.workspacePath()).thenReturn("docs/PRD.md");

        assertThat(supplier().toolkitFor(new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames())
                .containsExactlyInAnyOrder(AskUserTool.NAME, SavePrdTool.NAME,
                        StartFixRunTool.NAME);
    }

    @Test
    void given_local_workspace_when_toolkit_then_empty() {
        assertThat(supplier().toolkitFor(new AgentWorkspace.Local(null)).getToolNames()).isEmpty();
    }
}
