package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.application.FinishFixFacts;
import com.aieducenter.aiplatform.business.project.domain.model.RolePreset;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

/**
 * 按角色的工具集装配（#43 工具面收紧 + #46 结束工具）：BA = {ask_user, savePrd}
 * （仅项目 dev 工作区——savePrd 锚定项目，经 {@link PrdArtifactAdapter} 落盘登记）；
 * 派发工具已撤（BA 无派发权，链必达收口在平台代码）；CODER = {finish_fix}
 * （修正收口结束工具——「要不要动系统」的判定面；其余编码工具由 harness 内核
 * 自带，ask_user/savePrd 挂在编码智能体上是泄漏，行为面：编码智能体全程不可能
 * 产问答/存 PRD/派发类工具事件）；无角色语境 / 本地兜底工作区 = 空集。
 */
class RoleToolkitSupplierTest {

    private final PrdArtifactAdapter prdArtifacts = mock(PrdArtifactAdapter.class);
    private final FinishFixFacts finishFacts = new FinishFixFacts();

    private RoleToolkitSupplier supplier() {
        when(prdArtifacts.workspacePath()).thenReturn("docs/PRD.md");
        return new RoleToolkitSupplier(prdArtifacts, finishFacts);
    }

    @Test
    void given_ba_on_project_dev_when_toolkit_then_ask_user_and_save_prd_only() {
        assertThat(supplier().toolkitFor(RolePreset.BA.name(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames())
                .containsExactlyInAnyOrder(AskUserTool.NAME, SavePrdTool.NAME);
    }

    @Test
    void given_coder_on_project_dev_when_toolkit_then_finish_fix_only() {
        // 编码智能体的业务工具面 = 结束工具（#46 修正收口判定）一个：BA 资产不再
        // 泄漏（#43 前 CODER 名义上挂着 ask_user/savePrd/startFixRun），编码工具
        // 由 harness 内核自带
        assertThat(supplier().toolkitFor(RolePreset.CODER.name(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames())
                .containsExactly(FinishFixTool.NAME);
    }

    @Test
    void given_unknown_or_absent_role_when_toolkit_then_empty() {
        assertThat(supplier().toolkitFor(null, new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames()).isEmpty();
        assertThat(supplier().toolkitFor("naming", new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames()).isEmpty();
    }

    @Test
    void given_ba_on_local_workspace_when_toolkit_then_empty() {
        // 本地兜底工作区无项目语境：BA 资产也不发放
        assertThat(supplier().toolkitFor(RolePreset.BA.name(), new AgentWorkspace.Local(null))
                .getToolNames()).isEmpty();
    }
}
