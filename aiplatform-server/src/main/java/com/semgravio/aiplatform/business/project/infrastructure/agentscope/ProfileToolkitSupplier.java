package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.agentscope.AgentToolkitSupplier;
import com.semgravio.aiplatform.base.agentscope.AgentWorkspace;
import com.semgravio.aiplatform.base.agentscope.LandedFileFacts;
import com.semgravio.aiplatform.base.skills.domain.enums.SkillSlot;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceReadinessWaiter;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.DesignSpecAppService;
import com.semgravio.aiplatform.business.project.application.FastLaneFacts;
import com.semgravio.aiplatform.business.project.application.ImageGenerationAppService;
import com.semgravio.aiplatform.business.project.application.PrdRevisionFacts;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.port.ExternalContentFetcher;
import com.semgravio.aiplatform.business.project.domain.port.WebSearchProvider;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;
import com.semgravio.aiplatform.business.project.infrastructure.InterviewToolGate;
import com.semgravio.aiplatform.business.project.infrastructure.PrdArtifactAdapter;
import com.semgravio.aiplatform.business.project.infrastructure.SedimentProposalAdapter;
import com.semgravio.aiplatform.business.project.infrastructure.SkillProposalAdapter;

import io.agentscope.core.tool.Toolkit;

/**
 * 按智能体配置的工具集装配（智能体资产归业务侧；#86 角色预设收敛为配置——
 * 职能是配置不是结构）：{@link AgentProfile#MAIN 主智能体} = ask_user（每轮一问
 * 的挂起源）+ savePrd（PRD 落盘 + 业务登记 + 修订事实登记，#52——需求侧判定的
 * 观测面）+ 只读五件（文件树 / 文件内容 / 项目事实 + 外部地址抓取 fetch_url + 联网搜索
 * web_search，答询查证与自主调研用）+ propose_skill（#263 自荐三槽位齐开——
 * 需求侧梳理经验自荐，软指引不绑时刻）+ propose_sediment（#460 数据飞轮采集层
 * 三槽位齐开——需求案例/功能模块/业务规则自荐，软指引＋轮收口轻兜底盘问），
 * 随只读工作区注册（#86 对话姿态：内核文件/shell 工具已关——写面结构性不存在，
 * PRD 写入走 savePrd 自带通道，草稿写入走 propose_skill 平台侧通道——两写面
 * 均不落工作区文件）；{@link AgentProfile#EXECUTOR run 执行体} = finish_edit
 * （更新收口结束工具——「要不要动系统」的判定面）+ update_plan（步骤清单——
 * run 级计划的全量快照观测面，#236：part-plan 部件由部件映射表从参数增量产出）
 * + propose_skill（技能自荐——验证过的编码模式写草稿待审，#259 自产线；骨架件
 * 无开关概念，软指引在工作协议——无模式可沉淀的 run 不调用）
 * + propose_sediment（沉淀自荐——可复用功能模块/业务规则写采集层草稿待审，
 * #460；骨架件无开关，软指引在工作协议——无内容不调用）＋generate_image
 * （#545 图片能力下放构建轨，ADR-0052——演示配图落 public/；增强件，
 * {@code gi} 规格件关即不挂载，回无图现状）；
 * {@link AgentProfile#DESIGNER 设计执行体} = generate_image（出图工具件，#288
 * 立内核、#289 发放——档位表落提示词层，ADR-0026 不做代码级硬路由；骨架件
 * 恒挂载不读 gi 件），随设计面
 * 工作区注册（写文件件是 harness 内建、经 ProjectDesign 形态自带——shell 与
 * 委派在工厂结构性关闭）；
 * verify 子键（#352，子智能体自有平台工具面——平台工厂构建子级时按此键取
 * 视图合并，镜像技能仓库的子键路由先例）= report_verification（验证结果登记
 * ——收尾卡验证统计的事实源）+ propose_skill（subagent 槽血统实例，验证经验
 * 自荐——绑任务回交前，挂载与否归声明 allowlist 治理）+ propose_sediment
 * （subagent 槽血统实例，沉淀自荐——#460，挂载治理归声明 allowlist）；
 * 其余编码工具——含内核 shell——由 harness 内核自带，#219 透明面化后破坏性命令
 * 直通不确认；其余配置 / 本地兜底
 * 工作区 / 无配置语境 = 空集（模型不可见）。<b>#604 拆 run 尝试体旧机制</b>：run
 * 执行体槽（finish_edit/update_plan/技能与沉淀自荐 executor 槽/构建轨出图 gi 件）
 * 与 verify 子键视图（report_verification＋subagent 槽自荐两件）随子智能体委派
 * 与旧执行协议一并退役——生成/更新 run 经内核装配形态派发（工具面＝恰四件经
 * {@code KernelTools} 装配结构性兑现，本装配面零注册），设计执行体槽不受影响。
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
    private final InterviewToolGate interviewToolGate;
    private final PrdRevisionFacts prdRevisions;
    private final FastLaneFacts fastLaneFacts;
    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final ExternalContentFetcher externalContentFetcher;
    private final WebSearchProvider webSearchProvider;
    private final SkillProposalAdapter skillProposals;
    private final SedimentProposalAdapter sedimentProposals;
    private final ImageGenerationAppService imageGeneration;
    private final LandedFileFacts landedFiles;
    private final DesignSpecAppService designSpecs;
    private final WorkspaceReadinessWaiter readinessWaiter;

    public ProfileToolkitSupplier(PrdArtifactAdapter prdArtifacts,
            InterviewToolGate interviewToolGate,
            PrdRevisionFacts prdRevisions, FastLaneFacts fastLaneFacts,
            ProjectRepository projectRepository, WorkspaceLifecycleAppService workspaceLifecycleAppService,
            ExternalContentFetcher externalContentFetcher, WebSearchProvider webSearchProvider,
            SkillProposalAdapter skillProposals, SedimentProposalAdapter sedimentProposals,
            ImageGenerationAppService imageGeneration,
            LandedFileFacts landedFiles, DesignSpecAppService designSpecs,
            WorkspaceReadinessWaiter readinessWaiter) {
        this.prdArtifacts = prdArtifacts;
        this.interviewToolGate = interviewToolGate;
        this.prdRevisions = prdRevisions;
        this.fastLaneFacts = fastLaneFacts;
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.externalContentFetcher = externalContentFetcher;
        this.webSearchProvider = webSearchProvider;
        this.skillProposals = skillProposals;
        this.sedimentProposals = sedimentProposals;
        this.imageGeneration = imageGeneration;
        this.landedFiles = landedFiles;
        this.designSpecs = designSpecs;
        this.readinessWaiter = readinessWaiter;
    }

    @Override
    public Toolkit toolkitFor(String agentKey, AgentWorkspace workspace, String toolSpec) {
        Toolkit toolkit = new Toolkit();
        if (AgentProfile.MAIN.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectReadOnly ro) {
            toolkit.registerAgentTool(new AskUserTool(ro.workspaceId(), interviewToolGate));
            toolkit.registerAgentTool(new SavePrdTool(prdArtifacts.workspacePath(),
                    ro.workspaceId(), ro.containerName(), readinessWaiter, prdArtifacts,
                    interviewToolGate, prdRevisions, fastLaneFacts));
            toolkit.registerAgentTool(new ListWorkspaceFilesTool(ro.workspaceId(),
                    ro.containerName(), readinessWaiter));
            toolkit.registerAgentTool(new ReadWorkspaceFileTool(ro.workspaceId(),
                    ro.containerName(), readinessWaiter));
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
            // #460 数据飞轮采集层：沉淀物自荐（需求案例/功能模块/业务规则——骨架件
            // 无开关；软指引在工作协议＋轮收口轻兜底盘问，不绑时刻不硬步骤）。写面
            // 是采集层草稿库（经适配器），不写工作区文件
            toolkit.registerAgentTool(new ProposeSedimentTool(ro.workspaceId(),
                    SkillSlot.MAIN.key(), sedimentProposals));
        }
        // #289 设计执行体（设计面工作区）：出图工具件（#288 立内核、本票发放——
        // 档位表落提示词层，执行体按设计物语义自选；#292 落盘事实报进变更事实流，
        // 收口判据与稿清单看得见图片模型路的稿）；写文件件是 harness 内建、
        // 经 ProjectDesign 形态自带（shell/委派在工厂结构性关闭——本面零注册即无）
        if (AgentProfile.DESIGNER.key().equals(agentKey)
                && workspace instanceof AgentWorkspace.ProjectDesign design) {
            toolkit.registerAgentTool(GenerateImageTool.forDesign(design.workspaceId(),
                    imageGeneration, landedFiles, designSpecs));
        }
        return toolkit;
    }
}
