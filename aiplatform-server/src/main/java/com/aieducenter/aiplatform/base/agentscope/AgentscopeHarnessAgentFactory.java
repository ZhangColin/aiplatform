package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HarnessAgent 构建工厂：agent 无状态（per-session 靠 RuntimeContext 寻址），同规格
 * （name + sysPrompt + model + workspace）构建一次、进程内复用；容器关闭时统一释放
 * （HarnessAgent 是 AutoCloseable）。
 *
 * <p>工作区三形态（{@link AgentWorkspace}）：{@link AgentWorkspace.Local Local}
 * 本地目录直用；{@link AgentWorkspace.ProjectDev ProjectDev} 项目 dev 工作区——经
 * {@code abstractFilesystem} 逃生舱换 {@link DockerExecFilesystem}（docker exec
 * 落既有 dev 容器），并关闭会写 harness 内脏进项目工作区的部件（memory：源码包
 * 是交付物，记忆文件不进包）——工作区上下文（AGENTS.md 等）与
 * workspace/tools.json 读取照常，经容器文件面即项目事实；subagents 委派位在此
 * 开启（#95 挂载：声明经 {@link AgentSubagentSupplier} 取得；#260 装配一等化：
 * 声明转平台子智能体工厂构建——技能面按子智能体键接 subagent 槽位装配视图、
 * 容器文件面同父级，隔离根落位平台目录下进非交付目录集），只读面在分支处单独
 * 关闭；
 * {@link AgentWorkspace.ProjectReadOnly ProjectReadOnly} 项目工作区只读面（#86
 * 主智能体对话姿态）——容器与内脏关闭同 ProjectDev，另关内核文件/shell 工具
 * （写面结构性关闭，主智能体永不读写沙箱代码——PRD 写入走业务侧 savePrd）。</p>
 *
 * <p>会话状态：全形态统一接 {@link PostgresAgentStateStore}（cat_agent_state，
 * (userId, sessionId) 槽位）——平台重启后同一会话标识恢复续跑，会话上下文不丢；
 * 替换框架缺省的本地 JSON 文件实现（单机 {@code ~/.agentscope/state/}，多副本/
 * 重启语义不成立）。工具集经 {@link AgentToolkitSupplier}、技能经
 * {@link AgentSkillRepositorySupplier}（业务侧资产，#94 技能位）注入；工具面规格
 * 串（#252 增强工具开关）入实例缓存键——规格变＝实例变，开关变更下一轮命令构建
 * 即新装配。</p>
 */
@Slf4j
@Component
public class AgentscopeHarnessAgentFactory implements DisposableBean {

    /**
     * 真正构建 HarnessAgent 的步骤（抽出便于单测注入替身）。
     */
    interface AgentBuilder {

        HarnessAgent build(String name, String sysPrompt, String modelString,
                AgentWorkspace workspace, String agentKey, String toolSpec);
    }

    private final ConcurrentHashMap<String, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final AgentBuilder builder;
    private final AgentStateStore stateStore;
    private final AgentToolkitSupplier toolkitSupplier;

    @Autowired
    public AgentscopeHarnessAgentFactory(AgentStateStore stateStore,
            AgentToolkitSupplier toolkitSupplier, AgentSkillRepositorySupplier skillRepositorySupplier,
            AgentSubagentSupplier subagentSupplier, AgentMiddlewareSupplier middlewareSupplier,
            AgentscopeProperties properties) {
        this(stateStore, toolkitSupplier, (name, sysPrompt, modelString, workspace, agentKey, toolSpec) ->
                buildAgent(stateStore, toolkitSupplier, skillRepositorySupplier, subagentSupplier,
                        middlewareSupplier,
                        name, sysPrompt, modelString, workspace, agentKey, toolSpec, properties));
    }

    AgentscopeHarnessAgentFactory(AgentStateStore stateStore, AgentToolkitSupplier toolkitSupplier,
            AgentBuilder builder) {
        this.stateStore = stateStore;
        this.toolkitSupplier = toolkitSupplier;
        this.builder = builder;
    }

