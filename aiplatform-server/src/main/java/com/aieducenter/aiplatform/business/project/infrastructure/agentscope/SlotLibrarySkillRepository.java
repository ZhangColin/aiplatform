package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;

/**
 * 槽位库技能<b>动态视图</b>仓库（#249，ADR-0021 装配生效语义）：{@link
 * #getAllSkills()} 每次调用即时查库——该槽位「已指派且启用」的库条目（SKILL.md
 * 解析态即注入面，与后台审核面同源）。框架技能中间件每轮重建清单时重查本口，
 * 指派/启停变更下一轮自然生效、进行中 run 不定格；工厂实例长生命周期复用（缓存
 * 键不含技能集，实例内容仍动态）。停用即退出候选、卸载行即消失（快照物删除）
 * ——本仓库不做任何固化快照。
 *
 * <p>本类归 business 装配缝（组合 base.skills 存取口与 agentscope 框架类型是
 * 业务侧装配的事）；查询异常如实上抛——框架中间件对单仓库失败按「本轮跳过」
 * 降级并告警，不炸智能体主循环。</p>
 */
public class SlotLibrarySkillRepository implements AgentSkillRepository {

    private final SkillStore skillStore;
    private final SkillSlot slot;

    public SlotLibrarySkillRepository(SkillStore skillStore, SkillSlot slot) {
        this.skillStore = skillStore;
        this.slot = slot;
    }

    @Override
    public AgentSkill getSkill(String name) {
        for (AgentSkill skill : getAllSkills()) {
            if (skill.getName().equals(name)) {
                return skill;
            }
        }
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        return getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return skillStore.findEnabledAssigned(slot).stream()
                .map(SlotLibrarySkillRepository::of)
                .toList();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        // 库写口走后台管理 REST（安装/启停/卸载/指派），装配面只读
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        return getSkill(skillName) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("slot-library", "skl:" + slot.key(), false);
    }

    @Override
    public String getSource() {
        return "skl:" + slot.key();
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 装配面只读——写标志无效应（库写口走后台管理 REST）
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    /** 库条目 → 框架技能（解析态直映射——name/description 冗余列直读，来源＝来源包；
     *  name/description 在 metadata 之后设置：builder 的 metadata() 整体替换 map）。 */
    private static AgentSkill of(SkillRecord record) {
        return AgentSkill.builder()
                .metadata(record.frontmatter())
                .name(record.name())
                .description(record.description())
                .skillContent(record.content())
                .source(record.sourcePackage())
                .build();
    }
}
