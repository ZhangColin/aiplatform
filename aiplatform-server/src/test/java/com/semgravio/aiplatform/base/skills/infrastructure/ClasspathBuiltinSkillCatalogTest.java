package com.semgravio.aiplatform.base.skills.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.skills.domain.model.BuiltinSkill;

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

    @Test
    void given_prd_writing_skill_when_read_body_then_completion_presentation_pinned() {
        String body = catalog.findAll().stream()
                .filter(skill -> skill.name().equals("prd-writing"))
                .findFirst().orElseThrow()
                .content();

        // #461 新访谈策略：补全集中呈现（专业身份直陈、禁假设/自问自答字眼、
        // PRD 不区分用户答的与平台补的）＋「验收与待确认」章保留但不再拦门
        assertThat(body)
                .contains("补全集中呈现在 PRD")
                .contains("不区分用户答的与平台补的")
                .contains("保留但不再拦门")
                .contains("建议口径")
                // 旧两级门写法退役钉死（「阻碍核心流程」级不再进模板——必答题访谈期问掉）
                .doesNotContain("平台会拦截");
    }

    @Test
    void given_prd_writing_skill_when_read_body_then_style_direction_field_pinned() {
        String body = catalog.findAll().stream()
                .filter(skill -> skill.name().equals("prd-writing"))
                .findFirst().orElseThrow()
                .content();

        // #543 风格方向行（ADR-0052）：系统形项目从目录选定、关键约束章呈现、
        // 不占五问额度；纯系统形口径（系统＋设计走设计过程不写本行）钉死。
        // #551 补记（2026-11-18 收口赶工落缺省套实案）：必须选领域最匹配方向、
        // 缺省仅兜底、不得因赶工/省事选缺省——选取纪律句双源钉死
        assertThat(body)
                .contains("「关键约束」章的风格方向行")
                .contains("风格方向：")
                .contains("风格方向库·目录")
                .contains("只从目录选")
                .contains("不从零自造配色")
                .contains("不占访谈提问额度")
                .contains("必须选与项目领域气质最匹配的方向")
                .contains("缺省套仅当其余方向无一贴近领域气质时才兜底");
    }
}