    public HarnessAgent obtain(String name, String sysPrompt, String modelString,
            AgentWorkspace workspace, String agentKey, String toolSpec) {
        // sysPrompt/workspace/agentKey/toolSpec 明文入键（不用 hashCode：碰撞会把不同
        // 人格/工作区/工具面的 agent 当同规格静默复用）；toolSpec 是工具面规格
        // （#252 增强工具开关）：工具集构建时固化（Toolkit 静态注册、无技能线的
        // 动态视图缝），规格变＝实例变——开关变更下一轮命令构建即新装配
        String key = name + "|" + modelString + "|" + sysPrompt + "|" + workspace.identity()
                + "|" + agentKey + "|" + (toolSpec == null ? "" : toolSpec);
        return agents.computeIfAbsent(key,
                k -> builder.build(name, sysPrompt, modelString, workspace, agentKey, toolSpec));
    }

    @Override
    public void destroy() {
        agents.values().forEach(agent -> {
            try {
                agent.close();
            }
            catch (Exception e) {
                log.warn("关闭 HarnessAgent 失败（忽略，继续关闭其余实例）", e);
            }
        });
        agents.clear();
    }

    private static HarnessAgent buildAgent(AgentStateStore stateStore,
            AgentToolkitSupplier toolkitSupplier, AgentSkillRepositorySupplier skillRepositorySupplier,
            AgentSubagentSupplier subagentSupplier, AgentMiddlewareSupplier middlewareSupplier,
            String name, String sysPrompt, String modelString, AgentWorkspace workspace,
            String agentKey, String toolSpec, AgentscopeProperties properties) {
        Toolkit toolkit = toolkitSupplier.toolkitFor(agentKey, workspace, toolSpec);
        // 模型边界计量（#109）：主模型经 MeteredModel 包装——主循环每次迭代
        // 的 stream() 收口报用量（取代只认 ModelCallEndEvent 的旧源）；子智能体
        // 复用同一实例（计量边界连续，子智能体轮次同归当轮用量）
        Model model = MeteredModel.wrap(ModelRegistry.resolve(modelString), modelString);
        // 项目工作区容器文件面（ProjectDev/ProjectReadOnly 共用；子智能体同面，#260）
        DockerExecFilesystem containerFs = switch (workspace) {
            case AgentWorkspace.ProjectDev dev -> new DockerExecFilesystem(dev.containerName());
            case AgentWorkspace.ProjectReadOnly ro -> new DockerExecFilesystem(ro.containerName());
            case AgentWorkspace.Local ignored -> null;
        };
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .stateStore(stateStore)
                .toolkit(toolkit)
                // 技能挂载位（#94）：按配置发放技能仓库——无技能挂载返回空集即框架
                // 不注入 <available_skills>；本平台工作区技能用 .platform/skills/（非
                // 框架 skills/），关闭框架工作区技能自动合成免无谓文件面往返
                .disableDefaultWorkspaceSkills();
        skillRepositorySupplier.skillRepositoriesFor(agentKey, workspace)
                .forEach(builder::skillRepository);
        // 平台中间件挂载位（#261 首件＝技能加载计数）：全形态挂观测中间件；
        // 委派声明的子智能体同挂（框架对 HarnessRuntimeMiddleware 不随实例拷贝
        // 传播，子级须显式再挂——与技能视图同款两处接线）
        List<MiddlewareBase> platformMiddlewares =
                middlewareSupplier.middlewaresFor(agentKey, workspace);
        platformMiddlewares.forEach(builder::middleware);
        // 委派位（#95 挂载 / #260 装配一等化）：声明转平台子智能体工厂（框架 2.0.1
        // declared 工厂只继承父级技能仓库，子智能体槽位视图接不进去——见工厂方法
        // javadoc）；无声明挂载返回空集即框架不注入 <available_subagents>
        subagentSupplier.subagentsFor(agentKey, workspace)
                .forEach(decl -> builder.subagentFactory(decl.getName(), subagentFactory(decl,
                        workspace, containerFs, model, toolkit, toolkitSupplier, stateStore,
                        skillRepositorySupplier, platformMiddlewares)));
        if (properties.getMaxIters() != null) {
            builder.maxIters(properties.getMaxIters());
        }
        // 压缩显式配置（#108 / ADR-0012）：触发阈值 + 压缩模型档，让压缩在
        // context_length_exceeded 前触发——取值与依据见 AgentscopeProperties。
        builder.compaction(compactionConfig(properties));
        switch (workspace) {
            case AgentWorkspace.Local local -> {
                if (local.root() != null) {
                    builder.workspace(local.root());
                }
                // 记忆钩子关闭（#109）：MemoryFlushMiddleware 的 fire-and-forget flush
                // 跑 boundedElastic 线程、看不到本轮计量 ThreadLocal——其 model.stream
                // 用量逃逸计量。记忆抽取仍由压缩链 flushBeforeCompact 同步承担（已计量），
                // 关此周期 flush 不丢抽取；亦与项目工作区同口径（记忆不进包）。
                builder.disableMemoryHooks();
            }
            case AgentWorkspace.ProjectDev dev -> projectSandbox(builder, containerFs);
            case AgentWorkspace.ProjectReadOnly ro -> projectSandbox(builder, containerFs)
                    // 只读面（#86 主智能体对话姿态）：另关内核文件与 shell 工具——
                    // 写面结构性不存在，项目事实的读取经业务侧只读工具集
                    // （ProfileToolkitSupplier）；委派是 run 内机制（#95 委派位只开在
                    // ProjectDev），主智能体永不委派——子智能体一并关闭
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableSubagents();
        }
        HarnessAgent agent = builder.build();
        realignBuiltinWebTools(toolkit, agent);
        return agent;
    }

