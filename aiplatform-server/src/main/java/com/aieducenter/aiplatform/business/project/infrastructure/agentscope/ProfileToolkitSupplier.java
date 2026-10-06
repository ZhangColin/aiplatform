package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentToolkitSupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.AgentConfigAppService;
import com.aieducenter.aiplatform.business.project.application.BuildPlanFacts;
import com.aieducenter.aiplatform.business.project.application.FinishEditFacts;
import com.aieducenter.aiplatform.business.project.application.ImageGenerationAppService;
import com.aieducenter.aiplatform.business.project.application.PrdRevisionFacts;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.port.ExternalContentFetcher;
import com.aieducenter.aiplatform.business.project.domain.port.WebSearchProvider;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.project.infrastructure.PrdArtifactAdapter;
import com.aieducenter.aiplatform.business.project.infrastructure.SkillProposalAdapter;

import io.agentscope.core.tool.Toolkit;

/**
 * 按智能体配置的工具集装配（智能体资产归业务侧；#86 角色预设收敛为配置——
 * 职能是配置不是结构）：{@link AgentProfile#MAIN 主智能体} = ask_user（每轮一问
 * 的挂起源）+ savePrd（PRD 落盘 + 业务登记 + 修订事实登记，#52——需求侧判定的
 * 观测面）+ saveBuildPlan（切片计划事实登记，ADR 0009——生成编排的切片输入）+
 * 只读五件（文件树 / 文件内容 / 项目事实 + 外部地址抓取 fetch_url + 联网搜索
 * web_search，答询查证与自主调研用）+ propose_skill（#263 自荐三槽位齐开——
 * 需求侧梳理经验自荐，软指引不绑时刻），
 * 随只读工作区注册（#86 对话姿态：内核文件/shell 工具已关——写面结构性不存在，
 * PRD 写入走 savePrd 自带通道，草稿写入走 propose_skill 平台侧通道——两写面
 * 均不落工作区文件）；{@link AgentProfile#EXECUTOR run 执行体} = finish_edit
 * （更新收口结束工具——「要不要动系统」的判定面）+ update_plan（步骤清单——
 * run 级计划的全量快照观测面，#236：part-plan 部件由部件映射表从参数增量产出）
 * + propose_skill（技能自荐——验证过的编码模式写草稿待审，#259 自产线；骨架件
 * 无开关概念，软指引在工作协议——无模式可沉淀的 run 不调用）；
 * {@link AgentProfile#DESIGNER 设计执行体} = generate_image（出图工具件，#288
 * 立内核、#289 发放——档位表落提示词层，ADR-0026 不做代码级硬路由），随设计面
 * 工作区注册（写文件件是 harness 内建、经 ProjectDesign 形态自带——shell 与
 * 委派在工厂结构性关闭）；
 * self-test 子键（#263，子智能体自有平台工具面——平台工厂构建子级时按此键取
 * 视图合并，镜像技能仓库的子键路由先例）= propose_skill（subagent 槽血统实例，
 * 测试清单经验自荐——绑任务回交前，挂载与否归声明 allowlist 治理）；
 * 其余编码工具——含内核 shell——由 harness 内核自带，#219 透明面化后破坏性命令
 * 直通不确认；其余配置 / 本地兜底
 * 工作区 / 无配置语境 = 空集（模型不可见）。
 *
 * <p><b>增强工具开关（#252，ADR-0021 窄幅开关）</b>：fetch_url / web_search 两件
 * 按 {@code toolSpec}（工具面规格串，{@code AgentConfigAppService} 编码——运营
 * 配置开关列的生效态）装配——关＝不注册（该工具退出装配面），开＝注册（回归）；
 * 骨架工具无开关概念恒注册。工具面正本（名字/类别/槽位）在 {@code AgentTool}
 * 枚举，与本装配的同源性由装配面测试钉死。</p>
 */
@Component
public class ProfileToolkitSupplier implements AgentToolkitSupplier {

    /** 工具面规格串的件键（与 {@code AgentConfigAppService#toolSpec} 编码对偶）。 */
    private static final String WEB_SEARCH_KEY = "ws";
    private static final String FETCH_URL_KEY = "fu";

    private final PrdArtifactAdapter prdArtifacts;
    private final FinishEditFacts finishFacts;
    private final PrdRevisionFacts prdRevisions;
    private final BuildPlanFacts buildPlanFacts;
    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final ExternalContentFetcher externalContentFetcher;
    private final WebSearchProvider webSearchProvider;
    private final SkillProposalAdapter skillProposals;
    private final ImageGenerationAppService imageGeneration;

