package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.util.concurrent.ConcurrentHashMap;
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
 * 是交付物，记忆文件不进包）与内核 shell 工具（#83：执行体的命令走业务侧
 * ConfirmingShellTool，破坏性命令挂确认卡）——工作区上下文（AGENTS.md 等）与
 * workspace/tools.json 读取照常，经容器文件面即项目事实；subagents 委派位在此
 * 开启（#95：子智能体经 {@link AgentSubagentSupplier} 挂载，隔离根落位平台目录
 * 下进非交付目录集），只读面在分支处单独关闭；
 * {@link AgentWorkspace.ProjectReadOnly ProjectReadOnly} 项目工作区只读面（#86
 * 主智能体对话姿态）——容器与内脏关闭同 ProjectDev，另关内核文件/shell 工具
 * （写面结构性关闭，主智能体永不读写沙箱代码——PRD 写入走业务侧 savePrd）。</p>
 *
 * <p>会话状态：全形态统一接 {@link PostgresAgentStateStore}（cat_agent_state，
 * (userId, sessionId) 槽位）——平台重启后同一会话标识恢复续跑，会话上下文不丢；
 * 替换框架缺省的本地 JSON 文件实现（单机 {@code ~/.agentscope/state/}，多副本/
 * 重启语义不成立）。工具集经 {@link AgentToolkitSupplier}、技能经
 * {@link AgentSkillRepositorySupplier}（业务侧资产，#94 技能位）注入。</p>
 */
@Slf4j
@Component
public class AgentscopeHarnessAgentFactory implements DisposableBean {

    /**
     * 真正构建 HarnessAgent 的步骤（抽出便于单测注入替身）。
     */
    interface AgentBuilder {

        HarnessAgent build(String name, String sysPrompt, String modelString,
                AgentWorkspace workspace, String agentKey);
    }

    private final ConcurrentHashMap<String, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final AgentBuilder builder;
    private final AgentStateStore stateStore;
    private final AgentToolkitSupplier toolkitSupplier;

    @Autowired
    public AgentscopeHarnessAgentFactory(AgentStateStore stateStore,
            AgentToolkitSupplier toolkitSupplier, AgentSkillRepositorySupplier skillRepositorySupplier,
            AgentSubagentSupplier subagentSupplier, AgentscopeProperties properties) {
        this(stateStore, toolkitSupplier, (name, sysPrompt, modelString, workspace, agentKey) ->
                buildAgent(stateStore, toolkitSupplier, skillRepositorySupplier, subagentSupplier,
                        name, sysPrompt, modelString, workspace, agentKey, properties));
    }

    AgentscopeHarnessAgentFactory(AgentStateStore stateStore, AgentToolkitSupplier toolkitSupplier,
            AgentBuilder builder) {
        this.stateStore = stateStore;
        this.toolkitSupplier = toolkitSupplier;
        this.builder = builder;
    }

    public HarnessAgent obtain(String name, String sysPrompt, String modelString,
            AgentWorkspace workspace, String agentKey) {
        // sysPrompt/workspace/agentKey 明文入键（不用 hashCode：碰撞会把不同人格/
        // 工作区/工具面的 agent 当同规格静默复用）
        String key = name + "|" + modelString + "|" + sysPrompt + "|" + workspace.identity()
                + "|" + agentKey;
        return agents.computeIfAbsent(key,
                k -> builder.build(name, sysPrompt, modelString, workspace, agentKey));
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
            AgentSubagentSupplier subagentSupplier,
            String name, String sysPrompt, String modelString, AgentWorkspace workspace,
            String agentKey, AgentscopeProperties properties) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                // 模型边界计量（#109）：主模型经 MeteredModel 包装——主循环每次
                // 迭代的 stream() 收口报用量（取代只认 ModelCallEndEvent 的旧源）
                .model(MeteredModel.wrap(ModelRegistry.resolve(modelString), modelString))
                .stateStore(stateStore)
                .toolkit(toolkitSupplier.toolkitFor(agentKey, workspace))
                // 技能挂载位（#94）：按配置发放技能仓库——无技能挂载返回空集即框架
                // 不注入 <available_skills>；本平台工作区技能用 .platform/skills/（非
                // 框架 skills/），关闭框架工作区技能自动合成免无谓文件面往返
                .disableDefaultWorkspaceSkills();
        skillRepositorySupplier.skillRepositoriesFor(agentKey, workspace)
                .forEach(builder::skillRepository);
        // 委派位（#95）：按配置挂载子智能体声明——无声明挂载返回空集即框架不注入
        // <available_subagents>（主智能体/无配置语境空集）；子智能体隔离根由声明
        // 携带（框架 ISOLATED 工作区布局），工厂不另建机制
        subagentSupplier.subagentsFor(agentKey, workspace)
                .forEach(builder::subagent);
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
            case AgentWorkspace.ProjectDev dev -> projectSandbox(builder, dev.containerName())
                    // #83 权限确认触发面：内核 shell 退位——执行体的命令走业务侧
                    // ConfirmingShellTool（ToolBase 自检 ASK 挂确认卡；内核 shell 非
                    // ToolBase 引擎拦不住，注册名 execute 也进不了播报表）
                    .disableShellTool();
            case AgentWorkspace.ProjectReadOnly ro -> projectSandbox(builder, ro.containerName())
                    // 只读面（#86 主智能体对话姿态）：另关内核文件与 shell 工具——
                    // 写面结构性不存在，项目事实的读取经业务侧只读工具集
                    // （ProfileToolkitSupplier）；委派是 run 内机制（#95 委派位只开在
                    // ProjectDev），主智能体永不委派——子智能体一并关闭
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableSubagents();
        }
        return builder.build();
    }

    /**
     * 项目沙箱公共装配（ProjectDev 与 ProjectReadOnly 共用）：名义根与容器内
     * 工作区根同形（路径规范化剥前缀后即工作区锚定形）、docker exec 文件面、
     * 关闭会写 harness 内脏进项目工作区的部件（memory：源码包是交付物，记忆文件
     * 不进包）。subagents 不在公共装配关——委派位（#95）只开在 ProjectDev，只读面
     * 在分支处单独关闭。
     */
    private static HarnessAgent.Builder projectSandbox(HarnessAgent.Builder builder,
            String containerName) {
        return builder
                .workspace(java.nio.file.Path.of(AgentWorkspace.ProjectDev.CONTAINER_ROOT))
                .abstractFilesystem(new DockerExecFilesystem(containerName))
                .disableMemoryHooks()
                .disableMemoryTools();
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