    /**
     * 构建后工具面校正（#252）：上游 main 分支起框架 build() <b>无条件</b>注册自带
     * WebTools 两件（{@code web_fetch} 直抓 / {@code web_search} Tavily env-key
     * 直连；现行依赖 2.0.1 尚未带——本校正对缺失名幂等无害，是升级防御）——本平台
     * 出口统一走业务侧供数方（WebSearchProvider／ExternalContentFetcher 取数口，
     * 安全底线在取数口兑现），框架直连版结构性出局；且框架 {@code web_search} 与
     * 平台版<b>同名后注册即覆盖</b>（ToolRegistry 后写胜）。故对全部构建形态：
     * 先移除框架两件，平台版（toolkitFor 按开关装配、开才在）再注册回来——
     * 开＝平台版生效、关＝两版皆不在（「关即退出装配面」对同名框架件也成立）。
     */
    static void realignBuiltinWebTools(Toolkit platformToolkit, HarnessAgent agent) {
        Toolkit effective = agent.getToolkit();
        effective.removeTool("web_fetch");
        effective.removeTool("web_search");
        AgentTool webSearch = platformToolkit.getTool("web_search");
        if (webSearch != null) {
            effective.registerAgentTool(webSearch);
        }
    }

    /**
     * 项目沙箱公共装配（ProjectDev 与 ProjectReadOnly 共用）：名义根与容器内
     * 工作区根同形（路径规范化剥前缀后即工作区锚定形）、docker exec 文件面、
     * 关闭会写 harness 内脏进项目工作区的部件（memory：源码包是交付物，记忆文件
     * 不进包）。subagents 不在公共装配关——委派位（#95）只开在 ProjectDev，只读面
     * 在分支处单独关闭。文件面实例由调用方先建（子智能体工厂同面共享，#260）。
     */
    private static HarnessAgent.Builder projectSandbox(HarnessAgent.Builder builder,
            DockerExecFilesystem filesystem) {
        return builder
                .workspace(java.nio.file.Path.of(AgentWorkspace.ProjectDev.CONTAINER_ROOT))
                .abstractFilesystem(filesystem)
                .disableMemoryHooks()
                .disableMemoryTools();
    }

