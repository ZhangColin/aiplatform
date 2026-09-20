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
                .contains("关键节点才解说"); // #225 叙说密度放宽：关键节点（开工/转向/失败/收口）才解说
    }

    @Test
    void given_main_prompt_when_capability_boundary_then_discipline_and_inventory_present() {
        // #216 能力边界正本：纪律条款（禁无验证的平台级断言 + 如实描述替代路径）+
        // 终态能力清单（抓取/调研/问答等全部已具备能力的边界描述终稿）
        String prompt = AgentProfile.MAIN.systemPrompt();
        assertThat(prompt).contains("能力边界正本");
        assertThat(prompt).contains("不得声称平台具备或缺乏"); // 禁「平台没有 X」式无验证断言
        assertThat(prompt).contains("我没有这个工具"); // 如实自述边界
        assertThat(prompt).contains("替代路径"); // 如实描述替代路径
        assertThat(prompt).contains("能力清单");
        // 外部资料能力（#213/#215 落地）是本票终态清单的净新增；其余工具名已由协议锚点
        // 测试与「单一事实」一致性测试覆盖，此处不重复断言
        assertThat(prompt)
                .contains("fetch_url")
                .contains("web_search");
    }
}
