package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

/**
 * BA 工具集装配：ask_user / savePrd 仅随项目 dev 工作区注册——本地兜底工作区无
 * 项目语境，空集（模型不可见）；savePrd 锚定项目（工作区 + 业务登记经
 * {@link PrdArtifactAdapter}），路径正本取自登记侧。
 */
class BaToolkitSupplierTest {

    private final PrdArtifactAdapter prdArtifacts = mock(PrdArtifactAdapter.class);

    @Test
    void given_project_dev_workspace_when_toolkit_then_ask_user_and_save_prd_registered() {
        when(prdArtifacts.workspacePath()).thenReturn("docs/PRD.md");
        BaToolkitSupplier supplier = new BaToolkitSupplier(prdArtifacts);

        assertThat(supplier.toolkitFor(new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames())
                .containsExactlyInAnyOrder(AskUserTool.NAME, SavePrdTool.NAME);
    }

    @Test
    void given_local_workspace_when_toolkit_then_empty() {
        BaToolkitSupplier supplier = new BaToolkitSupplier(prdArtifacts);

        assertThat(supplier.toolkitFor(new AgentWorkspace.Local(null)).getToolNames()).isEmpty();
    }
}
