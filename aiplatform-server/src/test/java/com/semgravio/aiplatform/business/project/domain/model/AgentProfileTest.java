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
                .contains("迭代受理")
                .contains("禁止把问题写在正文里") // #445：提问必须经 ask_user（格式失格治本条款）
                // #459：必答/选项题应给推荐项（recommended 标推荐答案＋问答卡预选缺省）
                .contains("推荐答案")
                .contains("recommended");
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
                .contains("禁裸色")
                // #425 执行体工作协议「用基座件、禁裸客户端」条目；#540 起单源化
                //（ADR-0051）：协议只留指针句——件清单/用法/纪律唯一正本＝工作区
                // AGENTS.md 本栈写法规则节（栈规则集随基座 tag 换版），不再重复
                // 枚举预置件路径（双正本必漂移）；lint 两档口径句维持（语义改写归
                // #541 lint 让路票）
                .contains("禁裸客户端")
                .contains("唯一正本＝工作区 AGENTS.md 的本栈写法规则节")
                .contains("开工先读该节")
                .contains("不重造轮子")
                .contains("照常自建")
                .doesNotContain("src/lib/robust")
                .doesNotContain("src/components/robust")
                .doesNotContain("httpFetch")
                .doesNotContain("AsyncSection")
                // 「预约通」实测（11-12）的语义边界对齐句随枚举移除一并归栈规则集
                //（节内断言见 StackRulesEntryPathsTest——「含页面/组件调自家 /api 路由」）
                .contains("本栈写法规则节")
                .contains("强制档")
                .contains("预置档");
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
        // ADR-0031 起缺省值＝档位语义键（flash——解析链在 ModelTierAppService）。
        assertThat(AgentProfile.EXECUTOR.modelId()).isEqualTo("flash");
    }

    @Test
    void given_seats_when_model_tier_then_independent_per_seat() {
        // #341 首 Demo/迭代任务模型档可独立于正式生产档配置：三座智能体各持自身
        // 缺省档位（运营配置面按 agentKey 各自覆盖——改一座不牵连他座），Demo 阶段
        // 缺省快模型档（本票），设计执行体不在本票范围（设计线独立、档位另配）。
        assertThat(AgentProfile.DESIGNER.modelId()).isEqualTo("pro");
        assertThat(AgentProfile.EXECUTOR.modelId())
                .isNotEqualTo(AgentProfile.DESIGNER.modelId());
    }

    @Test
    void given_executor_prompt_when_shell_command_then_quote_discipline_clause() {
        // #440 提示层（短平快）：容器命令一律单引号形态、禁内嵌双引号与 $()——
        // 违反会被执行层拒绝（fail-fast 正本在 DockerExecFilesystem ①族守卫）；
        // 条款须带改写指引与「拒绝后重试即可」口径（拒命不是终态错误）
        String prompt = AgentProfile.EXECUTOR.systemPrompt();
        assertThat(prompt)
                .contains("单引号")
                .contains("禁内嵌双引号")
                .contains("$(")
                .contains("执行层")
                .contains("拒绝")
                .contains("#440");
    }

    @Test
    void given_executor_prompt_when_complexity_governance_then_three_hard_lines_and_deferral_clause() {
        // ADR-0050 执行体工作协议改写：条 1 待定项最小可用缺省＋推迟清单消费句
        //（清单内不做、不自行加回）；条 3 收口判据＝三硬底线（撤「真实应用」厚度
        // 指令、带数据库降为手段）＋演示数据一轮建成＋真实感反 placeholder；
        // 机器探活三件套仍是唯一收口硬判据（回归不破）
        String prompt = AgentProfile.EXECUTOR.systemPrompt();
        assertThat(prompt)
                .contains("最小可用缺省")
                .contains("清单内不做、不自行加回")
                .contains("无此章")
                .contains("三硬底线")
                .contains("能打开不白屏")
                .contains("刷新不丢")
                .contains("核心路径可点")
                .contains("演示数据一轮建成")
                .contains("placeholder")
                .contains("唯一的收口硬要件")
                .doesNotContain("真实应用")
                .doesNotContain("拿不准的片建议验");
    }

    @Test
    void given_executor_prompt_when_verify_self_decision_then_neutral_wording() {
        // ADR-0050 条 9 撤回「拿不准的片建议验」自决建议（避免与运营开关关停打架）：
        // 验证不是收口要件、用不用由执行体自决——不自报建议、不劝验
        assertThat(AgentProfile.EXECUTOR.systemPrompt())
                .contains("验证不是收口要件")
                .contains("用不用由你自决")
                .doesNotContain("建议验")
                .doesNotContain("别省这一步");
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
