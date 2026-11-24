package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceReadinessWaiter;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.FastLaneFacts;
import com.semgravio.aiplatform.business.project.application.ModificationHandoffFacts;
import com.semgravio.aiplatform.business.project.application.PrdRevisionFacts;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.AgentTool;
import com.semgravio.aiplatform.business.project.domain.model.AgentToolKind;
import com.semgravio.aiplatform.business.project.domain.port.ExternalContentFetcher;
import com.semgravio.aiplatform.business.project.domain.port.WebSearchProvider;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;
import com.semgravio.aiplatform.business.project.infrastructure.InterviewToolGate;
import com.semgravio.aiplatform.business.project.infrastructure.PrdArtifactAdapter;

/**
 * 按配置的工具集装配（#86 角色预设收敛为配置——职能是配置不是结构）：
 * 主智能体 = {ask_user, savePrd}（追问挂起源 + PRD 落盘/修订事实登记——
 * 需求侧判定的观测面）+ 只读五件
 * {list_workspace_files, read_workspace_file, query_project_facts, fetch_url,
 * web_search}（答询查证 + 自主调研），
 * 仅随只读工作区注册（#86 对话姿态：内核文件/shell 工具已关，写面结构性不存在——
 * PRD 写入走 savePrd 自带通道）；#638 阶段六技能清场后自荐两件
 * （propose_skill／propose_sediment）连同协议软指引退场，工具面回到七件。
 * run 执行体槽 = 空集（#604 随旧 run 尝试体整体退役，生成/更新 run 经内核装配
 * 形态派发、工具面＝恰四件经 KernelTools 装配结构性兑现，本装配面零注册）；
 * 无配置语境 / 本地兜底工作区 = 空集。
 *
 * <p>#252 增强工具开关：fetch_url / web_search 按 {@code toolSpec} 规格串装配——
 * 关＝不注册（退出装配面）、开＝注册（回归）、null/坏规格＝缺省全开（一次性判定
 * 等无规格语境的宽容腿）；骨架工具无开关概念恒注册。装配集与 {@link AgentTool}
 * 枚举（工具面清单正本）的一致性在此钉死——改一头不改另一头即红。</p>
 */
class ProfileToolkitSupplierTest {

    private final PrdArtifactAdapter prdArtifacts = mock(PrdArtifactAdapter.class);
    private final InterviewToolGate interviewToolGate = mock(InterviewToolGate.class);
    private final PrdRevisionFacts prdRevisions = new PrdRevisionFacts();
    private final FastLaneFacts fastLaneFacts = new FastLaneFacts();
    private final ModificationHandoffFacts modificationHandoffs = new ModificationHandoffFacts();
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService =
            mock(WorkspaceLifecycleAppService.class);
    private final WorkspaceReadinessWaiter readinessWaiter = mock(WorkspaceReadinessWaiter.class);
    private final ExternalContentFetcher externalContentFetcher = mock(ExternalContentFetcher.class);
    private final WebSearchProvider webSearchProvider = mock(WebSearchProvider.class);

    private ProfileToolkitSupplier supplier() {
        when(prdArtifacts.workspacePath()).thenReturn("docs/PRD.md");
        return new ProfileToolkitSupplier(prdArtifacts, interviewToolGate, prdRevisions,
                fastLaneFacts, modificationHandoffs,
                projectRepository, workspaceLifecycleAppService, externalContentFetcher,
                webSearchProvider, readinessWaiter);
    }

