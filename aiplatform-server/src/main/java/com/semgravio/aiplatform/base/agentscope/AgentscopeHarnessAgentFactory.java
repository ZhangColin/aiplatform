package com.semgravio.aiplatform.base.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HarnessAgent 构建工厂：agent 无状态（per-session 靠 RuntimeContext 寻址），同规格
 * （name + sysPrompt + model + workspace）构建一次、进程内复用；容器关闭时统一释放
 * （HarnessAgent 是 AutoCloseable）。
 *
 * <p>工作区五形态（{@link AgentWorkspace}）：{@link AgentWorkspace.Local Local}
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
 * （写文件件在、命令执行结构性不存在）；
 * {@link AgentWorkspace.ProjectKernel ProjectKernel} 项目工作区生成内核形态
 * （#595 内核装配，ADR-0054）——容器/内脏关/memory 关同 ProjectDev，提示词＝
 * {@link KernelSystemPrompt} 权威版本（经 {@link #obtainKernel} 装配，直携外来
 * 提示词被结构拒绝）、工具面＝恰四件（{@link KernelTools}）、循环五条语义结构性
 * 挂载（#596 {@link KernelLoopSemantics}）、不挂技能、不挂子智能体委派；会话状态
 * 存储、(userId,sessionId) 串行、内核重试关停、上下文压缩全部沿用现有装配
 * （一行不改）。</p>
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
                ModelChannel modelChannel, ModelCandidateQueue modelQueue,
                EdgeModelSupplier.EdgeModel compaction,
                EdgeModelSupplier.EdgeModel subagent, AgentWorkspace workspace, String agentKey,
                String toolSpec);
    }

    private final ConcurrentHashMap<String, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final AgentBuilder builder;
    private final AgentStateStore stateStore;
    private final AgentToolkitSupplier toolkitSupplier;
    private final EdgeModelSupplier edgeModels;
    private final ModelSwitchTraceSink switchTraceSink;

    /** 内核内置件名（wait_async_results）与坑参数名（timeout_seconds，#482）。 */
    private static final String WAIT_ASYNC_RESULTS_TOOL = "wait_async_results";
    private static final String TIMEOUT_SECONDS_PARAM = "timeout_seconds";
    private static final String REQUIRED_SCHEMA_KEY = "required";

    /**
     * 生成内核形态的智能体名/装配键（#595 名字腿；#601 起兼作内核命令的装配键——
     * 平台中间件按配置键挂、不看工作区形态：内核命令携本键（非 executor）结构性
     * 挡掉决策提示／verify 自决旧中间件，#598 警示第一条），钉死免漂。
     */
    public static final String KERNEL_AGENT_NAME = "generation-kernel";

    @Autowired
    public AgentscopeHarnessAgentFactory(AgentStateStore stateStore,
            AgentToolkitSupplier toolkitSupplier, AgentSkillRepositorySupplier skillRepositorySupplier,
            AgentSubagentSupplier subagentSupplier, AgentMiddlewareSupplier middlewareSupplier,
            EdgeModelSupplier edgeModels, AgentscopeProperties properties,
            ModelSwitchTraceSink switchTraceSink) {
        this(stateStore, toolkitSupplier, (name, sysPrompt, modelString, modelChannel, modelQueue,
                compaction, subagent, workspace, agentKey, toolSpec) ->
                buildAgent(stateStore, toolkitSupplier, skillRepositorySupplier, subagentSupplier,
                        middlewareSupplier, switchTraceSink,
                        name, sysPrompt, modelString, modelChannel, modelQueue, compaction,
                        subagent, workspace, agentKey, toolSpec, properties),
                edgeModels, switchTraceSink);
    }

    AgentscopeHarnessAgentFactory(AgentStateStore stateStore, AgentToolkitSupplier toolkitSupplier,
            AgentBuilder builder, EdgeModelSupplier edgeModels) {
        this(stateStore, toolkitSupplier, builder, edgeModels, null);
    }

    AgentscopeHarnessAgentFactory(AgentStateStore stateStore, AgentToolkitSupplier toolkitSupplier,
            AgentBuilder builder, EdgeModelSupplier edgeModels, ModelSwitchTraceSink switchTraceSink) {
        this.stateStore = stateStore;
        this.toolkitSupplier = toolkitSupplier;
        this.builder = builder;
        this.edgeModels = edgeModels;
        this.switchTraceSink = switchTraceSink;
    }

    public HarnessAgent obtain(String name, String sysPrompt, String modelString,
            ModelChannel modelChannel, ModelCandidateQueue modelQueue, AgentWorkspace workspace,
            String agentKey, String toolSpec) {
        // 压缩配置随装配解析（#366 边缘点收编：compaction 配置库值优先缺省 → 解析
        // 单点，每次 obtain 动态查——后台改档下一轮命令构建即生效）
        EdgeModelSupplier.EdgeModel compaction = edgeModels.compactionModel();
        // 委派子智能体配置随装配解析（#424 片轻验）：null＝随父级（执行体生效档
        // ——含路由升档），非空即独立档；同入实例缓存键指纹（改档即新实例）
        EdgeModelSupplier.EdgeModel subagent = edgeModels.subagentModel();
        // sysPrompt/workspace/agentKey/toolSpec 明文入键（不用 hashCode：碰撞会把不同
        // 人格/工作区/工具面的 agent 当同规格静默复用）；toolSpec 是工具面规格
        // （#252 增强工具开关）：工具集构建时固化（Toolkit 静态注册、无技能线的
        // 动态视图缝），规格变＝实例变——开关变更下一轮命令构建即新装配；
        // 生效配置指纹（ADR-0031）：同模型串下换 key/换地址＝指纹变＝新实例——守
        // 「改配置即生效、进行中 run 不定格」。#557 起队列形优先：modelQueue 非
        // 空时整队列指纹（任一候选变＝新实例——failover 面对全队列成立），双字段
        // 形（modelChannel.fingerprint()）为无队列测试形保留；
        // 压缩配置同入指纹（#366）：改压缩档即新实例；子智能体配置同律（#424）
        String channelFingerprint = modelQueue != null ? modelQueue.fingerprint()
                : (modelChannel == null ? "" : modelChannel.fingerprint());
        String key = name + "|" + modelString + "|" + channelFingerprint + "|"
                + sysPrompt + "|" + workspace.identity()
                + "|" + agentKey + "|" + (toolSpec == null ? "" : toolSpec)
                // 边缘配置（压缩/子智能体）整队列指纹：#557 队列化后任一候选变＝新实例
                + "|cx=" + compaction.queue().fingerprint()
                + "|sg=" + (subagent == null ? "" : subagent.queue().fingerprint());
        return agents.computeIfAbsent(key,
                k -> builder.build(name, sysPrompt, modelString, modelChannel, modelQueue,
                        compaction, subagent, workspace, agentKey, toolSpec));
    }

    /**
     * 生成内核装配（#595，ADR-0054／#593 用户故事 9/11）：系统提示＝
     * {@link KernelSystemPrompt} 权威版本（住代码、调用方不可覆盖——改提示词＝
     * 改内核＝开 issue）、工具面＝恰四件（{@link KernelTools}）、不挂技能、不挂
     * 子智能体委派；循环五条语义结构性挂载（#596 {@link KernelLoopSemantics}——
     * 截断/空回复即轮次结束＋收尾如实告知原因、同参重复 3/5/8 只提醒不拦截、到护栏
     * 上限关工具＋MAX_STEPS_PROMPT 如实汇报；一轮不调工具即结束由框架原生兑现）；
     * 工作区＝项目 dev 沙箱（容器文件面同 {@link AgentWorkspace.ProjectDev}），
     * 会话状态存储、(userId,sessionId) 串行、内核重试关停、上下文压缩沿用现有
     * 装配（{@link #buildAgent} 内核分支，一行不改；压缩的触发规则自 #598 起照
     * DeepSeek Harness 口径，见 {@link #kernelCompactionConfig}）。模型串/通道/候选队列照旧
     * 由调用方经供给链出口携入（#593 用户故事 6/7——供给链不绕开）。护栏三数值
     * （{@code app.agentscope.kernel-guard-*}：300 轮／45 分钟／60M prompt，到顶
     * 关工具如实汇报）与输出上限开关（{@code kernel-max-output-tokens}，每次请求
     * 携带、默认 32768）已接线（#597）；收尾判据（build✓＋探活✓）与开发服务器常驻
     * 守卫在业务侧 KernelClosingCriteria／KernelServingGuard（#597）。
     *
     * <p>守卫：直用 {@link AgentWorkspace.ProjectKernel} 工作区、绕过本入口携
     * 外来提示词走 {@link #obtain} 被 {@link #buildAgent} 结构拒绝
     * （IllegalArgumentException）。</p>
     */
    public HarnessAgent obtainKernel(String modelString, ModelChannel modelChannel,
            ModelCandidateQueue modelQueue, AgentWorkspace.ProjectKernel workspace,
            String agentKey) {
        return obtain(KERNEL_AGENT_NAME, KernelSystemPrompt.text(), modelString,
                modelChannel, modelQueue, workspace, agentKey, null);
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
            ModelSwitchTraceSink switchTraceSink,
            String name, String sysPrompt, String modelString, ModelChannel modelChannel,
            ModelCandidateQueue modelQueue,
            EdgeModelSupplier.EdgeModel compaction, EdgeModelSupplier.EdgeModel subagent,
            AgentWorkspace workspace, String agentKey,
            String toolSpec, AgentscopeProperties properties) {
        boolean kernelForm = workspace instanceof AgentWorkspace.ProjectKernel;
        // 提示词权威守卫（#595）：内核形态只收 KernelSystemPrompt 权威版本——改提示词＝
        // 改内核＝开 issue；直用内核工作区携外来提示词属编程错误，失败要快要响
        if (kernelForm && !KernelSystemPrompt.text().equals(sysPrompt)) {
            throw new IllegalArgumentException("生成内核形态的提示词必须取 KernelSystemPrompt "
                    + "权威版本（改提示词＝改内核＝开 issue，#595）——请经 obtainKernel 装配");
        }
        // 内核形态工具面＝恰四件（#594 KernelTools，base 自产）——结构性不发放旧
        // 工具面（AgentToolkitSupplier 是旧按槽位工具面的业务装配缝，内核不消费）；
        // 其余形态照旧经业务供应商按配置发放
        Toolkit toolkit = kernelForm
                ? kernelToolkit(((AgentWorkspace.ProjectKernel) workspace).containerName())
                : toolkitSupplier.toolkitFor(agentKey, workspace, toolSpec);
        // 模型流层平台包装（#109 计量 + #321 断流自动重试）：主模型解析即关内层
        // 缺省重试（见 innerRetryDisabledOptions）、经 streamLayer 包装——重试
        // 内圈（429/5xx 零发射失败流层接续）、计量外圈（主循环每次迭代的
        // stream() 收口报用量，取代只认 ModelCallEndEvent 的旧源）；子智能体
        // 复用同一实例（计量边界连续，子智能体轮次同归当轮用量）。通道注入件
        // （ADR-0031）随解析进创建上下文：baseUrl/apiKey/enableThinking 注入
        // SDK（覆盖依赖库默认地址与环境变量），前缀路由到平台注册工厂
        // （volcengine）或内置 provider（deepseek 等）。#557 起队列形：命令携
        // modelQueue（配置解析的整队列产物）时主模型经 failoverLayer 装配
        // （多候选＝FailoverModel 链式切换、单候选＝streamLayer 直装行为不变）
        Model model = modelQueue != null
                ? failoverLayer(modelQueue, properties, switchTraceSink)
                : streamLayer(
                        ModelRegistry.resolve(modelString, creationContextOf(modelChannel)),
                        modelString, vendorOf(modelChannel), properties);
        // 委派子智能体配置（#424 片轻验）：覆盖档独立解析同款流层包装（计量外圈＋
        // 断流重试同构；#557 起整队列 failover 同构）；null＝随父级实例（缺省同
        // 执行体档——含路由升档，计量边界连续的既有口径不变）
        Model subagentModel = subagent == null ? model
                : failoverLayer(subagent.queue(), properties, switchTraceSink);
        // 项目工作区容器文件面（ProjectDev/ProjectReadOnly/ProjectDesign/ProjectKernel 共用；
        // 子智能体同面，#260）
        DockerExecFilesystem containerFs = switch (workspace) {
            case AgentWorkspace.ProjectDev dev -> new DockerExecFilesystem(dev.containerName());
            case AgentWorkspace.ProjectReadOnly ro -> new DockerExecFilesystem(ro.containerName());
            case AgentWorkspace.ProjectDesign design -> new DockerExecFilesystem(design.containerName());
            case AgentWorkspace.ProjectKernel kernel -> new DockerExecFilesystem(kernel.containerName());
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
                .disableDefaultWorkspaceSkills()
                // pending tool 恢复开（#431）：会话（PG 槽位）带上次中断遗留的无结果
                // tool_use 续跑时，框架缺省炸 IllegalStateException（Pending tool
                // calls exist without results——生产 verify 反复实测），整轮尝试被
                // 吞、靠 run 重试环自愈分钟级。开启＝resume 时对无结果 pending 调用
                // 自动补 ERROR 结果继续走（模型看见工具失败事实自行续作，WARN 遥测
                // 落框架日志）——最小修复，不叠旁路恢复层（#412 递减效应引证）
                .enablePendingToolRecovery(true)
                // 会话誊本写面关停（#553 升级 2.0.5 最小适配）：2.0.5 起框架恒挂
                // TranscriptMiddleware——每轮收尾把对话誊本追加写 agents/<id>/
                // sessions/*.jsonl（容器姿态经 abstractFilesystem 落工作区卷、Local
                // 姿态纯宿主 NIO），#379 已裁决该形态的宿主/工作区 jsonl 是「无读者
                // 孤儿副本」（会话正本在 PG cat_agent_state）；平台无 SessionSearch/
                // Operate 消费面，显式关停延续既有决议
                .disableTranscript()
                // 内层重试关闭·内核旋钮（#555 / ADR-0053 ①）：平台 INNER_RETRY_DISABLED
                // 压的 maxAttempts=1 只到模型 configuredOptions——内核 ReActAgent 无条件
                // 烧 modelConfig(maxRetries 缺省 3) 进每次调用的 callOptions
                // （框架生成调用选项——与平台「模型配置」域概念〔ModelConfig/
                // config_key，#554 改名〕无涉，仅字面撞车），
                // ModelUtils.applyTimeoutAndRetry 的 mergeOptions(callOptions,
                // configuredOptions) 第一参字段级优先压过之（且 retryOn=null 时内核挂
                // 恒真 filter：401 也重试、耗尽 RetryExhaustedException 裹走真错误——
                // 上游 open bug #2861）。旋钮经 modelConfig.maxRetries→callOptions
                // executionConfig 与平台关闭一致，内核重试面＝1：401 类真错误单发裸上抛
                // （外层分类路由见 #552 ② failover；框架压缩中间件另有吞模型错误重放
                // 一轮的独立缺陷，#561）。子智能体工厂同款（subagentFactory）
                .maxRetries(1);
        // 技能挂载位（#94）：按配置发放技能仓库——无技能挂载返回空集即框架
        // 不注入 <available_skills>；平台技能来源三途（内置 classpath/安装库/
        // 自产库）均非框架工作区目录 skills/（预留位已删，ADR-0022），关闭
        // 框架工作区技能自动合成免无谓文件面往返。
        // 内核形态（#595）结构性跳过——「不挂技能」不靠业务供应商返回空集兑现
        if (!kernelForm) {
            skillRepositorySupplier.skillRepositoriesFor(agentKey, workspace)
                    .forEach(builder::skillRepository);
        }
        // 平台中间件挂载位（#261 首件＝技能加载计数）：全形态挂观测中间件；
        // 委派声明的子智能体同挂（框架对 HarnessRuntimeMiddleware 不随实例拷贝
        // 传播，子级须显式再挂——与技能视图同款两处接线）
        List<MiddlewareBase> platformMiddlewares =
                middlewareSupplier.middlewaresFor(agentKey, workspace);
        platformMiddlewares.forEach(builder::middleware);
        // 委派位（#95 挂载 / #260 装配一等化）：声明转平台子智能体工厂（框架 2.0.1
        // declared 工厂只继承父级技能仓库，子智能体槽位视图接不进去——见工厂方法
        // javadoc）；无声明挂载返回空集即框架不注入 <available_subagents>。
        // 内核形态（#595）结构性跳过——「不挂子智能体委派」不靠业务供应商返回空集兑现
        if (!kernelForm) {
            subagentSupplier.subagentsFor(agentKey, workspace)
                    .forEach(decl -> builder.subagentFactory(decl.getName(), subagentFactory(decl,
                            workspace, containerFs, model, subagentModel, toolkit, toolkitSupplier,
                            stateStore, skillRepositorySupplier, platformMiddlewares)));
        }
        // 内核循环五条语义（#596，ADR-0054／#590 最终修订）结构性挂载：一轮不调工具
        // 即结束（框架原生兑现）、截断/空回复＝轮次结束＋收尾如实告知原因、同参重复
        // 3/5/8 只提醒不拦截、到护栏上限关工具＋如实汇报——仅内核形态挂载，旧形态循环
        // 行为一行不动（机制与兑现位见 KernelLoopSemantics）。护栏三数值（#597，
        // 300 轮／45 分钟／60M prompt 运营兜底、非工作语义）从配置接线：轮次显式置空/
        // 非正回落缺省（SDK maxIters 互锁需具体轮数；显式置空不产出「无限轮」形态）
        int kernelGuardRounds = KernelLoopSemantics.GUARD_ROUNDS;
        if (kernelForm) {
            Integer configuredGuardRounds = properties.getKernelGuardRounds();
            if (configuredGuardRounds != null && configuredGuardRounds > 0) {
                kernelGuardRounds = configuredGuardRounds;
            }
            builder.middleware(new KernelLoopSemantics(kernelGuardRounds,
                    properties.getKernelGuardDuration(), properties.getKernelGuardPromptTokens()));
        }
        if (properties.getMaxIters() != null) {
            builder.maxIters(properties.getMaxIters());
        }
        if (kernelForm) {
            // 内核形态 SDK maxIters 抬到循环护栏之上（#596）——压过上方
            // properties 写入（内核形态不消费该配置项）：到顶语义（关工具＋
            // MAX_STEPS_PROMPT 如实汇报）由 KernelLoopSemantics 兑现，SDK
            // summarizing（自带 heuristics 提示句、无工具）不得抢先；抬升值随
            // 护栏轮次配置走（#597——护栏三数值配置化的互锁腿）
            builder.maxIters(kernelGuardRounds + 1);
            // 输出上限开关（#597，ADR-0054／#593 用户故事 10）：每次模型请求携带
            // max_tokens（ReActAgent 把 agent 级 generateOptions 作每次调用的
            // callOptions 基底——模型/provider 侧映射 max_tokens 请求体，OpenAI 系
            // formatter 同口径）；默认 32768、运营可调（调低换速度、代价截断风险
            // 回来——#590 授权取舍）；null＝不注入（回落模型/provider 缺省）
            if (properties.getKernelMaxOutputTokens() != null) {
                builder.generateOptions(GenerateOptions.builder()
                        .maxTokens(properties.getKernelMaxOutputTokens())
                        .build());
            }
        }
        // 压缩显式配置（#108 / ADR-0012）：触发阈值 + 压缩配置解析产物（#366 边缘
        // 点收编——配置键经 EdgeModelSupplier 反转读配置面），让压缩在
        // context_length_exceeded 前触发；阈值取值依据见 AgentscopeProperties。
        builder.compaction(kernelForm
                ? kernelCompactionConfig(properties, compaction, switchTraceSink,
                        model.getContextWindowSize(), kernelReservedOutputTokens(properties))
                : compactionConfig(properties, compaction, switchTraceSink));
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
            case AgentWorkspace.ProjectKernel kernel -> projectSandbox(builder, containerFs)
                    // 生成内核面（#595，ADR-0054）：框架文件面/shell/WebTools 结构性
                    // 不注册——防框架 build() 后写胜覆盖内核四件同名件（read/write/
                    // edit）；委派关（不挂子智能体委派）。内核四件经 toolkit 直装
                    // （见上 kernelToolkit），记忆关/memory 工具关随 projectSandbox
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableWebTools()
                    .disableSubagents();
        }
        HarnessAgent agent = builder.build();
        realignBuiltinWebTools(toolkit, agent);
        // #482：内置等待屏障件 schema 覆写（见 relaxWaitBarrierTimeoutSchema）
        relaxWaitBarrierTimeoutSchema(agent.getToolkit());
        // 内核工具面收口（#595）：恰四件不变量——构建后摘除一切非四件（框架内置
        // wait_async_results 等 messageBus 衍生件无 builder 开关，只能后置摘）；
        // 摘完恒等断言归 KernelAgentAssemblyTest 钉守
        if (kernelForm) {
            stripNonKernelTools(agent.getToolkit());
        }
        // #363：项目容器姿态抑制框架宿主物化面（见 suppressHostSkillStaging——
        // 容器根 /workspace 对纯 NIO 物化是宿主路径，写了必错处）
        if (containerFs != null) {
            suppressHostSkillStaging(agent);
        }
        return agent;
    }

    /**
     * 内核工具面装配（#595）：{@link KernelTools} 四件直装新 Toolkit——旧工具面
     * 供应商（{@link AgentToolkitSupplier}）与技能面、委派位对内核形态结构性
     * 不消费（见 {@link #buildAgent} 内核分支）。
     */
    private static Toolkit kernelToolkit(String containerName) {
        Toolkit toolkit = new Toolkit();
        KernelTools.forContainer(containerName).forEach(toolkit::registerAgentTool);
        return toolkit;
    }

    /**
     * 内核工具面收口（#595）：构建后工具面恰四件（{@link KernelTools#NAMES}）——
     * 一切非四件条目摘除（框架内置 wait_async_results/web 对/MCP 衍生件；框架
     * 文件面与 shell 已在前置 builder 开关结构性不注册，此处是收口兜底的第二道）。
     * 镜像 {@link #realignBuiltinWebTools}/#266 stripUndeclared 构建后置校正先例。
     */
    private static void stripNonKernelTools(Toolkit toolkit) {
        toolkit.getToolNames().stream()
                .filter(name -> !KernelTools.NAMES.contains(name))
                .toList()
                .forEach(toolkit::removeTool);
    }

    /**
     * 技能物化面宿主抑制（#363）：框架 {@code HarnessSkillMiddleware} 每轮
     * {@code onSystemPrompt} 经 {@code MarketplaceStager} 把技能（含空 resources 的
     * a-only 技能——createDirectories 无条件先行）物化到 {@code workspaceRoot/
     * .skills-cache/}。物化面是<b>纯宿主 NIO</b>（不走 abstractFilesystem），
     * 而项目容器姿态交给它的 workspace 根是<b>容器路径</b> {@code /workspace}
     * （{@link #projectSandbox}）——写必错处：宿主不可写即每轮 WARN（#363 现场：
     * {@code Failed to stage skill ... Read-only file system}），可写则静默落
     * 垃圾（本机实测 {@code C:\workspace\.skills-cache\...}，容器内并不存在）。
     *
     * <p>抑制＝构建后把中间件的 {@code stager} 字段置 null——框架官方语义位
     * （build 时无 workspace 或无技能仓库即构造 null stager）：{@code stager==null}
     * → {@code stage} 跳过（空结果 map）、filesRoot 提示缺省。技能清单与 load 工具
     * 内容发放不受影响（经仓库实例内存态），脚本仍可 load 后自写工作区；filesRoot
     * 提示本就指向容器内不存在的落点（物化在宿主），缺省反而免谎。镜像
     * {@link #realignBuiltinWebTools} 先例的构建后置校正（2.0.1 无公开开关）；反射
     * 失败仅告警——框架结构变更时物化面恢复旧行为（可观测回退，不炸装配）。</p>
     *
     * <p>Local 姿态不抑制：本地根即真实宿主目录，物化落点正确。子智能体同款
     * （{@link #subagentFactory} 容器面分支——其隔离根同样是容器路径）。</p>
     */
    private static void suppressHostSkillStaging(HarnessAgent agent) {
        for (MiddlewareBase middleware : agent.getDelegate().getMiddlewares()) {
            if (!(middleware instanceof HarnessSkillMiddleware skillMiddleware)) {
                continue;
            }
            try {
                java.lang.reflect.Field stager =
                        HarnessSkillMiddleware.class.getDeclaredField("stager");
                stager.setAccessible(true);
                stager.set(skillMiddleware, null);
            }
            catch (ReflectiveOperationException e) {
                log.warn("技能物化面抑制未生效（框架 HarnessSkillMiddleware.stager 反射失败，"
                        + "物化将按框架缺省行为落宿主路径）: {}", e.getMessage());
            }
        }
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
     * 内置等待屏障件 schema 覆写（#482）：agentscope 2.0.1 把 {@code wait_async_results}
     * 的 {@code timeout_seconds} 声明为<b>必填</b>（{@code @ToolParam} 缺省
     * {@code required=true}——对比 {@code task_ids}/{@code wait_all} 显式
     * {@code required=false}），而内核描述文本自称 "Default 60, max 120"、实现
     * {@code normalizeTimeout(null)→60} 兜底完备——schema 声明与描述/实现自相矛盾：
     * 模型按描述省略参数即 {@code ToolValidator} 参数校验失败（错误以
     * {@code ToolResultBlock.error} 回模型、日志仅 debug 级——#480 同族形态，取证
     * 依赖读码实证）。内核件经 {@code HarnessAgent.Builder.build()} 内置件面进入
     * （messageBus 非空即注册，与 {@code disableSubagents()} 无关——只读/设计面同在），
     * 声明 allowlist 管不到它的 schema 面（#260 遗留口径）。
     *
     * <p>修法＝构建后 schema 覆写（镜像 {@link #realignBuiltinWebTools}/#266 后置校正
     * 先例）：取在册件包 {@link RelaxedSchemaAgentTool 委托件}——唯一改写面是
     * {@code getParameters()}（required 列表摘 {@code timeout_seconds}，仅剩即移除
     * key），执行面全委托内核件；同名后写胜覆盖注册（{@code ToolRegistry} 后写胜）。
     * 校验面（ToolExecutor）与模型面（ToolSchemaProvider）同源
     * {@code AgentTool.getParameters()}——一覆两得。properties 定义保留（模型可显式
     * 传，>120 由内核 clamp）。幂等：required 本不含（上游修版/重复调用）即不动原件
     * ——agentscope 升级修掉此坑后本覆写自动退化为 no-op，届时可撤（回归测试
     * {@code AgentscopeWaitBarrierSchemaTest} 缝 A 断言红即撤除信号）。不选
     * {@code updateToolPresetParameters} 注默认：preset 合并在校验<b>之后</b>
     * （ToolExecutor 字节码序），救不了校验前置失败。</p>
     *
     * <p>主装配（{@link #buildAgent}，realignBuiltinWebTools 旁）与子智能体工厂
     * （{@link #subagentFactory}，#266 stripUndeclared 后——声明放行即在面）两处接线。</p>
     */
    static void relaxWaitBarrierTimeoutSchema(Toolkit toolkit) {
        AgentTool waitTool = toolkit.getTool(WAIT_ASYNC_RESULTS_TOOL);
        if (waitTool == null) {
            return;
        }
        Map<String, Object> schema = waitTool.getParameters();
        Object required = schema == null ? null : schema.get(REQUIRED_SCHEMA_KEY);
        if (!(required instanceof List<?> requiredNames)
                || !requiredNames.contains(TIMEOUT_SECONDS_PARAM)) {
            return;
        }
        List<?> remaining = requiredNames.stream()
                .filter(name -> !TIMEOUT_SECONDS_PARAM.equals(name))
                .toList();
        Map<String, Object> relaxed = new LinkedHashMap<>(schema);
        if (remaining.isEmpty()) {
            relaxed.remove(REQUIRED_SCHEMA_KEY);
        } else {
            relaxed.put(REQUIRED_SCHEMA_KEY, remaining);
        }
        toolkit.registerAgentTool(new RelaxedSchemaAgentTool(waitTool, relaxed));
    }

    /**
     * schema 覆写委托件（#482）：唯一改写面＝{@link #getParameters()}（构建时固化的
     * 改写后 schema），name/描述/strict/outputSchema/只读标记/执行全委托原内核件
     * ——平台只放宽声明面，行为零变化。
     */
    private static final class RelaxedSchemaAgentTool implements AgentTool {

        private final AgentTool delegate;
        private final Map<String, Object> parameters;

        RelaxedSchemaAgentTool(AgentTool delegate, Map<String, Object> parameters) {
            this.delegate = delegate;
            this.parameters = parameters;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public Boolean getStrict() {
            return delegate.getStrict();
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return delegate.getOutputSchema();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return delegate.callAsync(param);
        }
    }

    /**
     * 子智能体构建工厂（#260 subagent 槽装配一等化——撤哨兵接真视图）：业务侧声明
     * 携带 WHAT（名/描述/正文/工具 allowlist/步数），平台不变式在此兑现 HOW——计量
     * 模型同实例、状态存储同库、记忆关闭（记忆不进包）、容器文件面同父级、无工作区
     * 技能、pending tool 恢复开（#431——子会话槽位常驻，中断遗留的无结果 tool_use
     * 续跑即炸 IllegalStateException）；<b>技能面＝按子智能体键取装配视图</b>（{@link
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
            DockerExecFilesystem containerFs, Model parentModel, Model subagentModel,
            Toolkit parentToolkit,
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
        // 注册键即声明名（Function 入参恒同名），构建只认声明——单声明单工厂；
        // 子智能体模型＝覆盖档（#424：调用侧 buildAgent 已把无覆盖归一为父级实例，
        // 此处恒非空——parentModel 仅作参数形占位）
        return name -> {
            HarnessAgent.Builder sub = HarnessAgent.builder()
                    .name(declaration.getName())
                    .description(declaration.getDescription())
                    .model(subagentModel)
                    .toolkit(childToolkit)
                    .defaultSessionId(declaration.getName())
                    .maxIters(declaration.getSteps())
                    .sysPrompt(declaration.getInlineAgentsBody())
                    // 子智能体不委派（框架 asLeafSubagent 包私有的公开等价面）
                    .disableSubagents()
                    .disableDefaultWorkspaceSkills()
                    // pending tool 恢复开（#431）：故障面正在子级——子级同款开启
                    // （根因与机制见 buildAgent 处注释）
                    .enablePendingToolRecovery(true)
                    // 会话誊本写面关停（#553，同主装配——机制与依据见 buildAgent 处注释）
                    .disableTranscript()
                    // 内层重试关闭·内核旋钮（#555 / ADR-0053 ①，同主装配——机制与
                    // 修法见 buildAgent 处注释）
                    .maxRetries(1)
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
            // #482：内置等待屏障件 schema 覆写（声明放行即在面——同主装配口径）
            relaxWaitBarrierTimeoutSchema(child.getToolkit());
            // #363：容器面同款抑制——子级隔离根同样是容器路径（物化面纯宿主 NIO，
            // 见 suppressHostSkillStaging）；Local 姿态不抑制
            if (isolatedRoot != null) {
                suppressHostSkillStaging(child);
            }
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
     * 主模型与压缩模型共用此装配。vendor（ADR-0053 ②）随通道件入计量归因（可空
     * ＝无通道语境，模型边界回落前缀同值）。
     */
    static Model streamLayer(Model resolved, String modelString, String vendor,
            AgentscopeProperties properties) {
        return MeteredModel.wrap(RetryingModel.wrap(resolved, properties), modelString, vendor);
    }

    /** 兼容形（无通道语境的测试面：vendor 空——计量边界回落 provider 前缀）。 */
    static Model streamLayer(Model resolved, String modelString, AgentscopeProperties properties) {
        return streamLayer(resolved, modelString, null, properties);
    }

    /**
     * 候选队列装配（#557，ADR-0053 ③——主模型/子智能体/压缩三处共用装配面）：
     * 单候选队列＝{@link #streamLayer} 直装（无切换面，行为与既有单候选路径一致
     * ——软着陆）；多候选＝{@link FailoverModel}（持队列、懒装配候选[i+1]、切换
     * 留痕经 {@link ModelSwitchTraceSink}）。逐候选装配同形（前缀路由＋通道注入＋
     * streamLayer——每候选自己的计量归属〔provider 前缀与 vendor 均随候选的通道行
     * 走，按账号/按供应商双维度〕与 #321 候选内重试）。
     */
    static Model failoverLayer(ModelCandidateQueue queue, AgentscopeProperties properties,
            ModelSwitchTraceSink switchTraceSink) {
        java.util.function.Function<ModelCandidate, Model> assembler = candidate -> streamLayer(
                ModelRegistry.resolve(candidate.modelString(),
                        creationContextOf(candidate.channel())),
                candidate.modelString(), vendorOf(candidate.channel()), properties);
        if (queue.size() == 1) {
            return assembler.apply(queue.first());
        }
        return new FailoverModel(queue, assembler, switchTraceSink);
    }

    /** 候选通道件的 vendor 腿（无通道语境＝null——模型边界回落 provider 前缀）。 */
    private static String vendorOf(ModelChannel channel) {
        return channel == null ? null : channel.vendor();
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
     * 生效解析上下文（ADR-0031 通道注入腿）：通道件非空＝配置解析产物——
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
     * 压缩配置装配：把配置的触发阈值 + 压缩配置解析产物落到 {@link CompactionConfig}
     * ——压缩模型来自 {@link EdgeModelSupplier}（#366 边缘点收编：compaction 配置
     * 库值优先缺省 flash，通道注入同主模型链——换压缩档/换通道不发版），保留量/裁剪
     * 走框架缺省。压缩（摘要）模型同经候选队列装配（#109 计量 + #321 断流重试 +
     * #557 failover——边缘配置同获链式切换）：压缩链的摘要与 flushBeforeCompact
     * 记忆抽取共用该模型实例直调 stream()，包装后用量归入当前轮计量（flash 档与
     * 主模型 pro 档各自归位）、断流同获流层重试。
     *
     * <p><b>压缩前 offload 关停（#379）</b>：框架缺省 offloadBeforeCompact=true——
     * 压缩时把被逐消息 offload 进会话树，而 {@code SessionTree.flush()} 是<b>纯宿主
     * NIO</b> 写 {@code <workspace根>/<用户>/agents/<智能体名>/sessions/} 两份
     * jsonl（不走 abstractFilesystem，#107 重映射罩不住）。项目容器姿态交给框架的根是容器路径
     * {@code /workspace}——Windows 宿主落 {@code C:\workspace}（issue 现场）、部署
     * 环境落后端容器可写层；会话正本在 PG（cat_agent_state），宿主 jsonl 是无读者
     * 孤儿副本——全形态一致关停（Local 同样无人读，不做姿态分叉），压缩触发/摘要/
     * flushBeforeCompact 记忆抽取行为不变（被逐消息直接丢弃，不落盘）。勿用
     * {@code Builder.disableSessionPersistence()} 替代：2.0.1 该开关主构建路径零消费
     * （惰性，罩不住本链）。</p>
     */
    static CompactionConfig compactionConfig(AgentscopeProperties properties,
            EdgeModelSupplier.EdgeModel compaction, ModelSwitchTraceSink switchTraceSink) {
        CompactionConfig.Builder config = compactionConfigBuilder(properties, compaction, switchTraceSink);
        if (properties.getCompactionTriggerTokens() != null) {
            config.triggerTokens(properties.getCompactionTriggerTokens());
        }
        return config.build();
    }

    /**
     * DeepSeek Harness compaction-basic 缺省口径的两个常量（源
     * {@code packages/compaction/compaction-basic/src/config.ts}：thresholdRatio 0.8、
     * headroomTokens 65,536）。
     */
    static final double KERNEL_COMPACTION_THRESHOLD_RATIO = 0.8;
    static final int KERNEL_COMPACTION_HEADROOM_TOKENS = 65_536;

    /**
     * 内核形态压缩配置（#598 真跑实证后修正）：照 DeepSeek Harness 的口径——只按上下文
     * 压力压缩，不按消息条数。框架缺省 {@code triggerMessages=50}（对话满 50 条即压缩）
     * 对内核关停：实证内核一轮 2–3 条消息，约 15–20 轮压缩一次，14 分钟 17 次、301 轮
     * 撞轮次护栏；实验基线无压缩、89 轮自然结束；Harness 的自动压缩只按上下文压力
     * 触发、没有按消息条数的规则（用户裁决 2026-11-24：照 Harness 口径）。token 阈值
     * {@link #kernelCompactionTriggerTokens} 取主模型窗口（{@code contextWindow}）与内核
     * 每次请求的输出上限（{@code reservedOutputTokens}）。其余（offload 关停、压缩模型）
     * 与 {@link #compactionConfig} 同一份公共段；旧形态一行不动。
     */
    static CompactionConfig kernelCompactionConfig(AgentscopeProperties properties,
            EdgeModelSupplier.EdgeModel compaction, ModelSwitchTraceSink switchTraceSink,
            int contextWindow, int reservedOutputTokens) {
        CompactionConfig.Builder config = compactionConfigBuilder(properties, compaction, switchTraceSink);
        config.triggerMessages(0);
        config.triggerTokens(kernelCompactionTriggerTokens(contextWindow, reservedOutputTokens));
        return config.build();
    }

    /**
     * 内核形态压缩触发 token 阈值——DeepSeek Harness compaction-basic
     * {@code resolveCompactSpec} 原式：min(窗口×0.8, 窗口−输出上限−余量 65,536)。压力
     * 同时受窗口比例与「扣除输出预留和压缩余量后的容量」两项约束。窗口未登记（≤0）
     * 或余量算尽时回 0＝框架动态口径（压缩中间件按主模型窗口现算；无窗口回 16 万兜底）。
     *
     * <p>单位注记：框架压缩器按<b>估算</b> token 判阈，Harness 按供应商回报的真实 token。
     * 内核对话以代码为主，实测估算≈真实的 2 倍（#598 首跑末轮：估算 177,831／回报
     * 88,920）——同一公式落在平台上，真实 token 上的触发点比 Harness 更早。
     * 不沿用 {@code compaction-trigger-tokens}：那个固定值是按中文对话「估算偏低」
     * 标定的，对内核（估算偏高）方向相反，会在窗口两成处就触发。</p>
     */
    static int kernelCompactionTriggerTokens(int contextWindow, int reservedOutputTokens) {
        if (contextWindow <= 0) {
            return 0;
        }
        long byRatio = Math.round(contextWindow * KERNEL_COMPACTION_THRESHOLD_RATIO);
        long byBudget = (long) contextWindow - Math.max(0, reservedOutputTokens)
                - KERNEL_COMPACTION_HEADROOM_TOKENS;
        return byBudget <= 0 ? 0 : (int) Math.min(byRatio, byBudget);
    }

    /** 内核每次请求预留的输出上限（kernel-max-output-tokens；未配置＝不注入＝不预留）。 */
    private static int kernelReservedOutputTokens(AgentscopeProperties properties) {
        Integer cap = properties.getKernelMaxOutputTokens();
        return cap == null || cap < 0 ? 0 : cap;
    }

    /** 压缩配置公共段（全形态一致）：压缩前 offload 关停＋压缩模型（候选队列 failover 同构、计量外圈）。 */
    private static CompactionConfig.Builder compactionConfigBuilder(AgentscopeProperties properties,
            EdgeModelSupplier.EdgeModel compaction, ModelSwitchTraceSink switchTraceSink) {
        CompactionConfig.Builder config = CompactionConfig.builder();
        // #379 宿主泄漏正源（见 compactionConfig javadoc）——框架缺省开，必须显式关
        config.offloadBeforeCompact(false);
        config.model(failoverLayer(compaction.queue(), properties, switchTraceSink));
        return config;
    }
}
