package com.semgravio.aiplatform.base.skills.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.skills.domain.model.BuiltinSkill;

/**
 * 内置技能目录真资源面（#247 classpath {@code skills/}）：prd-writing 章节模板
 * 契约钉死——设计线整条退役后（#609；#615 清死分支）PRD 只有系统形八章节、
 * 清单章恒为功能清单，正文漂移即契约漂移；关键语句在场、退役文本缺席，逐条钉住。
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
    void given_prd_writing_skill_when_read_body_then_single_form_chapters_pinned() {
        String body = catalog.findAll().stream()
                .filter(skill -> skill.name().equals("prd-writing"))
                .findFirst().orElseThrow()
                .content();

        // 单形正本：系统形八章节、清单章恒为功能清单
        assertThat(body)
                .contains("固定章节依序为")
                .contains("需求背景", "目标用户", "核心场景", "范围边界")
                .contains("关键约束", "功能清单", "推迟清单", "验收与待确认")
                .contains("清单章为「功能清单」")
                .contains("系统生成的直接依据")
                // 设计形／设计物清单／终点类型走形随设计线整条退役退场（#609；#615）
                .doesNotContain("设计形")
                .doesNotContain("设计物清单")
                .doesNotContain("终点类型")
                .doesNotContain("设计硬约束");
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
    void given_prd_writing_skill_when_read_body_then_style_direction_line_retired() {
        String body = catalog.findAll().stream()
                .filter(skill -> skill.name().equals("prd-writing"))
                .findFirst().orElseThrow()
                .content();

        // #581 风格机制出局（PRD 风格口径＝风格词为准、零机制），#609 设计线退场后
        // 技能正文不再教 PRD 落风格方向行——#615 清死分支
        assertThat(body)
                .doesNotContain("风格方向")
                .doesNotContain("风格方向库·目录");
    }
}
