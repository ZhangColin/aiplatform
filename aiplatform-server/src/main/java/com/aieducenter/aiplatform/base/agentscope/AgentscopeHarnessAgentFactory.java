package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
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
 * <p>工作区四形态（{@link AgentWorkspace}）：{@link AgentWorkspace.Local Local}
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
 * （写面结构性关闭，主智能体永不读写沙箱代码——PRD 写入走业务侧 savePrd）；
 * {@link AgentWorkspace.ProjectDesign ProjectDesign} 项目工作区设计面（#289
 * 设计执行体姿态，ADR-0025 无 shell）——文件工具保留、另关内核 shell 与委派
 * （写文件件在、命令执行结构性不存在）。</p>
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
                ModelChannel modelChannel, AgentWorkspace workspace, String agentKey,
                String toolSpec);
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
        this(stateStore, toolkitSupplier, (name, sysPrompt, modelString, modelChannel, workspace,
                agentKey, toolSpec) ->
                buildAgent(stateStore, toolkitSupplier, skillRepositorySupplier, subagentSupplier,
                        middlewareSupplier,
                        name, sysPrompt, modelString, modelChannel, workspace, agentKey, toolSpec,
                        properties));
    }

    AgentscopeHarnessAgentFactory(AgentStateStore stateStore, AgentToolkitSupplier toolkitSupplier,
            AgentBuilder builder) {
        this.stateStore = stateStore;
        this.toolkitSupplier = toolkitSupplier;
        this.builder = builder;
    }

    public HarnessAgent obtain(String name, String sysPrompt, String modelString,
            ModelChannel modelChannel, AgentWorkspace workspace, String agentKey, String toolSpec) {
        // sysPrompt/workspace/agentKey/toolSpec 明文入键（不用 hashCode：碰撞会把不同
        // 人格/工作区/工具面的 agent 当同规格静默复用）；toolSpec 是工具面规格
        // （#252 增强工具开关）：工具集构建时固化（Toolkit 静态注册、无技能线的
        // 动态视图缝），规格变＝实例变——开关变更下一轮命令构建即新装配；
        // modelChannel.fingerprint() 是生效配置指纹（ADR-0031）：同模型串下换
        // key/换地址＝指纹变＝新实例——守「改配置即生效、进行中 run 不定格」
        String key = name + "|" + modelString + "|"
                + (modelChannel == null ? "" : modelChannel.fingerprint()) + "|"
                + sysPrompt + "|" + workspace.identity()
                + "|" + agentKey + "|" + (toolSpec == null ? "" : toolSpec);
        return agents.computeIfAbsent(key,
                k -> builder.build(name, sysPrompt, modelString, modelChannel, workspace,
                        agentKey, toolSpec));
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
            String name, String sysPrompt, String modelString, ModelChannel modelChannel,
            AgentWorkspace workspace, String agentKey, String toolSpec,
            AgentscopeProperties properties) {
        Toolkit toolkit = toolkitSupplier.toolkitFor(agentKey, workspace, toolSpec);
        // 模型流层平台包装（#109 计量 + #321 断流自动重试）：主模型解析即关内层
        // 缺省重试（见 innerRetryDisabledOptions）、经 streamLayer 包装——重试
        // 内圈（429/5xx 零发射失败流层接续）、计量外圈（主循环每次迭代的
        // stream() 收口报用量，取代只认 ModelCallEndEvent 的旧源）；子智能体
        // 复用同一实例（计量边界连续，子智能体轮次同归当轮用量）。通道注入件
        // （ADR-0031）随解析进创建上下文：baseUrl/apiKey/enableThinking 注入
        // SDK（覆盖依赖库默认地址与环境变量），前缀路由到平台注册工厂
        // （volcengine）或内置 provider（deepseek 等）
        Model model = streamLayer(
                ModelRegistry.resolve(modelString, creationContextOf(modelChannel)),
                modelString, properties);
        // 项目工作区容器文件面（ProjectDev/ProjectReadOnly 共用；子智能体同面，#260）
        DockerExecFilesystem containerFs = switch (workspace) {
            case AgentWorkspace.ProjectDev dev -> new DockerExecFilesystem(dev.containerName());
            case AgentWorkspace.ProjectReadOnly ro -> new DockerExecFilesystem(ro.containerName());
            case AgentWorkspace.ProjectDesign design -> new DockerExecFilesystem(design.containerName());
            case AgentWorkspace.Local ignored -> null;
        };
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .stateStore(stateStore)
                .toolkit(toolkit)
                // 技能挂载位（#94）：按配置发放技能仓库——无技能挂载返回空集即框架
                // 不注入 <available_skills>；平台技能来源三途（内置 classpath/安装库/
                // 自产库）均非框架工作区目录 skills/（预留位已删，ADR-0022），关闭
                // 框架工作区技能自动合成免无谓文件面往返
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
                // 另起订阅树（boundedElastic 线程）、不在本轮计量订阅链上——其
                // model.stream 用量逃逸计量。记忆抽取仍由压缩链 flushBeforeCompact
                // 同步承担（已计量），关此周期 flush 不丢抽取；亦与项目工作区同口径
                // （记忆不进包）。
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
            case AgentWorkspace.ProjectDesign design -> projectSandbox(builder, containerFs)
                    // 设计面（#289 设计执行体姿态，ADR-0025 无 shell）：内核文件
                    // 工具（写文件件）保留——设计稿落工作区；shell 与委派结构性
                    // 关闭——写稿无需命令、设计会话不委派（信任面收窄同主智能体）
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
     * 工具供应商按子智能体键发放的视图 ∪ 框架 build() 注册的内置件——后者在合并
     * 之后整体注册，#266 起构建后按声明后置再滤一道，构建收口后子级工具面 ⊆ 声明
     * ［运行时另挂的仅技能装载件 load_skill_through_path——中间件每轮重挂，归技能
     * 面治理非工具面声明］）——同件子键视图后写胜出（工具实例携带血统槽位：继承的
     * propose_skill 是执行体槽实例，子键视图覆写为 subagent 槽实例，草稿血统不串槽）。
     * 白名单摘名即退出子级面（视图自带也绕不过声明），镜像技能仓库的子键路由先例。</p>
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
            HarnessAgent child = sub.build();
            // #266 构建后置收口：框架 build() 在子级工具面合并之后整体注册自带件
            // （内核文件面含 edit_file、wait_async_results、未来版 WebTools）——内置
            // 件同受声明 allowlist 治理（镜像 realignBuiltinWebTools 后置校正先例，
            // build() 之后才是完整事实面；技能装载件 load_skill_through_path 由中间
            // 件每轮运行时挂载，不在此处、不受影响）
            stripUndeclared(child.getToolkit(), declaration.getTools());
            return child;
        };
    }

    /**
     * 工具 allowlist 过滤（框架 declared 工厂同款语义）：非空 allowlist 只留列名件、
     * 空即全保留；在副本上摘除（父级工具集不动）。
     */
    private static Toolkit allowlistedToolkit(Toolkit parentToolkit, List<String> allowlist) {
        Toolkit toolkit = parentToolkit.copy();
        stripUndeclared(toolkit, allowlist);
        return toolkit;
    }

    /** 就地摘除非列名件（声明白名单机械半边，两处消费：合并前副本＋#266 构建后收口）。 */
    private static void stripUndeclared(Toolkit toolkit, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return;
        }
        toolkit.getToolNames().stream()
                .filter(toolName -> !allows(allowlist, toolName))
                .toList()
                .forEach(toolkit::removeTool);
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
     * 模型流层平台包装（#321 装配单点）：{@code MeteredModel(RetryingModel(raw))}
     * ——重试内圈（断流自动重试，{@link RetryingModel}）、计量外圈（#109
     * {@link MeteredModel}）。序有讲究：计量在外、重试在内——护栏不变式（任何
     * 发射只来自末次尝试）下整流收口上报与单次直调同形，重试不破坏既有计量
     * 路径；重试的重订阅也无需跨线程传播计量上下文——计量 scope 经 Reactor 订阅链
     * {@code Context} 传递（#324），重试在内圈重订阅不跨订阅链、raw 模型无计量依赖。
     * 主模型与压缩模型共用此装配。
     */
    static Model streamLayer(Model resolved, String modelString, AgentscopeProperties properties) {
        return MeteredModel.wrap(RetryingModel.wrap(resolved, properties), modelString);
    }

    /**
     * 内层（框架模型内建）重试关闭的执行配置（#321）：agentscope 2.0.1 的
     * {@code OpenAIChatModel.Builder.build()} 经 {@code ensureDefaultExecutionConfig}
     * 把 {@code ExecutionConfig.MODEL_DEFAULTS} 烧进 configuredOptions——内层
     * retryWhen 默认开启，但（a）平台无控制面（次数/backoff 不可配），（b）无
     * 已发块护栏——中途断流重订阅重放已消费块、下游逐 chunk 累积即回复文本
     * 重复拼接，（c）耗尽后上抛 reactor {@code RetryExhaustedException}——真错误
     * 被重试机制的皮裹走（外层尝试环 #221 错误现场拿到的是重试耗尽文案而非
     * 模型错误）。此处显式供值（presence 即跳过缺省注入）关内层重试
     * （{@code maxAttempts=1}）、保留缺省 inter-signal 超时（{@code timeout}
     * 取 MODEL_DEFAULTS 同值——除重试维度外不改既有行为，流时长上限属 #322），
     * 流层重试由 {@link RetryingModel} 平面独占（可配、有护栏、分类接线、耗尽
     * 如实上抛）。
     */
    static GenerateOptions innerRetryDisabledOptions() {
        return GenerateOptions.builder()
                .executionConfig(ExecutionConfig.builder()
                        .timeout(ExecutionConfig.MODEL_DEFAULTS.getTimeout())
                        .maxAttempts(1)
                        .build())
                .build();
    }

    /**
     * 解析上下文（#321）：携内层重试关闭的执行配置进模型解析——生成方
     * （DeepSeekModelProvider 等）把 context 的 GenerateOptions 组件并进
     * configuredOptions，模型实例的全部调用路径（主循环 reasoning / 压缩摘要 /
     * 记忆抽取直调 stream()）一致生效；cachePolicy 缺省 DEFAULT 不进注册表缓存
     * （模型解析本就只发生在 agent 构建一次，实例缓存由本工厂承担）。
     */
    private static final ModelCreationContext INNER_RETRY_DISABLED =
            ModelCreationContext.builder()
                    .component(GenerateOptions.class, innerRetryDisabledOptions())
                    .build();

    /**
     * 生效解析上下文（ADR-0031 通道注入腿）：通道件非空＝档位解析产物——
     * baseUrl/apiKey/enableThinking 注入创建上下文（平台 volcengine 工厂与内置
     * provider 都从标准字段读，key 不落环境变量）；空＝无通道语境（一次性调用
     * 面的配置缺省串），维持仅内层重试关闭的既有上下文。
     */
    static ModelCreationContext creationContextOf(ModelChannel modelChannel) {
        if (modelChannel == null) {
            return INNER_RETRY_DISABLED;
        }
        return INNER_RETRY_DISABLED.toBuilder()
                .apiKey(modelChannel.apiKey())
                .baseUrl(modelChannel.baseUrl())
                .enableThinking(modelChannel.thinkingEnabled())
                .build();
    }

    /**
     * 压缩配置装配：把配置的触发阈值 + 压缩模型档落到 {@link CompactionConfig}，空值
     * 回框架缺省（保留量/裁剪亦走框架缺省）——取值与依据见 {@link AgentscopeProperties}
     * （#108 / ADR-0012）。压缩（摘要）模型同经 {@link #streamLayer} 包装（#109
     * 计量 + #321 断流重试，内层重试同关）：压缩链的摘要与 flushBeforeCompact
     * 记忆抽取共用该模型实例直调 stream()，包装后用量归入当前轮计量（flash 档与
     * 主模型 pro 档各自归位）、断流同获流层重试。
     */
    static CompactionConfig compactionConfig(AgentscopeProperties properties) {
        CompactionConfig.Builder config = CompactionConfig.builder();
        if (properties.getCompactionTriggerTokens() != null) {
            config.triggerTokens(properties.getCompactionTriggerTokens());
        }
        String compactionModel = properties.getCompactionModel();
        if (compactionModel != null && !compactionModel.isBlank()) {
            config.model(streamLayer(
                    ModelRegistry.resolve(compactionModel, INNER_RETRY_DISABLED), compactionModel,
                    properties));
        }
        return config.build();
    }
}