    /**
     * 子智能体构建工厂（#260 subagent 槽装配一等化——撤哨兵接真视图）：业务侧声明
     * 携带 WHAT（名/描述/正文/工具 allowlist/步数），平台不变式在此兑现 HOW——计量
     * 模型同实例、状态存储同库、记忆关闭（记忆不进包）、容器文件面同父级、无工作区
     * 技能；<b>技能面＝按子智能体键取装配视图</b>（{@link
     * AgentSkillRepositorySupplier#skillRepositoriesFor}——subagent 槽动态查库视图，
     * 指派/启停变更子智能体下一轮清单重建即生效、进行中 run 不定格，ADR-0021 语义
     * 与主装配同款）。
     *
     * <p>为何不走框架声明直配（{@code Builder.subagent(decl)}）：2.0.1 declared 工厂
     * 把<b>父级</b>技能仓库实例原样继承给子智能体、声明 {@code skills} 只做静态名
     * 过滤——无「子智能体自带仓库」通路，槽位视图接不进去（上游 {@code
     * SubagentDeclaration.skillRepositories} 未发布）。自定义工厂路径的两处代价记录
     * 在案：条目描述退化为名（2.0.1 {@code subagentFactory(name, factory)} 无描述参
     * ——路由由执行体工作协议点名 agent_id 承担，框架带描述参版本发布后可回归）；
     * 框架子智能体上下文段不随行（正文自含角色与回交纪律，通用英文段不复制以免
     * 跟版漂移）。{@code asLeafSubagent()} 包私有不可及，公开等价面 {@code
     * disableSubagents()} 封死子智能体再委派。</p>
     *
     * <p>容器文件面显式同父级：2.0.1 declared 工厂对 ISOLATED 子智能体不继承
     * {@code abstractFilesystem}（子级回落宿主机本地缺省——读代码/报告落盘与
     * {@code execute} 跑测试分裂在两个文件面）；本工厂给子智能体同一容器面，读/写/
     * 命令一致落项目 dev 容器，技能脚本物化（hasShell 槽位）同面可跑。隔离根＝父级
     * 工作区下 {@code agents/<name>/workspace/}（框架 ISOLATED 布局同款路径演算，
     * 已进非交付目录集——报告写隔离根不脏交付面）。</p>
     *
     * <p><b>子级工具面（#263 自荐三槽位齐开）</b>：声明白名单治理的（父级继承 ∪
     * 工具供应商按子智能体键发放的视图）——同件子键视图后写胜出（工具实例携带
     * 血统槽位：继承的 propose_skill 是执行体槽实例，子键视图覆写为 subagent 槽
     * 实例，草稿血统不串槽）。白名单摘名即退出子级面（视图自带也绕不过声明），
     * 镜像技能仓库的子键路由先例。</p>
     */
    static Function<String, Agent> subagentFactory(
            SubagentDeclaration declaration, AgentWorkspace workspace,
            DockerExecFilesystem containerFs, Model parentModel, Toolkit parentToolkit,
            AgentToolkitSupplier toolkitSupplier, AgentStateStore stateStore,
            AgentSkillRepositorySupplier skillRepositorySupplier,
            List<MiddlewareBase> platformMiddlewares) {
        List<AgentSkillRepository> childSkills =
                skillRepositorySupplier.skillRepositoriesFor(declaration.getName(), workspace);
        Toolkit childToolkit = childToolkit(parentToolkit, declaration, workspace,
                toolkitSupplier);
        java.nio.file.Path isolatedRoot = containerFs != null
                ? java.nio.file.Path.of(AgentWorkspace.ProjectDev.CONTAINER_ROOT)
                        .resolve("agents").resolve(declaration.getName()).resolve("workspace")
                : null;
        // 注册键即声明名（Function 入参恒同名），构建只认声明——单声明单工厂
        return name -> {
            HarnessAgent.Builder sub = HarnessAgent.builder()
                    .name(declaration.getName())
                    .description(declaration.getDescription())
                    .model(parentModel)
                    .toolkit(childToolkit)
                    .defaultSessionId(declaration.getName())
                    .maxIters(declaration.getSteps())
                    .sysPrompt(declaration.getInlineAgentsBody())
                    // 子智能体不委派（框架 asLeafSubagent 包私有的公开等价面）
                    .disableSubagents()
                    .disableDefaultWorkspaceSkills()
                    .stateStore(stateStore);
            if (isolatedRoot != null) {
                sub.workspace(isolatedRoot).abstractFilesystem(containerFs);
            }
            // 记忆同父级项目面口径关闭（记忆不进包；压缩链不走子智能体）
            sub.disableMemoryHooks().disableMemoryTools();
            childSkills.forEach(sub::skillRepository);
            // 平台中间件显式同挂（框架对 HarnessRuntimeMiddleware 不随拷贝传播）
            platformMiddlewares.forEach(sub::middleware);
            return sub.build();
        };
    }

