package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 栈规则集入口点名回归（#471 P1-b）：AGENTS.md 本栈写法规则节必须点名预置入口
 * （{@code src/lib/db.ts}、{@code src/lib/http.ts}）——预置件与规则指路是双轮：
 * 件在场而规则不指路，run 执行体仍按训练习惯自建入口文件（11-11 两项目＋11-12
 * 易记单词本三连中的实测形态：教学在协议里、规则在 AGENTS.md 里，仍先写了裸
 * 客户端才被收口拦下）。点名口径与正文纪律同节正本：无用户可控片段、无内嵌
 * 双引号（Windows argv fail-fast 面）。
 */
class StackRulesEntryPathsTest {

    @Test
    void given_stack_rules_section_when_named_entries_present_then_db_entry_pointed() {
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("数据访问规则应点名预置入口 src/lib/db.ts（件＋指路双轮）")
                .contains("src/lib/db.ts");
    }

    @Test
    void given_stack_rules_section_when_named_entries_present_then_http_entry_pointed() {
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("外部 HTTP 规则应点名预置入口 src/lib/http.ts（件＋指路双轮）")
                .contains("src/lib/http.ts");
    }

    @Test
    void given_stack_rules_section_when_named_entries_present_then_auth_entry_pointed() {
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("会话与密码规则应点名预置入口 src/lib/auth.ts（件＋指路双轮）")
                .contains("src/lib/auth.ts");
    }

    @Test
    void given_stack_rules_section_when_pasted_into_heredoc_then_no_embedded_double_quotes() {
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("节正文经 heredoc 写入工作区——内嵌双引号踩 Windows argv fail-fast 面（#399）")
                .doesNotContain("\"");
    }
}