    public ProfileToolkitSupplier(PrdArtifactAdapter prdArtifacts, FinishEditFacts finishFacts,
            PrdRevisionFacts prdRevisions, BuildPlanFacts buildPlanFacts,
            ProjectRepository projectRepository, WorkspaceLifecycleAppService workspaceLifecycleAppService,
            ExternalContentFetcher externalContentFetcher, WebSearchProvider webSearchProvider,
            SkillProposalAdapter skillProposals, ImageGenerationAppService imageGeneration) {
        this.prdArtifacts = prdArtifacts;
        this.finishFacts = finishFacts;
        this.prdRevisions = prdRevisions;
        this.buildPlanFacts = buildPlanFacts;
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.externalContentFetcher = externalContentFetcher;
        this.webSearchProvider = webSearchProvider;
        this.skillProposals = skillProposals;
        this.imageGeneration = imageGeneration;
    }

    @Override
    public Toolkit toolkitFor(String agentKey, AgentWorkspace workspace, String toolSpec) {
        Toolkit toolkit = new Toolkit();
        if (AgentProfile.MAIN.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectReadOnly ro) {
            toolkit.registerAgentTool(new AskUserTool());
            toolkit.registerAgentTool(new SavePrdTool(prdArtifacts.workspacePath(),
                    ro.workspaceId(), ro.containerName(), prdArtifacts, prdRevisions));
            toolkit.registerAgentTool(new SaveBuildPlanTool(ro.workspaceId(), buildPlanFacts));
            toolkit.registerAgentTool(new ListWorkspaceFilesTool(ro.containerName()));
            toolkit.registerAgentTool(new ReadWorkspaceFileTool(ro.containerName()));
            toolkit.registerAgentTool(new ProjectFactsTool(ro.workspaceId(),
                    projectRepository, workspaceLifecycleAppService));
            // #213 抓取闭环：贴 URL 读外部资料（只读 GET + 四条安全底线在取数口兑现）
            // ——增强工具，开关关即不注册（#252）
            if (AgentConfigAppService.toolEnabled(toolSpec, FETCH_URL_KEY)) {
                toolkit.registerAgentTool(new FetchUrlTool(externalContentFetcher));
            }
            // #215 调研闭环：自主搜索补缺口（供数方接口 + 平台配置实例化）
            // ——增强工具，开关关即不注册（#252）
            if (AgentConfigAppService.toolEnabled(toolSpec, WEB_SEARCH_KEY)) {
                toolkit.registerAgentTool(new WebSearchTool(webSearchProvider));
            }
            // #263 自荐三槽位齐开：需求侧梳理经验自荐（骨架件无开关；软指引在
            // 工作协议——不绑时刻，无经验可沉淀的会话不调用）。写面是平台草稿库
            // （经适配器），不写工作区文件——#86 只读姿态不被破坏
            toolkit.registerAgentTool(new ProposeSkillTool(ro.workspaceId(),
                    SkillSlot.MAIN, skillProposals));
        }
        if (AgentProfile.EXECUTOR.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectDev dev) {
            toolkit.registerAgentTool(new FinishEditTool(dev.workspaceId(), finishFacts));
            // #236 步骤清单：run 级计划的全量快照（呈现面在部件映射表，本工具零副作用）
            toolkit.registerAgentTool(new UpdatePlanTool());
            // #259 技能自荐：验证过的编码模式沉淀为草稿（骨架件无开关；软指引在
            // 工作协议——无模式可沉淀的 run 不调用）
            toolkit.registerAgentTool(new ProposeSkillTool(dev.workspaceId(),
                    SkillSlot.EXECUTOR, skillProposals));
        }
        // #289 设计执行体（设计面工作区）：出图工具件（#288 立内核、本票发放——
        // 档位表落提示词层，执行体按设计物语义自选）；写文件件是 harness 内建、
        // 经 ProjectDesign 形态自带（shell/委派在工厂结构性关闭——本面零注册即无）
        if (AgentProfile.DESIGNER.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectDesign design) {
            toolkit.registerAgentTool(new GenerateImageTool(design.workspaceId(), imageGeneration));
        }
        // #263 self-test 子键视图：子智能体自有平台工具面（技能自荐 subagent 槽
        // 血统实例）——平台工厂构建子智能体时按本键取视图合并进子级工具面
        // （镜像技能仓库的子键路由先例），声明 allowlist 治理挂载与否
        if (ProfileSubagentSupplier.SELF_TEST_NAME.equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectDev dev) {
            toolkit.registerAgentTool(new ProposeSkillTool(dev.workspaceId(),
                    SkillSlot.SUBAGENT, skillProposals));
        }
        return toolkit;
    }
}
