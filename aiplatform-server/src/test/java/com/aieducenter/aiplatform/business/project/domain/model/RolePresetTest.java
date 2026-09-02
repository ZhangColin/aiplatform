package com.aieducenter.aiplatform.business.project.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色卡 preset（代码配置不落库；v1 资产两个智能体——BA 访谈 + CODER 编码（#22
 * 落位），差异只在资产与工具集）。
 */
class RolePresetTest {

    @Test
    void given_presets_when_inspect_then_fully_configured() {
        assertThat(RolePreset.values()).extracting(Enum::name)
                .containsExactly("BA", "CODER");

        for (RolePreset role : RolePreset.values()) {
            assertThat(role.getName()).isNotBlank();
            assertThat(role.modelId()).isNotBlank();
            assertThat(role.systemPrompt()).isNotBlank();
        }
    }

    @Test
    void given_model_tier_when_inspect_then_ba_flash_coder_pro() {
        // 模型分档：文档类角色 flash（走链路优先），编码类角色 pro（实现质量优先）
        assertThat(RolePreset.BA.modelId()).isEqualTo("deepseek-v4-flash");
        assertThat(RolePreset.BA.chatModelString()).isEqualTo("deepseek:deepseek-v4-flash");
        assertThat(RolePreset.CODER.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(RolePreset.CODER.chatModelString()).isEqualTo("deepseek:deepseek-v4-pro");
    }

    @Test
    void given_name_when_by_name_then_resolved_or_empty() {
        assertThat(RolePreset.byName("BA")).contains(RolePreset.BA);
        assertThat(RolePreset.byName(" ba ")).contains(RolePreset.BA);
        assertThat(RolePreset.byName("coder")).contains(RolePreset.CODER);
        assertThat(RolePreset.byName("DEV")).isEmpty(); // 旧编码角色 preset 已随任务下发退场
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

    // ---------- BA systemPrompt 契约（#43 链必达：判定下放、派发归平台） ----------

    @Test
    void given_iteration_protocol_when_inspect_then_judgment_routed_without_markers() {
        // 判定内化 BA：统一受理、无需用户标注意见类型、无需逐条批准
        assertThat(PROMPT).contains("统一受理");
        assertThat(PROMPT).contains("不需要用户标注意见类型");
        assertThat(PROMPT).contains("无需逐条征求批准");
        // BA 只判需求侧：需求变更先改 PRD、拿不准先问；派发权归平台（角色卡明确
        // BA 无派发工具——防模型幻觉调用不存在的工具）
        assertThat(PROMPT).contains("需求侧判定");
        assertThat(PROMPT).contains("先按第 7 条修订 PRD");
        assertThat(PROMPT).contains("savePrd");
        assertThat(PROMPT).contains("由平台自动安排");
        assertThat(PROMPT).contains("没有任何派发修正的工具");
        assertThat(PROMPT).doesNotContain("startFixRun");
    }

    @Test
    void given_iteration_protocol_when_inspect_then_queue_merge_and_convergence_urge() {
        // run 进行中的新意见：受理不丢弃、下一轮合并处理（告知用户；排队合并由
        // 平台派发侧承载，BA 只如实告知）
        assertThat(PROMPT).contains("下一轮修正一并处理");
        // 迭代无次数上限 + 意见发散时催促收敛
        assertThat(PROMPT).contains("迭代轮数没有上限");
        assertThat(PROMPT).contains("催促收敛");
    }

    // ---------- CODER systemPrompt 契约（#22 生成环①） ----------

    private static final String CODER_PROMPT = RolePreset.CODER.systemPrompt();

    @Test
    void given_coder_protocol_when_inspect_then_prd_self_read_and_platform_conventions() {
        // PRD 自读（工作区 docs/PRD.md 是需求正本，平台不搬运）+ 平台技术约定注入
        assertThat(CODER_PROMPT).contains("docs/PRD.md");
        assertThat(CODER_PROMPT).contains("功能清单");
        assertThat(CODER_PROMPT).contains("TypeScript");
        assertThat(CODER_PROMPT).contains("DATABASE_URL");
        assertThat(CODER_PROMPT).contains("AGENTS.md");
    }

    @Test
    void given_coder_protocol_when_inspect_then_generation_and_fix_same_mechanism() {
        // 生成与修正同机制（#26）：修正任务来自任务说明，动手前重读 PRD（可能已修订）
        assertThat(CODER_PROMPT).contains("生成与修正同一套机制");
        assertThat(CODER_PROMPT).contains("修正任务");
        assertThat(CODER_PROMPT).contains("重读");
    }

    @Test
    void given_coder_protocol_when_inspect_then_service_ready_criteria() {
        // 收口判据：真实应用（带数据库、数据落得住）+ 8081 可访问才算完成
        assertThat(CODER_PROMPT).contains("0.0.0.0:8081");
        assertThat(CODER_PROMPT).contains("curl");
        assertThat(CODER_PROMPT).contains("初始数据");
        assertThat(CODER_PROMPT).contains("真实落库");
    }

    @Test
    void given_coder_protocol_when_inspect_then_service_started_early_and_evolves_incrementally() {
        // 起服节奏（#44 渐进预览前提）：一开工即以可运行形态起服务（空壳也可）、
        // 此后增量演进——不是写完全部代码最后才起服务；收口判据不因此放松
        assertThat(CODER_PROMPT).contains("一开工");
        assertThat(CODER_PROMPT).contains("增量演进");
        assertThat(CODER_PROMPT).contains("不要写完全部代码");
    }

    @Test
    void given_coder_protocol_when_inspect_then_narration_and_term_rules() {
        // 直播解说生产 = 智能体自述为主（#23 直播侧栏消费）；术语口径与 BA 同源
        assertThat(CODER_PROMPT).contains("自述");
        assertThat(CODER_PROMPT).contains("面向非技术");
        assertThat(CODER_PROMPT).contains("「系统」");
        assertThat(CODER_PROMPT).contains("Demo");
    }
}