    @Test
    void given_main_on_read_only_workspace_when_toolkit_then_dialog_prd_and_read_trio() {
        // #86 并轨后的主智能体资产：访谈/判定工具 +
        // 答询查证只读五件同面（单会话连续——追问、答询、受理意见不换工具面）；
        // #638 技能清场后自荐两件退场——工具面只剩七件；savePrd 锚定项目。
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null);
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder(
                AskUserTool.NAME, SavePrdTool.NAME,
                ListWorkspaceFilesTool.NAME, ReadWorkspaceFileTool.NAME, ProjectFactsTool.NAME,
                FetchUrlTool.NAME, WebSearchTool.NAME);
        for (String name : toolkit.getToolNames()) {
            // 只读五件全 readOnly；ask_user 是挂起源（无写面）；
            // 平台侧写面一件：savePrd（PRD 产出是访谈协议的预期终点）
            if (!SavePrdTool.NAME.equals(name)) {
                assertThat(toolkit.getTool(name).isReadOnly()).as(name).isTrue();
            }
        }
        // #638 验收：自荐两件结构性不在装配面
        assertThat(toolkit.getToolNames())
                .doesNotContain("propose_skill", "propose_sediment");
    }

    @Test
    void given_main_on_dialog_plane_spec_when_toolkit_then_answer_clarify_and_handoff_no_write() {
        // #622/#623 对话面：生成后的独立轻会话——答疑、澄清、交修改指令三职能
        // （ask_user 无额度闸 ＋ 只读三件 ＋ submit_modification），零写工作区/编码工具
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"),
                AgentConfigAppService.DIALOG_TOOL_SPEC);
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder(
                AskUserTool.NAME, ListWorkspaceFilesTool.NAME,
                ReadWorkspaceFileTool.NAME, ProjectFactsTool.NAME,
                SubmitModificationTool.NAME);
        assertThat(toolkit.getToolNames())
                .doesNotContain(SavePrdTool.NAME, FetchUrlTool.NAME, WebSearchTool.NAME);
        for (String name : toolkit.getToolNames()) {
            assertThat(toolkit.getTool(name).isReadOnly()).as(name).isTrue();
        }
        // 对话面标记只认显式 plane=dialog；缺省/坏规格仍主智能体全工具面
        assertThat(AgentConfigAppService.isDialogPlane(AgentConfigAppService.DIALOG_TOOL_SPEC))
                .isTrue();
        assertThat(AgentConfigAppService.isDialogPlane(null)).isFalse();
        assertThat(AgentConfigAppService.isDialogPlane("ws=true,fu=true")).isFalse();
        assertThat(AgentConfigAppService.isDialogPlane("plane=other")).isFalse();
    }

    @Test
    void given_main_on_dev_workspace_when_toolkit_then_empty() {
        // 对话资产只随只读面发放（配置 × 工作区形态双锚，防误配——读写面上的主
        // 智能体是配置漂移，不发放）
        assertThat(supplier().toolkitFor(AgentProfile.MAIN.key(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null)
                .getToolNames())
                .isEmpty();
    }

    @Test
    void given_executor_key_when_toolkit_then_empty() {
        // #604 执行体槽整体退役：finish_edit/update_plan 随旧 run 尝试体退役、
        // 自荐两件与构建轨出图 gi 件随执行协议与开关列一并拆除——生成/更新 run 经内核
        // 装配形态派发（工具面＝恰四件经 KernelTools 装配结构性兑现），本装配面
        // 对 executor 键零注册（dev/只读面同空——双锚防误配）
        assertThat(supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null)
                .getToolNames())
                .isEmpty();
        assertThat(supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null)
                .getToolNames())
                .isEmpty();
    }

    @Test
    void given_executor_on_read_only_workspace_when_toolkit_then_empty() {
        // 执行体资产不随只读面发放（双锚防误配）
        assertThat(supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null)
                .getToolNames())
                .isEmpty();
    }

    @Test
    void given_unknown_or_absent_key_when_toolkit_then_empty() {
        assertThat(supplier().toolkitFor(null, new AgentWorkspace.ProjectDev("42", "ws-42-dev"),
                        null).getToolNames()).isEmpty();
        assertThat(supplier().toolkitFor("naming", new AgentWorkspace.ProjectDev("42", "ws-42-dev"),
                        null).getToolNames()).isEmpty();
    }

    @Test
    void given_main_protocol_when_built_then_self_nomination_guidance_retired() {
        // #638 验收：自荐两件连同协议软指引、能力清单提及与轮收口提醒一并退场——
        // 主智能体协议不再出现 propose_skill／propose_sediment 与「软指引非必做」
        String prompt = AgentProfile.MAIN.systemPrompt();
        assertThat(prompt).doesNotContain("propose_skill", "propose_sediment", "软指引非必做");
    }

    @Test
    void given_main_on_local_workspace_when_toolkit_then_empty() {
        // 本地兜底工作区无项目语境：对话资产也不发放
        assertThat(supplier().toolkitFor(AgentProfile.MAIN.key(), new AgentWorkspace.Local(null),
                        null).getToolNames()).isEmpty();
    }

    @Test
    void given_main_capability_inventory_when_assembled_then_prompt_mentions_every_tool() {
        // #216 单一事实：提示词能力清单是正本，须与工具装配一致——装配的每件业务
        // 工具都在提示词里点名（防止装配加了工具、提示词漏描述而模型「不认」这能力）。
        // 只验装配⊆提示词单向：提示词另点名的 load_skill_through_path 是内核技能加载
        // 工具（随 prd-writing 技能发放，非本装配器的业务注册），反向断言会误伤内核工具
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null);
        String prompt = AgentProfile.MAIN.systemPrompt();
        for (String name : toolkit.getToolNames()) {
            assertThat(prompt).as("主智能体能力清单应点名工具：%s", name).contains(name);
        }
    }

    // ---------- #252 增强工具开关：关＝退出装配面、开＝回归、坏规格＝缺省全开 ----------

    @Test
    void given_web_search_disabled_when_toolkit_then_exits_assembly_but_skeleton_stays() {
        // 关＝该工具退出装配面（模型不可见），骨架与其余增强件不动
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "ws=false,fu=true");
        assertThat(toolkit.getToolNames()).doesNotContain(WebSearchTool.NAME);
        assertThat(toolkit.getToolNames()).contains(FetchUrlTool.NAME);
        assertThat(toolkit.getToolNames()).contains(AskUserTool.NAME, SavePrdTool.NAME,
                ListWorkspaceFilesTool.NAME, ReadWorkspaceFileTool.NAME,
                ProjectFactsTool.NAME);
    }

    @Test
    void given_both_enhancements_disabled_when_toolkit_then_skeleton_only() {
        // 两件全关＝只剩骨架（编排链路＋只读三件——结构性锁死件不受开关影响）
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "ws=false,fu=false");
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder(
                AskUserTool.NAME, SavePrdTool.NAME,
                ListWorkspaceFilesTool.NAME, ReadWorkspaceFileTool.NAME, ProjectFactsTool.NAME);
    }

    @Test
    void given_enabled_spec_when_toolkit_then_rejoins_assembly() {
        // 开＝回归（与 #252「开即回归」的装配断言对偶）
        assertThat(supplier().toolkitFor(AgentProfile.MAIN.key(),
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "ws=true,fu=false")
                .getToolNames())
                .contains(WebSearchTool.NAME)
                .doesNotContain(FetchUrlTool.NAME);
    }

    @Test
    void given_absent_or_broken_spec_when_toolkit_then_defaults_open() {
        // null/缺件/坏值＝缺省开（一次性判定等无规格语境；坏规格不炸装配面——
        // 解码单点 AgentConfigAppService.toolEnabled 的宽容腿）
        assertThat(supplier().toolkitFor(AgentProfile.MAIN.key(),
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null)
                .getToolNames()).contains(WebSearchTool.NAME, FetchUrlTool.NAME);
        assertThat(supplier().toolkitFor(AgentProfile.MAIN.key(),
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "fu=false")
                .getToolNames()).contains(WebSearchTool.NAME); // 缺 ws 件＝ws 缺省开
        assertThat(supplier().toolkitFor(AgentProfile.MAIN.key(),
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "garbage")
                .getToolNames()).contains(WebSearchTool.NAME, FetchUrlTool.NAME);
    }

    @Test
    void given_spec_round_trip_when_encode_then_decode_preserves_all_switches() {
        // 编解码对偶单点：EffectiveConfig.toolSpec() 编码 ⇄ toolEnabled 解码值一致
        // （规格串是底座不解释的透传串，语义只在这两处——漂移即红）
        for (boolean ws : new boolean[]{true, false}) {
            for (boolean fu : new boolean[]{true, false}) {
                String spec = new AgentConfigAppService.EffectiveConfig(
                        "prompt", "model", ws, fu).toolSpec();
                assertThat(AgentConfigAppService.toolEnabled(spec, "ws")).as(spec).isEqualTo(ws);
                assertThat(AgentConfigAppService.toolEnabled(spec, "fu")).as(spec).isEqualTo(fu);
            }
        }
    }

    @Test
    void given_full_inventory_when_assembled_then_matches_agent_tool_enum() {
        // 清单正本同源钉死（#252）：装配注册集（全开态）≡ AgentTool 枚举该槽位集合
        // ——枚举是后台清单的正本、装配是运行事实，两头漂移此断言即红；类别同样
        // 对齐（增强两件之外全是骨架——骨架锁死的判定基础）
        var mainToolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null);
        assertThat(mainToolkit.getToolNames()).containsExactlyInAnyOrderElementsOf(
                AgentTool.ofSlot("main").stream().map(AgentTool::toolName).toList());
        assertThat(AgentTool.ofSlot("main").stream().filter(
                        tool -> tool.kind() == AgentToolKind.ENHANCEMENT).map(AgentTool::toolName))
                .containsExactlyInAnyOrder(WebSearchTool.NAME, FetchUrlTool.NAME);
    }
}
