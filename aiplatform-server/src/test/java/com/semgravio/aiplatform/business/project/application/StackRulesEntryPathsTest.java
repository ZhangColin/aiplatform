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
                .as("HTTP 调用规则应点名预置入口 src/lib/http.ts（件＋指路双轮）")
                .contains("src/lib/http.ts");
    }

    /**
     * 语义边界对齐（「预约通」11-12 实测）：规则文本旧写「外部 HTTP」，run 执行体
     * 把页面调自家 /api 判为内部调用而裸写 fetch，被 bare-fetch 强制档拦下两段升档
     * （基座强制 7+4 处）。规则正本的判定边界必须与 lint 一致——不分内外。
     */
    @Test
    void given_stack_rules_section_when_http_scope_described_then_internal_api_calls_covered() {
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("HTTP 规则必须覆盖页面/组件调自家 /api——与 lint 强制档判定边界对齐")
                .contains("含页面/组件调自家 /api 路由")
                .contains("调自家 /api 也是 HTTP 调用");
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
