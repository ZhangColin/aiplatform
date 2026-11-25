package com.semgravio.aiplatform.business.project.domain.model;

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
        assertThat(AgentProfile.byKey("designer")).isEmpty(); // #610 设计执行体座随设计线退役
    }

    @Test
    void given_unknown_or_blank_key_when_by_key_then_empty() {
        assertThat(AgentProfile.byKey(null)).isEmpty();
        assertThat(AgentProfile.byKey("")).isEmpty();
        assertThat(AgentProfile.byKey("ba")).isEmpty(); // 旧职能体键已退役，不回解
        assertThat(AgentProfile.byKey("assistant")).isEmpty();
    }

    @Test
    void given_refinement_kind_when_display_name_then_readable_name_while_auxiliary_stays_null() {
        // #641 事后提炼的成本切片要有可读的智能体种类名；naming/classify 维持「—」桶口径
        assertThat(AgentProfile.displayNameOf(UsageDims.AGENT_KIND_REFINEMENT))
                .isEqualTo(AgentEdgePoint.REFINEMENT.getName())
                .isEqualTo("候选经验提炼");
        assertThat(AgentProfile.displayNameOf(UsageDims.AGENT_KIND_NAMING)).isNull();
        assertThat(AgentProfile.displayNameOf(UsageDims.AGENT_KIND_CLASSIFY)).isNull();
    }

    @Test
    void given_profiles_when_prompts_then_protocol_anchors_present() {
        // 协议锚点抽查：主智能体配置含追问/答询/迭代受理三段（单会话连续）；
        // 设计执行体配置含写稿协议条款。run 执行体的协议正文已随 #604 退役
        //（缺省提示只作占位，锚点见退役占位用例）
        assertThat(AgentProfile.MAIN.systemPrompt())
                .contains("ask_user")
                .contains("savePrd")
                .contains("query_project_facts")
                .contains("迭代受理")
                .contains("禁止把问题写在正文里") // #445：提问必须经 ask_user（格式失格治本条款）
                // #459：必答/选项题应给推荐项（recommended 标推荐答案＋问答卡预选缺省）
                .contains("推荐答案")
                .contains("recommended");
        // EXECUTOR 座的协议锚点断言随 #604 删除（13 条执行协议正文退役——缺省提示
        // 只作退役占位，见 given_executor_when_prompt_then_retired_placeholder_without_legacy_protocol）；
        // #610 设计执行体（DESIGNER）座随设计线整条退役删除。
    }

    @Test
    void given_demo_stage_when_executor_tier_then_fast_model_default() {
        // #341 Demo 阶段快模型档：Demo 阶段（首 Demo = PRD 确认触发的首个生成 run ＋
        // 迭代 = 更新 run）由 run 执行体承载，其缺省档 ＝ 快模型档（spec⑤「Demo 阶段
        // 激进用快模型，质量靠验证兜底」）——Demo 与正式生产同轨同协议，配置在既有
        // 智能体运营配置机制上人工定档（本票＝配置态初档；路由判定机制归 #354）。
        // ADR-0031 起缺省值＝配置语义键（flash——解析链在 ModelConfigAppService）。
        assertThat(AgentProfile.EXECUTOR.modelConfigKey()).isEqualTo("flash");
    }

    @Test
    void given_designer_seat_retired_when_lookup_then_absent() {
        // #610 设计线整条退役：设计执行体座（designer）与出图档位配置一并拆除——
        // 配置面只余 main/executor 两座
        assertThat(AgentProfile.byKey("designer")).isEmpty();
        assertThat(AgentProfile.values())
                .noneMatch(profile -> "designer".equals(profile.key()));
    }

    @Test
    void given_executor_when_prompt_then_retired_placeholder_without_legacy_protocol() {
        // #604 拆 run 尝试体旧机制：EXECUTOR 座 13 条执行协议正文整体退役——生成/更新
        // run 经内核装配形态派发（系统提示＝KernelSystemPrompt 权威版本），本枚举条目
        // 保留 key/code/name/modelConfigKey 作 Demo 阶段模型配置槽位；缺省提示只是
        // 库值缺失时的占位，不得残留旧协议锚点（防旧正文按键挂回）。原钉旧协议措辞
        // 的用例（#440 命令引号纪律、ADR-0050 三硬底线与推迟清单、verify 自决中性
        // 措辞）随协议正文一并删除。
        String prompt = AgentProfile.EXECUTOR.systemPrompt();
        assertThat(prompt)
                .contains("#604")
                .contains("退役")
                .doesNotContain("finish_edit")
                .doesNotContain("agent_spawn")
                .doesNotContain("report_verification")
                .doesNotContain("DESIGN.md")
                .doesNotContain("三硬底线")
                .doesNotContain("verify");
        assertThat(AgentProfile.EXECUTOR.key()).isEqualTo("executor");
        assertThat(AgentProfile.EXECUTOR.modelConfigKey()).isEqualTo("flash");
    }

    @Test
    void given_main_prompt_when_new_interview_strategy_then_must_answer_completion_convergence_present() {
        // #461 新访谈策略（#449 设计定案）：必答收窄为两类口径（缺了会建错方向／涉用户
        // 独特事实与偏好）、补全以专业身份直陈（禁假设/自问自答字眼、集中落 PRD、
        // 不区分用户答的与平台补的）、收敛令（「直接做/别问了」类输入后未决缺口按
        // 推荐补全收口）、题量软目标非硬上限、零必答首轮直接产 PRD；每轮一问硬契约
        // 与「禁止把问题写在正文里」维持不动
        String prompt = AgentProfile.MAIN.systemPrompt();
        assertThat(prompt)
                .contains("缺了会建错方向")
                .contains("涉用户独特事实与偏好")
                .contains("补全")
                .contains("我会怎么做")
                .contains("不区分用户答的与平台补的")
                .contains("收敛")
                .contains("软目标")
                .contains("零必答")
                .contains("每轮一问")
                // 旧口径退役钉死：不再「宁可多问一轮、不得替用户假设后收敛」
                .doesNotContain("宁可多问一轮")
                .doesNotContain("不得替用户假设");
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