    /**
     * 工具 allowlist 过滤（框架 declared 工厂同款语义）：非空 allowlist 只留列名件、
     * 空即全保留；在副本上摘除（父级工具集不动）。
     */
    private static Toolkit allowlistedToolkit(Toolkit parentToolkit, List<String> allowlist) {
        Toolkit toolkit = parentToolkit.copy();
        if (allowlist == null || allowlist.isEmpty()) {
            return toolkit;
        }
        toolkit.getToolNames().stream()
                .filter(toolName -> !allows(allowlist, toolName))
                .toList()
                .forEach(toolkit::removeTool);
        return toolkit;
    }

    /**
     * 子级工具面（#263 自荐三槽位齐开）：声明白名单治理的（父级继承 ∪ 工具供应商
     * 按子智能体键发放的视图）。子键视图件经白名单放行后<b>后写胜出</b>注册——
     * 工具实例携带血统槽位（继承的 propose_skill 是执行体槽实例，子键视图覆写为
     * subagent 槽实例）；白名单摘名即退出子级面（视图自带也绕不过声明治理）。
     * toolSpec 传 null：子智能体无运营配置行，且子键视图只发骨架件（无开关件）。
     */
    private static Toolkit childToolkit(Toolkit parentToolkit, SubagentDeclaration declaration,
            AgentWorkspace workspace, AgentToolkitSupplier toolkitSupplier) {
        List<String> allowlist = declaration.getTools();
        Toolkit toolkit = allowlistedToolkit(parentToolkit, allowlist);
        Toolkit ownView = toolkitSupplier.toolkitFor(declaration.getName(), workspace, null);
        for (String name : ownView.getToolNames()) {
            if (allows(allowlist, name)) {
                toolkit.registerAgentTool(ownView.getTool(name));
            }
        }
        return toolkit;
    }

    /** 声明白名单判定（同款语义单点）：缺省/空＝全放行，非空＝只放行列名件。 */
    private static boolean allows(List<String> allowlist, String toolName) {
        return allowlist == null || allowlist.isEmpty() || allowlist.contains(toolName);
    }

    /**
     * 压缩配置装配：把配置的触发阈值 + 压缩模型档落到 {@link CompactionConfig}，空值
     * 回框架缺省（保留量/裁剪亦走框架缺省）——取值与依据见 {@link AgentscopeProperties}
     * （#108 / ADR-0012）。压缩（摘要）模型同样经 {@link MeteredModel} 包装（#109）：
     * 压缩链的摘要与 flushBeforeCompact 记忆抽取共用该模型实例直调 stream()，包装
     * 后用量归入当前轮计量（flash 档与主模型 pro 档各自归位）。
     */
    static CompactionConfig compactionConfig(AgentscopeProperties properties) {
        CompactionConfig.Builder config = CompactionConfig.builder();
        if (properties.getCompactionTriggerTokens() != null) {
            config.triggerTokens(properties.getCompactionTriggerTokens());
        }
        String compactionModel = properties.getCompactionModel();
        if (compactionModel != null && !compactionModel.isBlank()) {
            config.model(MeteredModel.wrap(ModelRegistry.resolve(compactionModel), compactionModel));
        }
        return config.build();
    }
}
