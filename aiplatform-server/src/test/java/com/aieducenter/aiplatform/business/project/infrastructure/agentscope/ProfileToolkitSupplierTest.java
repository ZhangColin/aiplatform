package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftReceipt;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.AgentConfigAppService;
import com.aieducenter.aiplatform.business.project.application.ImageGenerationAppService;
import com.aieducenter.aiplatform.business.project.application.BuildPlanFacts;
import com.aieducenter.aiplatform.business.project.application.FinishEditFacts;
import com.aieducenter.aiplatform.business.project.application.PrdRevisionFacts;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.model.AgentTool;
import com.aieducenter.aiplatform.business.project.domain.model.AgentToolKind;
import com.aieducenter.aiplatform.business.project.domain.port.ExternalContentFetcher;
import com.aieducenter.aiplatform.business.project.domain.port.WebSearchProvider;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;
import com.aieducenter.aiplatform.business.project.infrastructure.SkillProposalAdapter;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

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
 *
 * <p>#252 增强工具开关：fetch_url / web_search 按 {@code toolSpec} 规格串装配——
 * 关＝不注册（退出装配面）、开＝注册（回归）、null/坏规格＝缺省全开（一次性判定
 * 等无规格语境的宽容腿）；骨架工具无开关概念恒注册。装配集与 {@link AgentTool}
 * 枚举（工具面清单正本）的一致性在此钉死——改一头不改另一头即红。</p>
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
    private final SkillProposalAdapter skillProposals = mock(SkillProposalAdapter.class);
    private final ImageGenerationAppService imageGeneration = mock(ImageGenerationAppService.class);

    private ProfileToolkitSupplier supplier() {
        when(prdArtifacts.workspacePath()).thenReturn("docs/PRD.md");
        return new ProfileToolkitSupplier(prdArtifacts, finishFacts, prdRevisions, buildPlanFacts,
                projectRepository, workspaceLifecycleAppService, externalContentFetcher,
                webSearchProvider, skillProposals, imageGeneration);
    }

    @Test
    void given_main_on_read_only_workspace_when_toolkit_then_dialog_prd_buildplan_and_read_trio() {
        // #86 并轨后的主智能体资产：访谈/判定工具 + 切片计划（saveBuildPlan）+
        // 答询查证只读五件同面（单会话连续——追问、答询、受理意见不换工具面）+
        // 技能自荐（#263 三槽位齐开——需求侧经验不绑时刻，软指引在协议）；
        // savePrd 锚定项目（经 PrdArtifactAdapter 落盘登记）；无派发工具（链必达收口
        // 在平台代码）
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null);
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder(
                AskUserTool.NAME, SavePrdTool.NAME, SaveBuildPlanTool.NAME,
                ListWorkspaceFilesTool.NAME, ReadWorkspaceFileTool.NAME, ProjectFactsTool.NAME,
                FetchUrlTool.NAME, WebSearchTool.NAME, ProposeSkillTool.NAME);
        for (String name : toolkit.getToolNames()) {
            // 只读五件与 saveBuildPlan 全 readOnly；ask_user 是挂起源（无写面）；
            // 平台侧写面两件：savePrd（PRD 产出是访谈协议的预期终点）与
            // propose_skill（草稿写库——不写工作区文件，#86 只读姿态不被破坏）
            if (!SavePrdTool.NAME.equals(name) && !ProposeSkillTool.NAME.equals(name)) {
                assertThat(toolkit.getTool(name).isReadOnly()).as(name).isTrue();
            }
        }
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
    void given_executor_on_project_dev_when_toolkit_then_finish_edit_update_plan_and_propose_skill() {
        // 执行体的业务工具面 = 结束工具（#46 修正收口判定）+ 步骤清单（#236 run 级
        // 计划的全量快照观测面——呈现归部件映射表，本工具零副作用）+ 技能自荐
        // （#259 过程模式沉淀待审——骨架件无开关）：主智能体资产
        // 不泄漏（ask_user/savePrd/只读五件都不在执行体面），其余编码工具（含内核
        // shell，#219 透明面化后破坏性命令直通）由 harness 内核自带
        assertThat(supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null)
                .getToolNames())
                .containsExactlyInAnyOrder(FinishEditTool.NAME, UpdatePlanTool.NAME,
                        ProposeSkillTool.NAME);
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

    // ---------- #289 设计执行体（设计面工作区：写文件件＋出图件，无 shell） ----------

    @Test
    void given_designer_on_design_workspace_when_toolkit_then_generate_image_only() {
        // #289 设计执行体平台工具面＝出图工具件（#288 立内核、#289 发放——档位表
        // 落提示词层）；写文件件是 harness 内建、经 ProjectDesign 形态自带（shell/
        // 委派在工厂结构性关闭），不在此注册——主智能体与执行体资产不泄漏
        assertThat(supplier().toolkitFor(AgentProfile.DESIGNER.key(),
                        new AgentWorkspace.ProjectDesign("42", "ws-42-dev"), null)
                .getToolNames())
                .containsExactly(GenerateImageTool.NAME);
    }

    @Test
    void given_designer_on_other_workspaces_when_toolkit_then_empty() {
        // 设计资产只随设计面发放（配置 × 工作区形态双锚防误配——#289 无 shell 的
        // 结构性第一道：错配到 ProjectDev 即零平台件，工具面仍经工厂关 shell）
        assertThat(supplier().toolkitFor(AgentProfile.DESIGNER.key(),
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null)
                .getToolNames()).isEmpty();
        assertThat(supplier().toolkitFor(AgentProfile.DESIGNER.key(),
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null)
                .getToolNames()).isEmpty();
    }

    // ---------- #263 自荐三槽位齐开：self-test 子键视图（subagent 槽血统） ----------

    @Test
    void given_self_test_key_on_project_dev_when_toolkit_then_propose_skill_only() {
        // 子智能体自有平台工具面＝技能自荐一件（#263 声明白名单接入的本体——
        // 子键视图发 subagent 槽实例，执行体资产 finish_edit/update_plan 不泄漏）
        assertThat(supplier().toolkitFor(ProfileSubagentSupplier.SELF_TEST_NAME,
                        new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null).getToolNames())
                .containsExactly(ProposeSkillTool.NAME);
    }

    @Test
    void given_self_test_key_on_other_workspaces_when_toolkit_then_empty() {
        // 子键视图只随项目 dev 面发放（self-test 委派位只在 ProjectDev——与声明面同锚）
        assertThat(supplier().toolkitFor(ProfileSubagentSupplier.SELF_TEST_NAME,
                        new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), null)
                .getToolNames()).isEmpty();
        assertThat(supplier().toolkitFor(ProfileSubagentSupplier.SELF_TEST_NAME,
                        new AgentWorkspace.Local(null), null).getToolNames()).isEmpty();
    }

    @Test
    void given_three_slot_assemblies_when_propose_skill_called_then_lineage_slot_each() {
        // #263 血统验收：三槽位装配出的 propose_skill 实例各带正确来源槽位——真缝
        // 走通（装配 → 工具执行 → 适配器血统），草稿落库的槽位血统不串槽
        assertProposeLineage(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "main");
        assertProposeLineage(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "executor");
        assertProposeLineage(ProfileSubagentSupplier.SELF_TEST_NAME,
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), "subagent");
    }

    /** 适配器替身：记录血统调用形状（SkillProposalAdapter 为具体类，子类覆写）。 */
    private static final class RecordingProposeAdapter extends SkillProposalAdapter {

        final java.util.List<String> calls = new java.util.ArrayList<>();

        RecordingProposeAdapter() {
            super(null, null);
        }

        @Override
        public SkillDraftReceipt propose(String workspaceId, String runId, SkillSlot slot,
                String name, String description, String content) {
            calls.add(workspaceId + "|" + runId + "|" + slot.key() + "|" + name);
            return SkillDraftReceipt.accepted(1L, "技能草稿已留档待审。");
        }
    }

    private void assertProposeLineage(String agentKey, AgentWorkspace workspace, String slotKey) {
        RecordingProposeAdapter recorder = new RecordingProposeAdapter();
        when(prdArtifacts.workspacePath()).thenReturn("docs/PRD.md");
        var supplier = new ProfileToolkitSupplier(prdArtifacts, finishFacts, prdRevisions,
                buildPlanFacts, projectRepository, workspaceLifecycleAppService,
                externalContentFetcher, webSearchProvider, recorder, imageGeneration);
        var toolkit = supplier.toolkitFor(agentKey, workspace, null);
        assertThat(toolkit.getToolNames()).as(agentKey).contains(ProposeSkillTool.NAME);
        String name = "seam-check-" + slotKey;
        java.util.Map<String, Object> input = java.util.Map.of(
                "name", name, "description", "缝测血统。", "body", "正文。");
        var result = Mono.from(toolkit.getTool(ProposeSkillTool.NAME)
                .callAsync(ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("tc-1", ProposeSkillTool.NAME,
                                input, null))
                        .input(input)
                        .runtimeContext(RuntimeContext.builder()
                                .sessionId("s").userId("u")
                                .put(AgentscopeAgentClient.RUN_ID_CONTEXT_KEY, "run-seam")
                                .build())
                        .build()))
                .block();
        assertThat(result.getState()).as(agentKey).isNotEqualTo(ToolResultState.ERROR);
        assertThat(recorder.calls).containsExactly("42|run-seam|" + slotKey + "|" + name);
    }

    @Test
    void given_main_protocol_when_built_then_soft_guidance_present() {
        // #263 软指引落位钉死：主智能体协议含自荐指引句（不绑时刻——与 self-test
        // 正文钉死测试对称；执行体对应句 #259 已落）
        assertThat(AgentProfile.MAIN.systemPrompt()).contains("软指引非必做");
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

    @Test
    void given_executor_capability_inventory_when_assembled_then_prompt_mentions_every_tool() {
        // #216 单一事实的执行体镜像：装配的每件业务工具（finish_edit / update_plan）
        // 都在执行协议里点名（update_plan 的调用纪律 = #236 提示词口径）
        var toolkit = supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null);
        String prompt = AgentProfile.EXECUTOR.systemPrompt();
        for (String name : toolkit.getToolNames()) {
            assertThat(prompt).as("执行体执行协议应点名工具：%s", name).contains(name);
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
                SaveBuildPlanTool.NAME, ListWorkspaceFilesTool.NAME, ReadWorkspaceFileTool.NAME,
                ProjectFactsTool.NAME);
    }

    @Test
    void given_both_enhancements_disabled_when_toolkit_then_skeleton_only() {
        // 两件全关＝只剩骨架七件（编排链路＋只读三件＋自荐 #263——结构性锁死件
        // 不受开关影响）
        var toolkit = supplier().toolkitFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"), "ws=false,fu=false");
        assertThat(toolkit.getToolNames()).containsExactlyInAnyOrder(
                AskUserTool.NAME, SavePrdTool.NAME, SaveBuildPlanTool.NAME,
                ListWorkspaceFilesTool.NAME, ReadWorkspaceFileTool.NAME, ProjectFactsTool.NAME,
                ProposeSkillTool.NAME);
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
    void given_spec_round_trip_when_encode_then_decode_preserves_both_switches() {
        // 编解码对偶单点：EffectiveConfig.toolSpec() 编码 ⇄ toolEnabled 解码值一致
        // （规格串是底座不解释的透传串，语义只在这两处——漂移即红）
        for (boolean ws : new boolean[]{true, false}) {
            for (boolean fu : new boolean[]{true, false}) {
                String spec = new AgentConfigAppService.EffectiveConfig(
                        "prompt", "model", "deepseek:model", ws, fu).toolSpec();
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
        var executorToolkit = supplier().toolkitFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"), null);
        assertThat(executorToolkit.getToolNames()).containsExactlyInAnyOrderElementsOf(
                AgentTool.ofSlot("executor").stream().map(AgentTool::toolName).toList());
        // #289 designer 槽同源：装配注册集 ≡ 枚举 designer 槽位集合（generate_image）
        var designerToolkit = supplier().toolkitFor(AgentProfile.DESIGNER.key(),
                new AgentWorkspace.ProjectDesign("42", "ws-42-dev"), null);
        assertThat(designerToolkit.getToolNames()).containsExactlyInAnyOrderElementsOf(
                AgentTool.ofSlot("designer").stream().map(AgentTool::toolName).toList());
        assertThat(AgentTool.ofSlot("main").stream().filter(
                        tool -> tool.kind() == AgentToolKind.ENHANCEMENT).map(AgentTool::toolName))
                .containsExactlyInAnyOrder(WebSearchTool.NAME, FetchUrlTool.NAME);
    }
}
