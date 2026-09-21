package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

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

/**
 * 按配置的工具集装配（#86 角色预设收敛为配置——职能是配置不是结构）：
 * 主智能体 = {ask_user, savePrd, saveBuildPlan}（追问挂起源 + PRD 落盘/修订事实
 * 登记——需求侧判定的观测面 + 切片计划事实登记——生成编排的切片输入）+ 只读五件
 * {list_workspace_files, read_workspace_file, query_project_facts, fetch_url,
 * web_search}（答询查证 + 自主调研），
 * 仅随只读工作区注册（#86 对话姿态：内核文件/shell 工具已关，写面结构性不存在——
 * PRD 写入走 savePrd 自带通道）；
 * run 执行体 = {finish_edit}（更新收口结束工具——「要不要动系统」的判定面；
 * 其余编码工具——含内核 shell——由 harness 内核自带）；无配置语境 / 本地兜底
 * 工作区 = 空集。
 */
class ProfileToolkitSupplierTest {

    private final PrdArtifactAdapter prdArtifacts = mock(PrdArtifactAdapter.class);
    private final FinishEditFacts finishFacts = new FinishEditFacts();
    private final PrdRevisionFacts prdRevisions = new PrdRevisionFacts();
    private final BuildPlanFacts buildPlanFacts = new BuildPlanFacts();
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService =
            mock(WorkspaceLifecycleAppService.class);
    private final ExternalContentFetcher externalContentFetcher = mock(ExternalContentFetcher.class);
    private final WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);

    private ProfileToolkitSupplier supplier() {
        when(prdArtifacts.workspacePath()).thenReturn("docs/PRD.md");
        return new ProfileToolkitSupplier(prdArtifacts, finishFacts, prdRevisions, buildPlanFacts,
                projectRepository, workspaceLifecycleAppService, externalContentFetcher,
                webSearchProvider);
    }

    @Test
    void given_main_on_read_only_workspace_when_toolkit_then_dialog_prd_buildplan_and_read_trio() {
        // #86 并轨后的主智能体资产：访谈/判定工具 + 切片计划（saveBuildPlan）+
        // 答询查证只读五件同面（单会话连续——追问、答询、受理意见不换工具面）；
        // savePrd 锚定项目（经 PrdArtifactAdapter 落盘登记）；无派发工具（链必达收口
        // 在平台代码）
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"));
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder(
                AskUserTool.NAME, SavePrdTool.NAME, SaveBuildPlanTool.NAME,
                ListWorkspaceFilesTool.NAME, ReadWorkspaceFileTool.NAME, ProjectFactsTool.NAME,
                FetchUrlTool.NAME, WebSearchTool.NAME);
        for (String name : toolkit.getToolNames()) {
            // 只读五件与 saveBuildPlan 全 readOnly；ask_user 是挂起源（无写面）；
            // savePrd 是唯一写面（PRD 产出是访谈协议的预期终点）
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
    void given_executor_on_project_dev_when_toolkit_then_finish_edit_and_update_plan() {
        // 执行体的业务工具面 = 结束工具（#46 修正收口判定）+ 步骤清单（#236 run 级
        // 计划的全量快照观测面——呈现归部件映射表，本工具零副作用）：主智能体资产
        // 不泄漏（ask_user/savePrd/只读五件都不在执行体面），其余编码工具（含内核
        // shell，#219 透明面化后破坏性命令直通）由 harness 内核自带
        assertThat(supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"))
                .getToolNames())
                .containsExactlyInAnyOrder(FinishEditTool.NAME, UpdatePlanTool.NAME);
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

    @Test
    void given_main_capability_inventory_when_assembled_then_prompt_mentions_every_tool() {
        // #216 单一事实：提示词能力清单是正本，须与工具装配一致——装配的每件业务
        // 工具都在提示词里点名（防止装配加了工具、提示词漏描述而模型「不认」这能力）。
        // 只验装配⊆提示词单向：提示词另点名的 load_skill_through_path 是内核技能加载
        // 工具（随 prd-writing 技能发放，非本装配器的业务注册），反向断言会误伤内核工具
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"));
        String prompt = AgentProfile.MAIN.systemPrompt();
        for (String name : toolkit.getToolNames()) {
            assertThat(prompt).as("主智能体能力清单应点名工具：%s", name).contains(name);
        }
    }

    @Test
    void given_executor_capability_inventory_when_assembled_then_prompt_mentions_every_tool() {
        // #216 单一事实的执行体镜像：装配的每件业务工具（finish_edit / update_plan）
        // 都在执行协议里点名（update_plan 的调用纪律 = #236 提示词口径）
        var toolkit = supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"));
        String prompt = AgentProfile.EXECUTOR.systemPrompt();
        for (String name : toolkit.getToolNames()) {
            assertThat(prompt).as("执行体执行协议应点名工具：%s", name).contains(name);
        }
    }
}
