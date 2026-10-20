package com.aieducenter.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 智能体配置（#86 角色预设收敛为配置；#289 起三座——主智能体 / run 执行体 / 设计
 * 执行体）的稳定键
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
        assertThat(AgentProfile.byKey("designer")).contains(AgentProfile.DESIGNER); // #289 designer 座
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
                // #352 收口验证委派 verify 子智能体（自测位升级——独立验证三面）：
                // 失败项修复后复验、修不了如实标注
                .contains("verify")
                .contains("agent_spawn")
                .contains("复验")
                .contains("如实")
                .contains("关键节点才解说") // #225 叙说密度放宽：关键节点（开工/转向/失败/收口）才解说
                // #296 遵守三件套②（验收③）：设计规范短条目（短条目优于长段——
                // 条件式「若有 DESIGN.md」兼容无规范项目；缺省正本在此、运营
                // 配置面可覆盖＝调整不发版）
                .contains("DESIGN.md")
                .contains("token 优先")
                .contains("禁裸色");
        // #295 写稿协议（验收①）：token 集中 :root 条款（界面类确定性直提的协议
        // 保证面）＋平面类出图参数（palette/style 随稿物化）——缺省正本在此，
        // 运营配置面可覆盖（ADR-0021）
        assertThat(AgentProfile.DESIGNER.systemPrompt())
                .contains(":root")
                .contains("--primary")
                .contains("--brand-1")
                .contains("palette")
                .contains("定稿时转正");
    }

    @Test
    void given_demo_stage_when_executor_tier_then_fast_model_default() {
        // #341 Demo 阶段快模型档：Demo 阶段（首 Demo = PRD 确认触发的首个生成 run ＋
        // 迭代 = 更新 run）由 run 执行体承载，其缺省档 ＝ 快模型档（spec⑤「Demo 阶段
        // 激进用快模型，质量靠验证兜底」）——Demo 与正式生产同轨同协议，档位在既有
        // 智能体运营配置机制上人工定档（本票＝配置态初档；路由判定机制归 #354）。
        assertThat(AgentProfile.EXECUTOR.modelId()).isEqualTo("deepseek-v4-flash");
        assertThat(AgentProfile.EXECUTOR.chatModelString()).isEqualTo("deepseek:deepseek-v4-flash");
    }

    @Test
    void given_seats_when_model_tier_then_independent_per_seat() {
        // #341 首 Demo/迭代任务模型档可独立于正式生产档配置：三座智能体各持自身
        // 缺省档位（运营配置面按 agentKey 各自覆盖——改一座不牵连他座），Demo 阶段
        // 缺省快模型档（本票），设计执行体不在本票范围（设计线独立、档位另配）。
        assertThat(AgentProfile.DESIGNER.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(AgentProfile.EXECUTOR.modelId())
                .isNotEqualTo(AgentProfile.DESIGNER.modelId());
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
