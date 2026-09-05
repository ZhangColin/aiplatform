package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.agentscope.AgentSkillRepositorySupplier;
import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;

/**
 * 按智能体配置的技能集装配（技能来源三途之「内置」首个，#94 技能位接线）：{@link
 * AgentProfile#MAIN 主智能体} = 内置 PRD 写作技能（classpath {@code skills/} 目录
 * 只读，SKILL.md 生态同构——七章节模板与写法从旧角色卡 prompt 与 savePrd 工具描述
 * 迁出）；{@link AgentProfile#EXECUTOR run 执行体} 与无配置语境 = 空集（不写 PRD
 * 无需技能——无技能挂载即框架不注入 {@code <available_skills>}）。技能是智能体
 * 能力（非工作区依赖），故只看配置键；内置/自制/安装三途为扩展点，v1 只接内置。
 *
 * <p>{@link ClasspathSkillRepository} 长生命周期（fat-jar 下共享 JarFileSystem 引用
 * 计数），持单例随容器关闭释放；读操作线程安全（只读仓库）。</p>
 */
@Component
public class ProfileSkillRepositorySupplier
        implements AgentSkillRepositorySupplier, DisposableBean {

    /** 内置技能目录（classpath 资源根，SKILL.md 生态同构；每个技能一个子目录）。 */
    private static final String BUILTIN_SKILLS_PATH = "skills";

    private final ClasspathSkillRepository builtinSkills;

    public ProfileSkillRepositorySupplier() {
        try {
            this.builtinSkills = new ClasspathSkillRepository(BUILTIN_SKILLS_PATH);
        }
        catch (IOException e) {
            throw new UncheckedIOException("内置技能目录加载失败: " + BUILTIN_SKILLS_PATH, e);
        }
    }

    /** 测试缝注入构造（单测替身仓库）。 */
    ProfileSkillRepositorySupplier(ClasspathSkillRepository builtinSkills) {
        this.builtinSkills = builtinSkills;
    }

    @Override
    public List<AgentSkillRepository> skillRepositoriesFor(String agentKey,
            AgentWorkspace workspace) {
        if (AgentProfile.MAIN.key().equals(agentKey)) {
            return List.of(builtinSkills);
        }
        return List.of();
    }

    @Override
    public void destroy() {
        builtinSkills.close();
    }
}
