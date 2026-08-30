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
}
