package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.agentscope.AgentWorkspace;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;

/**
 * 按配置的技能集装配（#94 技能位接线）：主智能体 = 内置 PRD 写作技能（classpath
 * skills/ 目录只读——七章节模板与写法从旧角色卡 prompt 与 savePrd 工具描述迁出）；
 * run 执行体与无配置语境 = 空集（不写 PRD 无需技能——无技能挂载即框架不注入
 * {@code <available_skills>}）。技能是智能体能力（非工作区依赖），只看配置键。
 */
class ProfileSkillRepositorySupplierTest {

    private final ClasspathSkillRepository builtinSkills = mock(ClasspathSkillRepository.class);

    private ProfileSkillRepositorySupplier supplier() {
        return new ProfileSkillRepositorySupplier(builtinSkills);
    }

    @Test
    void given_main_on_any_workspace_when_skill_repositories_then_builtin_prd_writing() {
        // 主智能体挂载内置技能（不区分工作区形态——技能是智能体能力，非工作区依赖；
        // 与工具集的双锚防误配不同，技能只看配置键，Local/Dev 也照发）
        ProfileSkillRepositorySupplier supplier = supplier();
        assertThat(supplier.skillRepositoriesFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectReadOnly("42", "ws-42-dev"))).containsExactly(builtinSkills);
        assertThat(supplier.skillRepositoriesFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"))).containsExactly(builtinSkills);
        assertThat(supplier.skillRepositoriesFor(AgentProfile.MAIN.key(),
                new AgentWorkspace.Local(null))).containsExactly(builtinSkills);
    }

    @Test
    void given_executor_when_skill_repositories_then_empty() {
        // 执行体不写 PRD——无技能挂载（结构测试：无挂载 → 框架不注入 <available_skills>）
        assertThat(supplier().skillRepositoriesFor(AgentProfile.EXECUTOR.key(),
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"))).isEmpty();
    }

    @Test
    void given_unknown_or_absent_key_when_skill_repositories_then_empty() {
        assertThat(supplier().skillRepositoriesFor(null,
                new AgentWorkspace.ProjectDev("42", "ws-42-dev"))).isEmpty();
        assertThat(supplier().skillRepositoriesFor("naming",
                new AgentWorkspace.Local(null))).isEmpty();
    }

    @Test
    void builtin_repository_contains_prd_writing_skill_with_seven_chapters() throws IOException {
        // 行为回归锚：七章节模板不再活在角色卡 prompt，而在此内置技能资产——加载真
        // 仓库断言正文仍含七章节（PRD 仍按七章节产出）
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            AgentSkill skill = repo.getSkill("prd-writing");
            assertThat(skill).isNotNull();
            assertThat(skill.getSkillContent())
                    .contains("需求背景", "目标用户", "核心场景", "范围边界",
                            "关键约束", "功能清单", "待定项");
        }
    }
}
