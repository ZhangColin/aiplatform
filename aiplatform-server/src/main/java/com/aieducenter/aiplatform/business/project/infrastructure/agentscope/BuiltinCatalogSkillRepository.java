package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.List;

import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;
import com.aieducenter.aiplatform.base.skills.domain.port.BuiltinSkillCatalog;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;

/**
 * 内置技能目录 → agentscope 技能仓库适配（#249 装配缝归一）：装配侧原先自持
 * {@code ClasspathSkillRepository} 读同一 classpath 目录（路径字面量两处各持、
 * 实例两份），本适配器把 {@link BuiltinSkillCatalog} 端口包成框架仓库类型——
 * 装配与后台清单同源单实例（#247 留下的归一触发器在此兑现）。只读透传（目录
 * 本身只读）：读操作按调用即时查目录（量级个位数不缓存，与目录实现同口径），
 * 写操作恒拒。
 *
 * <p>本类归 business 装配缝（组合 base.skills 端口与 agentscope 框架类型是
 * 业务侧装配的事，base 两 BC 不互相挂依赖）。</p>
 */
public class BuiltinCatalogSkillRepository implements AgentSkillRepository {

    private final BuiltinSkillCatalog catalog;

    public BuiltinCatalogSkillRepository(BuiltinSkillCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public AgentSkill getSkill(String name) {
        BuiltinSkill skill = catalog.findByName(name);
        return skill == null ? null : of(skill);
    }

    @Override
    public List<String> getAllSkillNames() {
        return catalog.findAll().stream().map(BuiltinSkill::name).toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return catalog.findAll().stream().map(BuiltinCatalogSkillRepository::of).toList();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        // 只读目录（classpath 随平台发版）——写面结构性不存在
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        return catalog.findByName(skillName) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("builtin-catalog", "classpath:skills", false);
    }

    @Override
    public String getSource() {
        return "builtin";
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 只读仓库——写标志无效应（目录随平台发版）
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    /** 内置读模型 → 框架技能（解析态直映射——审核面所见即注入面；name/description
     *  在 metadata 之后设置：builder 的 metadata() 整体替换 map，先设会被冲掉）。 */
    private static AgentSkill of(BuiltinSkill skill) {
        return AgentSkill.builder()
                .metadata(skill.frontmatter())
                .name(skill.name())
                .description(skill.description())
                .skillContent(skill.content())
                .source("builtin")
                .build();
    }
}
