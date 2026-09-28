package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentSkillRepositorySupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.port.BuiltinSkillCatalog;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.core.skill.repository.AgentSkillRepository;

/**
 * 按智能体配置的技能集装配（#94 技能位接线 → #249 槽位装配合成）：某槽位装配面
 * ＝内置 classpath ∪ 该槽位<b>已指派且启用</b>的库技能——库技能经 {@link
 * SlotLibrarySkillRepository 动态查库视图}发放（ADR-0021：指派/启停变更下一轮
 * 自然生效、进行中 run 不定格、工厂缓存键不含技能集），内置经 {@link
 * BuiltinCatalogSkillRepository 目录端口适配}发放（与后台清单同源单实例——#247
 * 留下的装配缝归一在此兑现）。技能是智能体能力（非工作区依赖），只看配置键。
 *
 * <p>槽位装配面（内置挂载按职能性质定形，不全员摊内置——PRD 写作是需求侧资产，
 * {@code CONTEXT.md}「主智能体保持轻技能面」同源）：</p>
 * <ul>
 * <li>{@link AgentProfile#MAIN 主智能体}＝内置（PRD 写作）∪ main 槽位库技能；</li>
 * <li>{@link AgentProfile#EXECUTOR run 执行体}＝executor 槽位库技能（安装技能的
 * 主消费槽位——编码方法论等）；</li>
 * <li>{@code self-test}（子智能体槽当前唯一实例，键见 {@link
 * ProfileSubagentSupplier#SELF_TEST_NAME}）＝subagent 槽位库技能——<b>运行时已
 * 接线</b>（#260 装配一等化：平台工厂构建子智能体时按本键取视图挂载，指派的
 * 启用技能真实出现在自测子智能体技能清单；动态查库同款语义——指派/启停变更
 * 下一轮清单重建即生效）；</li>
 * <li>无配置语境＝空集（无技能挂载即框架不注入 {@code <available_skills>}）。</li>
 * </ul>
 *
 * <p>三个槽位视图实例长生命周期（无状态查库），随容器同生灭。</p>
 */
@Component
public class ProfileSkillRepositorySupplier implements AgentSkillRepositorySupplier {

    private final AgentSkillRepository builtinSkills;
    private final AgentSkillRepository mainLibrary;
    private final AgentSkillRepository executorLibrary;
    private final AgentSkillRepository subagentLibrary;

    public ProfileSkillRepositorySupplier(BuiltinSkillCatalog builtinSkillCatalog,
            SkillStore skillStore) {
        this.builtinSkills = new BuiltinCatalogSkillRepository(builtinSkillCatalog);
        this.mainLibrary = new SlotLibrarySkillRepository(skillStore, SkillSlot.MAIN);
        this.executorLibrary = new SlotLibrarySkillRepository(skillStore, SkillSlot.EXECUTOR);
        this.subagentLibrary = new SlotLibrarySkillRepository(skillStore, SkillSlot.SUBAGENT);
    }

    @Override
    public List<AgentSkillRepository> skillRepositoriesFor(String agentKey,
            AgentWorkspace workspace) {
        if (AgentProfile.MAIN.key().equals(agentKey)) {
            // 内置在前（低优先级）、库在后（高优先级）——显式指派的库技能同名覆盖内置
            return List.of(builtinSkills, mainLibrary);
        }
        if (AgentProfile.EXECUTOR.key().equals(agentKey)) {
            return List.of(executorLibrary);
        }
        if (ProfileSubagentSupplier.SELF_TEST_NAME.equals(agentKey)) {
            return List.of(subagentLibrary);
        }
        return List.of();
    }
}
