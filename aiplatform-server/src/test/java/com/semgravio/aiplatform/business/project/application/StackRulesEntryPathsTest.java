package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 栈规则集入口点名回归（#471 P1-b；#540 扩容至模式件/纪律句/应用壳惯用法）：
 * AGENTS.md 本栈写法规则节必须点名预置入口（{@code src/lib/db.ts}、
 * {@code src/lib/http.ts}、模式件两件）——预置件与规则指路是双轮：件在场而
 * 规则不指路，run 执行体仍按训练习惯自建入口文件（11-11 两项目＋11-12
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
    void given_stack_rules_section_when_pattern_components_present_then_entries_pointed() {
        // #540 模式件指路（件＋指路双轮同律）：首批两件必须在节内点名路径与用法
        // ——件在场而规则不指路，run 执行体仍按训练习惯自写表单/列表的状态骨架
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("表单模式件应点名 src/components/patterns/ValidatedForm")
                .contains("src/components/patterns/ValidatedForm")
                .as("列表模式件应点名 src/components/patterns/FilterableList")
                .contains("src/components/patterns/FilterableList");
    }

    /**
     * #540 纪律句（ADR-0051 复用纪律）：基座件不改约＋定制三通道封顶——
     * 告知通道单源的机械防线在检测落地（#541 路径清单拦截）之前先住在规则
     * 正本里；漏写＝执行体无从知晓「预置件内部不得编辑」的边界。
     */
    @Test
    void given_stack_rules_section_when_reuse_discipline_then_no_edit_covenant_present() {
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("纪律句应含基座件不改约与三通道定制口径")
                .contains("基座件不改约")
                .contains("不得编辑或删除")
                .contains("三通道");
    }

    /**
     * #540 应用壳惯用法（ADR-0051：布局/导航不预置整壳——单一化防线画在结构
     * 之下，只教做法不给件）：三类典型壳的组装指引进节，缺＝执行体无从参考
     * 而倾向重造整壳或千篇一律。
     */
    @Test
    void given_stack_rules_section_when_app_shell_idioms_then_three_kinds_present() {
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("应用壳惯用法应覆盖仪表盘壳/营销页/内容站三类")
                .contains("仪表盘壳")
                .contains("营销页")
                .contains("内容站")
                .contains("不预置整件");
    }

    @Test
    void given_stack_rules_section_when_pasted_into_heredoc_then_no_embedded_double_quotes() {
        assertThat(GenerationAppService.AGENTS_MD_STACK_RULES_SECTION)
                .as("节正文经 heredoc 写入工作区——内嵌双引号踩 Windows argv fail-fast 面（#399）")
                .doesNotContain("\"");
    }
}
