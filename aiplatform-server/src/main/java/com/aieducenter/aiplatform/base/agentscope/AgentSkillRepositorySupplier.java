package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import java.util.List;

/**
 * 智能体技能仓库 SPI（镜像 {@link AgentToolkitSupplier}——技能是「按需加载的能力
 * 包」，与工具集同为智能体资产，归业务侧）：各智能体配置（{@code agentKey}）的
 * 差异只在技能集，base/agentscope 只供内核不供技能。业务侧实现本接口，按<b>配置
 * </b>发放技能仓库（技能来源三途：内置 / 自制 / 安装；v1 只接内置首个——PRD 写作，
 * 见 {@code ProfileSkillRepositorySupplier}）；配置语境为空或未知时返回空集——
 * 无技能挂载即框架不注入 {@code <available_skills>}（无挂载不注入）。工厂构建
 * agent 时取用（每次调用返回同一批实例，仓库通常长生命周期）。
 *
 * @see io.agentscope.core.skill.repository.ClasspathSkillRepository
 */
@FunctionalInterface
public interface AgentSkillRepositorySupplier {

    /**
     * 给定配置键与工作区形态的技能仓库清单（低优先级在前、高优先级在后，框架
     * 同名冲突按后注册覆盖；返回空集 = 该配置不挂载任何技能）。{@code workspace}
     * 为技能来源「自制」（工作区 .platform/skills/）预留——v1 只接内置（classpath），
     * 内置技能非工作区依赖，实现可只看配置键（{@link AgentToolkitSupplier} 的
     * 工作区形态判据在此不成立：工具依赖工作区，技能是智能体能力）。
     */
    List<AgentSkillRepository> skillRepositoriesFor(String agentKey, AgentWorkspace workspace);
}
