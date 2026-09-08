package com.aieducenter.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 智能体配置（#86 角色预设收敛为配置）：两座（主智能体 / run 执行体）的稳定键
 * 是工具集装配、run-start 载荷与计量 dims 的共用寻址腿；byKey 是计量读侧的
 * 回解口（未知/空键返回空——辅助调用 classify/naming 无展示名）。
 */
class AgentProfileTest {

    @Test
    void given_profiles_when_key_then_stable_lowercase() {
        assertThat(AgentProfile.MAIN.key()).isEqualTo("main");
        assertThat(AgentProfile.EXECUTOR.key()).isEqualTo("executor");
    }

    @Test
    void given_key_when_by_key_then_resolved_case_insensitive() {
        assertThat(AgentProfile.byKey("main")).contains(AgentProfile.MAIN);
        assertThat(AgentProfile.byKey("EXECUTOR")).contains(AgentProfile.EXECUTOR);
        assertThat(AgentProfile.byKey("  Main ")).contains(AgentProfile.MAIN);
    }

    @Test
    void given_unknown_or_blank_key_when_by_key_then_empty() {
        assertThat(AgentProfile.byKey(null)).isEmpty();
        assertThat(AgentProfile.byKey("")).isEmpty();
        assertThat(AgentProfile.byKey("ba")).isEmpty(); // 旧职能体键已退役，不回解
        assertThat(AgentProfile.byKey("assistant")).isEmpty();
    }

    @Test
    void given_profiles_when_prompts_then_protocol_anchors_present() {
        // 协议锚点抽查：主智能体配置含追问/答询/迭代受理三段（单会话连续），
        // 执行体配置含 finish_edit 判定契约
        assertThat(AgentProfile.MAIN.systemPrompt())
                .contains("ask_user")
                .contains("savePrd")
                .contains("query_project_facts")
                .contains("迭代受理");
        assertThat(AgentProfile.EXECUTOR.systemPrompt())
                .contains("finish_edit")
                .contains("self-test") // #96 自检段委派自测子智能体
                .contains("agent_spawn")
                .contains("先解说后动手"); // #119 解说密实化：每工作段先解说后动手
    }
}
