package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.FinishEditFacts;
import com.aieducenter.aiplatform.business.project.application.PrdRevisionFacts;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

/**
 * 按配置的工具集装配（#86 角色预设收敛为配置——职能是配置不是结构）：
 * 主智能体 = {ask_user, savePrd}（追问挂起源 + PRD 落盘/修订事实登记——需求侧
 * 判定的观测面）+ 只读三件 {list_workspace_files, read_workspace_file,
 * query_project_facts}（答询查证），仅随只读工作区注册（#86 对话姿态：内核
 * 文件/shell 工具已关，写面结构性不存在——PRD 写入走 savePrd 自带通道）；
 * run 执行体 = {finish_edit}（更新收口结束工具——「要不要动系统」的判定面）
 * + {command}（#83 需确认的命令工具）；无配置语境 / 本地兜底工作区 = 空集。
 */
class ProfileToolkitSupplierTest {

    private final PrdArtifactAdapter prdArtifacts = mock(PrdArtifactAdapter.class);
    private final FinishEditFacts finishFacts = new FinishEditFacts();
    private final PrdRevisionFacts prdRevisions = new PrdRevisionFacts();
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService =
            mock(WorkspaceLifecycleAppService.class);

    private ProfileToolkitSupplier supplier() {
        when(prdArtifacts.workspacePath()).thenReturn("docs/PRD.md");
        return new ProfileToolkitSupplier(prdArtifacts, finishFacts, prdRevisions,
                projectRepository, workspaceLifecycleAppService);
    }

    @Test
    void given_main_on_read_only_workspace_when_toolkit_then_dialog_and_prd_and_read_trio() {
        // #86 并轨后的主智能体资产：访谈/判定工具 + 答询查证只读三件同面（单会话
        // 连续——追问、答询、受理意见不换工具面）；savePrd 锚定项目（经
        // PrdArtifactAdapter 落盘登记）；无派发工具（链必达收口在平台代码）
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"));
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder(
                AskUserTool.NAME, SavePrdTool.NAME,
                ListWorkspaceFilesTool.NAME, ReadWorkspaceFileTool.NAME, ProjectFactsTool.NAME);
        for (String name : toolkit.getToolNames()) {
            // 只读三件全 readOnly；ask_user 是挂起源（无写面）；savePrd 是唯一
            // 写面（PRD 产出是访谈协议的预期终点）
            if (!SavePrdTool.NAME.equals(name)) {
                assertThat(toolkit.getTool(name).isReadOnly()).as(name).isTrue();
            }
        }
    }

    @Test
    void given_main_on_dev_workspace_when_toolkit_then_empty() {
        // 对话资产只随只读面发放（配置 × 工作区形态双锚，防误配——读写面上的主
        // 智能体是配置漂移，不发放）
        assertThat(supplier().toolkitFor(AgentProfile.MAIN.key(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames())
                .isEmpty();
    }

    @Test
    void given_executor_on_project_dev_when_toolkit_then_finish_edit_and_command() {
        // 执行体的业务工具面 = 结束工具（#46 修正收口判定）+ 需确认的命令工具
        // （#83：command 替位内核 shell——破坏性命令挂确认卡）：主智能体资产不
        // 泄漏（ask_user/savePrd/只读三件都不在执行体面），其余编码工具由 harness
        // 内核自带
        assertThat(supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames())
                .containsExactlyInAnyOrder(FinishEditTool.NAME, ConfirmingShellTool.NAME);
    }

    @Test
    void given_executor_on_read_only_workspace_when_toolkit_then_empty() {
        // 执行体资产不随只读面发放（双锚防误配）
        assertThat(supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"))
                .getToolNames())
                .isEmpty();
    }

    @Test
    void given_unknown_or_absent_key_when_toolkit_then_empty() {
        assertThat(supplier().toolkitFor(null, new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames()).isEmpty();
        assertThat(supplier().toolkitFor("naming", new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames()).isEmpty();
    }

    @Test
    void given_main_on_local_workspace_when_toolkit_then_empty() {
        // 本地兜底工作区无项目语境：对话资产也不发放
        assertThat(supplier().toolkitFor(AgentProfile.MAIN.key(), new AgentWorkspace.Local(null))
                .getToolNames()).isEmpty();
    }
}
