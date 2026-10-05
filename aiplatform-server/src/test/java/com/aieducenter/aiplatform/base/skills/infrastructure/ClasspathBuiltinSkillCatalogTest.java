package com.aieducenter.aiplatform.base.skills.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;

/**
 * 内置技能目录真资源面（#247 classpath {@code skills/}）：prd-writing 双形态
 * 契约钉死（#285 PRD 清单章二形）——系统形（功能清单）/设计形（设计物清单）
 * 与「按项目终点类型走形」的选择规则是主智能体 PRD 产出的形态正本，正文漂移
 * 即契约漂移，逐句钉住。
 */
class ClasspathBuiltinSkillCatalogTest {

    private final ClasspathBuiltinSkillCatalog catalog = new ClasspathBuiltinSkillCatalog();

    @Test
    void given_classpath_skills_when_find_all_then_prd_writing_present() {
        List<BuiltinSkill> skills = catalog.findAll();

        assertThat(skills)
                .extracting(BuiltinSkill::name)
                .contains("prd-writing");
    }

    @Test
    void given_prd_writing_skill_when_read_body_then_dual_form_chapters_pinned() {
        String body = catalog.findAll().stream()
                .filter(skill -> skill.name().equals("prd-writing"))
                .findFirst().orElseThrow()
                .content();

        // 清单章二形正本：系统形（功能清单——系统/系统＋设计）与设计形（设计物清单）
        assertThat(body)
                .contains("清单章按项目终点类型走形")
                .contains("query_project_facts")
                .contains("系统形（终点类型＝系统、系统＋设计）")
                .contains("功能清单")
                .contains("系统＋设计项目不另立设计清单")
                .contains("设计硬约束")
                .contains("关键约束")
                .contains("设计形（终点类型＝设计）")
                .contains("设计物清单")
                .contains("用途、尺寸、风格要点与验收要点")
                // 终点类型变更时清单章随改（自愈口径——后续任意修订按形态规则收敛）
                .contains("终点类型变更后修订 PRD 时，清单章必须随改到新形态");
    }
}
