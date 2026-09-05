package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
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
 * 落既有 dev 容器），并关闭会写 harness 内脏进项目工作区的部件（subagents /
 * memory：源码包是交付物，记忆文件不进包）与内核 shell 工具（#83：执行体的命令
 * 走业务侧 ConfirmingShellTool，破坏性命令挂确认卡）——工作区上下文（AGENTS.md
 * 等）与 workspace/tools.json 读取照常，经容器文件面即项目事实；
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
            AgentscopeProperties properties) {
        this(stateStore, toolkitSupplier, (name, sysPrompt, modelString, workspace, agentKey) ->
                buildAgent(stateStore, toolkitSupplier, skillRepositorySupplier,
                        name, sysPrompt, modelString, workspace, agentKey,
                        properties.getMaxIters()));
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
            String name, String sysPrompt, String modelString, AgentWorkspace workspace,
            String agentKey, Integer maxIters) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(modelString)
                .stateStore(stateStore)
                .toolkit(toolkitSupplier.toolkitFor(agentKey, workspace))
                // 技能挂载位（#94）：按配置发放技能仓库——无技能挂载返回空集即框架
                // 不注入 <available_skills>；本平台工作区技能用 .platform/skills/（非
                // 框架 skills/），关闭框架工作区技能自动合成免无谓文件面往返
                .disableDefaultWorkspaceSkills();
        skillRepositorySupplier.skillRepositoriesFor(agentKey, workspace)
                .forEach(builder::skillRepository);
        if (maxIters != null) {
            builder.maxIters(maxIters);
        }
        switch (workspace) {
            case AgentWorkspace.Local local -> {
                if (local.root() != null) {
                    builder.workspace(local.root());
                }
            }
            case AgentWorkspace.ProjectDev dev -> projectSandbox(builder, dev.containerName())
                    // #83 权限确认触发面：内核 shell 退位——执行体的命令走业务侧
                    // ConfirmingShellTool（ToolBase 自检 ASK 挂确认卡；内核 shell 非
                    // ToolBase 引擎拦不住，注册名 execute 也进不了播报表）
                    .disableShellTool();
            case AgentWorkspace.ProjectReadOnly ro -> projectSandbox(builder, ro.containerName())
                    // 只读面（#86 主智能体对话姿态）：另关内核文件与 shell 工具——
                    // 写面结构性不存在，项目事实的读取经业务侧只读工具集
                    // （ProfileToolkitSupplier）
                    .disableFilesystemTools()
                    .disableShellTool();
        }
        return builder.build();
    }

    /**
     * 项目沙箱公共装配（ProjectDev 与 ProjectReadOnly 共用）：名义根与容器内
     * 工作区根同形（路径规范化剥前缀后即工作区锚定形）、docker exec 文件面、
     * 关闭会写 harness 内脏进项目工作区的部件（源码包是交付物）。
     */
    private static HarnessAgent.Builder projectSandbox(HarnessAgent.Builder builder,
            String containerName) {
        return builder
                .workspace(java.nio.file.Path.of(AgentWorkspace.ProjectDev.CONTAINER_ROOT))
                .abstractFilesystem(new DockerExecFilesystem(containerName))
                .disableSubagents()
                .disableMemoryHooks()
                .disableMemoryTools();
    }
}
