package com.aieducenter.aiplatform.base.skills.domain.port;

import java.util.List;

import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;

/**
 * 内置技能目录（#247）：classpath {@code skills/} 目录的读取口——内置技能与
 * 库技能同权呈现的合成源（空库时清单仍非空）。环境能力型端口（对齐
 * {@code EmbeddingClient} 先例）：实现走 agentscope ClasspathSkillRepository
 * （SKILL.md 生态同构、fat-jar 虚拟文件系统），域层不见 agentscope 类型。
 */
public interface BuiltinSkillCatalog {

    /**
     * 全部内置技能（名称序）。
     */
    List<BuiltinSkill> findAll();

    /**
     * 按技能名直读（frontmatter name）。
     *
     * @return 查无返回 null，由调用方定 404 语义
     */
    BuiltinSkill findByName(String name);
}
