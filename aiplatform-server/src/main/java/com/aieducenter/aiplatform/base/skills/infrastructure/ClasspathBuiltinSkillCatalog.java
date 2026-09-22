package com.aieducenter.aiplatform.base.skills.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;
import com.aieducenter.aiplatform.base.skills.domain.port.BuiltinSkillCatalog;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;

/**
 * 内置技能目录实现（#247）：classpath {@code skills/} 目录经 agentscope
 * {@link ClasspathSkillRepository} 读取（SKILL.md 生态同构、fat-jar 虚拟文件
 * 系统），解析态（frontmatter＋正文）即审核面所见。读操作按调用即时读文件
 * （只读目录、量级个位数，不缓存）。
 *
 * <p>{@link ClasspathSkillRepository} 长生命周期（fat-jar 下共享 JarFileSystem
 * 引用计数），持单例随容器关闭释放。装配侧（business
 * {@code ProfileSkillRepositorySupplier}）另持实例读同一目录——路径字面量两处
 * 各持（T3 装配合成收口时统一装配缝，届时归一）。</p>
 */
@Component
@Adapter(PortType.CLIENT)
public class ClasspathBuiltinSkillCatalog implements BuiltinSkillCatalog, DisposableBean {

    /** 内置技能目录（classpath 资源根，与装配侧同目录）。 */
    private static final String BUILTIN_SKILLS_PATH = "skills";

    private final ClasspathSkillRepository builtinSkills;

    public ClasspathBuiltinSkillCatalog() {
        try {
            this.builtinSkills = new ClasspathSkillRepository(BUILTIN_SKILLS_PATH);
        }
        catch (IOException e) {
            throw new UncheckedIOException("内置技能目录加载失败: " + BUILTIN_SKILLS_PATH, e);
        }
    }

    /** 测试缝注入构造（单测替身目录）。 */
    ClasspathBuiltinSkillCatalog(ClasspathSkillRepository builtinSkills) {
        this.builtinSkills = builtinSkills;
    }

    @Override
    public List<BuiltinSkill> findAll() {
        return builtinSkills.getAllSkills().stream()
                .map(ClasspathBuiltinSkillCatalog::of)
                .sorted(Comparator.comparing(BuiltinSkill::name))
                .toList();
    }

    @Override
    public BuiltinSkill findByName(String name) {
        try {
            return of(builtinSkills.getSkill(name));
        }
        catch (IllegalArgumentException e) {
            // 框架查无抛 Illegal——端口口径转 null，由调用方定 404 语义
            return null;
        }
    }

    @Override
    public void destroy() {
        builtinSkills.close();
    }

    private static BuiltinSkill of(AgentSkill skill) {
        return new BuiltinSkill(skill.getName(), skill.getDescription(),
                skill.getMetadata(), skill.getSkillContent());
    }
}
