package com.aieducenter.aiplatform.business.project.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色卡 preset（代码配置不落库；v1 资产里只有 BA 一个入口智能体——编码智能体
 * 资产随生成环落位）。
 */
class RolePresetTest {

    @Test
    void given_presets_when_inspect_then_ba_fully_configured() {
        assertThat(RolePreset.values()).extracting(Enum::name)
                .containsExactly("BA");

        for (RolePreset role : RolePreset.values()) {
            assertThat(role.getName()).isNotBlank();
            assertThat(role.modelId()).isNotBlank();
            assertThat(role.systemPrompt()).isNotBlank();
        }
    }

    @Test
    void given_model_tier_when_inspect_then_ba_flash() {
        // 文档类角色 flash（走链路优先）
        assertThat(RolePreset.BA.modelId()).isEqualTo("deepseek-v4-flash");
        assertThat(RolePreset.BA.chatModelString()).isEqualTo("deepseek:deepseek-v4-flash");
    }

    @Test
    void given_name_when_by_name_then_resolved_or_empty() {
        assertThat(RolePreset.byName("BA")).contains(RolePreset.BA);
        assertThat(RolePreset.byName(" ba ")).contains(RolePreset.BA);
        assertThat(RolePreset.byName("DEV")).isEmpty(); // 编码角色 preset 已随任务下发退场
        assertThat(RolePreset.byName(null)).isEmpty();
        assertThat(RolePreset.byName("")).isEmpty();
    }

    // ---------- BA systemPrompt 契约（#20 七章节 PRD 协议） ----------

    private static final String PROMPT = RolePreset.BA.systemPrompt();

    @Test
    void given_prd_protocol_when_inspect_then_all_seven_sections_declared() {
        // 七章节一个不少（旧版缺功能清单——#20 补齐为系统生成的直接依据）
        for (String section : new String[] {"需求背景", "目标用户", "核心场景", "范围边界",
                "关键约束", "功能清单", "待定项"}) {
            assertThat(PROMPT).contains(section);
        }
        // 功能清单章节的写法契约：编号列出 + 每点验收要点
        assertThat(PROMPT).contains("编号");
        assertThat(PROMPT).contains("验收要点");
    }

    @Test
    void given_prd_protocol_when_inspect_then_plain_language_and_term_rules() {
        // PRD 平实语言、面向非技术用户
        assertThat(PROMPT).contains("平实");
        assertThat(PROMPT).contains("非技术");
        // 术语口径：面向用户一律称「系统」、禁用 Demo/原型/样品
        assertThat(PROMPT).contains("「系统」");
        assertThat(PROMPT).contains("Demo");
        assertThat(PROMPT).contains("原型");
        assertThat(PROMPT).contains("样品");
    }

    @Test
    void given_prd_protocol_when_inspect_then_revision_summary_and_converge_on_demand() {
        // 修订回路：定位章节修订 + 会话内给修订摘要（改了哪些章节）
        assertThat(PROMPT).contains("修订摘要");
        // 催促收敛：立即收敛、缺口记入待定项；访谈轮数无上限
        assertThat(PROMPT).contains("待定项");
        assertThat(PROMPT).contains("多问");
    }
}
