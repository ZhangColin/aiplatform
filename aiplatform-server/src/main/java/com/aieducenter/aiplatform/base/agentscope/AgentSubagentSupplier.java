package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.util.List;

/**
 * 智能体子智能体 SPI（镜像 {@link AgentSkillRepositorySupplier}——委派位与技能位同为
 * run 内一等挂载点，ADR 0006「单一主智能体+委派式执行」）：子智能体（自测、审查等
 * 专项职能）归业务侧，base/agentscope 只供内核不供子智能体。业务侧实现本接口，按
 * <b>配置</b>发放子智能体声明（{@link SubagentDeclaration}——名字/描述/隔离工作区/
 * 角色正文/工具白名单）；配置语境为空或未知时返回空集——无声明挂载即框架不注入
 * {@code <available_subagents>}（无挂载不注入）。工厂构建 agent 时取用（每次调用
 * 返回同一批声明实例，声明为不可变值对象）。
 *
 * <p>隔离口径（#95）：委派子智能体的隔离 = 框架隔离根——声明用
 * {@code workspaceMode(ISOLATED)}，引擎自带 per-agent 工作区布局（自动创建
 * {@code agents/<name>/workspace/}、namespace 隔离），{@code agents} 已进非交付
 * 目录集（{@code WorkspaceLayout#AGENTS_DIR}），零新机制。子智能体任务自包含、
 * 结果回交 run 执行体，不直接面对用户；事件带 {@code source} 归属（过程呈现分
 * 角色播，用户面无角色标签）。</p>
 *
 * @see SubagentDeclaration
 * @see com.aieducenter.aiplatform.base.agentscope.AgentSkillRepositorySupplier
 */
@FunctionalInterface
public interface AgentSubagentSupplier {

    /**
     * 给定配置键与工作区形态的子智能体声明清单（返回空集 = 该配置不委派任何子
     * 智能体）。{@code workspace} 为子智能体隔离根落位（工作区 .platform/ 目录）
     * 的寻址腿——委派是 run 内机制，子智能体依赖项目工作区（读写交付代码只读、
     * 报告写隔离根），故与工具集同看工作区形态（技能是智能体能力、非工作区依赖，
     * 两 SPI 的判据在此分道）。
     */
    List<SubagentDeclaration> subagentsFor(String agentKey, AgentWorkspace workspace);
}
