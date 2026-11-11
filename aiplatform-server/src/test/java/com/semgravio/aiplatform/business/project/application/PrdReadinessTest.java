package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.project.domain.model.ReadinessDimension;

/**
 * 「验收与待确认」章解析（#331 定界与条目解析沿用；<b>#462 去门化：级分组
 * 语义删除</b>——ADR-0039：「阻碍核心流程/次要未知」子标题只作界标不再分级，
 * 章内条目统一归待确认池（minor），blocking 恒空（响应形状保留兼容）、维度态
 * 不再产 BLOCKING）。解析面保留：章标题定界（任意层级、序号前缀容忍——#304
 * 同源教训）、编号条目（首行全文）＋前导【维度】标注（生成条件五要素词表）、
 * 维度态按维度聚合（仅 ⚡/✓ 两态产出，五维固定序）。章缺失/形态漂移 fail-open
 * 方向不变：条目尽力而归次要，不误丢。
 */
class PrdReadinessTest {

    // ---------- 灵魂例：真实形态正例（序号前缀＋旧分级子标题＋【维度】＋强调＋子项缩进） ----------

    @Test
    void given_real_form_chapter_when_parse_then_items_flattened_to_minor_and_dimensions_aggregated() {
        // 真实走查形态同源（#304 项目 367868027623310018）：主智能体按中文文档习惯
        // 给七章节加序号「## 七、验收与待确认」；条目可带 ** 强调；条目下缩进子项
        // 是说明行不是条目。旧两级子标题在场＝界标容忍（#462 不分级，全归待确认池）
        String prd = """
                # 巾上添花 · 需求文档（PRD）

                ## 六、功能清单

                1. **首页——品牌展示**
                   - 用途：展示品牌故事。
                2. **下单页——微信支付**

                ## 七、验收与待确认

                ### 阻碍核心流程

                1. 【主流程】**下单后的发货方式未确认**（自提还是快递）
                   - 不确认则核心交易闭环无法确定
                2. 【数据与约束】是否需要留存用户手机号未确认

                ### 次要未知

                1. 【验收】列表默认排序偏好未确认（假设：按创建时间倒序）
                2. 【目标与角色】访客的浏览权限范围（假设：全部只读）
                """;
        PrdReadiness readiness = PrdReadiness.parse(prd);

        // #462 去门化：级分组语义删除——「阻碍核心流程」子标题只作界标，条目
        // 与「次要未知」同归统一待确认池（全文保持原样、前导【维度】剥离挂维、
        // 子项缩进行不进条目）
        assertThat(readiness.blocking()).isEmpty(); // 恒空（响应形状保留兼容）
        assertThat(readiness.minor()).containsExactly(
                new PrdReadiness.PendingItem(ReadinessDimension.MAIN_FLOW,
                        "**下单后的发货方式未确认**（自提还是快递）"),
                new PrdReadiness.PendingItem(ReadinessDimension.DATA_CONSTRAINT,
                        "是否需要留存用户手机号未确认"),
                new PrdReadiness.PendingItem(ReadinessDimension.ACCEPTANCE,
                        "列表默认排序偏好未确认（假设：按创建时间倒序）"),
                new PrdReadiness.PendingItem(ReadinessDimension.GOAL_ROLE,
                        "访客的浏览权限范围（假设：全部只读）"));
        // 维度态＝五维固定序聚合：待确认项挂维维→MINOR（⚡）、无缺项→READY（✓）——
        // 「n/5 维度就绪」＝READY 计数（呈现面自数）；BLOCKING 枚举保留但不再产出
        assertThat(readiness.dimensions()).containsExactly(
                new PrdReadiness.DimensionState(ReadinessDimension.GOAL_ROLE, PrdReadiness.Status.MINOR),
                new PrdReadiness.DimensionState(ReadinessDimension.MAIN_FLOW, PrdReadiness.Status.MINOR),
                new PrdReadiness.DimensionState(ReadinessDimension.FEATURE_BOUNDARY, PrdReadiness.Status.READY),
                new PrdReadiness.DimensionState(ReadinessDimension.DATA_CONSTRAINT, PrdReadiness.Status.MINOR),
                new PrdReadiness.DimensionState(ReadinessDimension.ACCEPTANCE, PrdReadiness.Status.MINOR));
    }

    // ---------- fail-open：章缺失（含存量项目旧「待定项」章）不产条目 ----------

    @Test
    void given_chapter_missing_when_parse_then_no_items_and_all_ready() {
        // 存量项目真实场景：旧模板产出「待定项」章（无分级无维度），现行解析器不认
        // ——fail-open 放行（不误丢、不臆造）
        String legacyPrd = """
                # PRD

                ## 功能清单

                1. 用户能注册登录

                ## 待定项

                1. 发货方式还没确认
                """;
        assertThat(PrdReadiness.parse(legacyPrd).blocking()).isEmpty();
        assertThat(PrdReadiness.parse(legacyPrd).minor()).isEmpty();
        assertThat(PrdReadiness.parse(legacyPrd).dimensions())
                .allSatisfy(state -> assertThat(state.status()).isEqualTo(PrdReadiness.Status.READY));

        // 无 PRD / 空文同口径
        assertThat(PrdReadiness.parse(null).blocking()).isEmpty();
        assertThat(PrdReadiness.parse("").minor()).isEmpty();
    }

    // ---------- 「暂无」章（提示词钉的无缺项写法）＝零条目全就绪 ----------

    @Test
    void given_none_pending_chapter_when_parse_then_empty_items_all_ready() {
        String prd = """
                # PRD

                ## 验收与待确认

                暂无
                """;
        PrdReadiness readiness = PrdReadiness.parse(prd);
        assertThat(readiness.blocking()).isEmpty();
        assertThat(readiness.minor()).isEmpty();
        assertThat(readiness.dimensions())
                .allSatisfy(state -> assertThat(state.status()).isEqualTo(PrdReadiness.Status.READY));
    }

    // ---------- 伪章负例：子串形章名不出条目（受限宽容，#304 同源口径） ----------

    @Test
    void given_chapter_title_as_substring_when_parse_then_not_matched() {
        // 章名精确＋行尾锚定：「验收与待确认说明」「验收与待确认（初稿）」不是本章
        String prd = """
                # PRD

                ## 七、验收与待确认说明

                1. 【主流程】不该进本章的条目

                ## 验收与待确认（初稿）

                1. 【验收】也不该进
                """;
        PrdReadiness readiness = PrdReadiness.parse(prd);
        assertThat(readiness.blocking()).isEmpty();
        assertThat(readiness.minor()).isEmpty();
    }

    // ---------- 形态漂移：级分组子标题对不上 → 条目归待确认池（fail-open 方向钉死） ----------

    @Test
    void given_drifted_group_headings_when_parse_then_items_minor() {
        // 章在、分组子标题措辞漂移（「### 待补」「### 风险」对不上阻碍/次要锚）：
        // 界标失效——条目照进统一待确认池（不误丢）；未分组条目同归
        String prd = """
                # PRD

                ## 验收与待确认

                ### 待补

                1. 【主流程】发货方式未确认

                ### 风险

                2. 【验收】排序偏好未确认
                """;
        PrdReadiness readiness = PrdReadiness.parse(prd);
        assertThat(readiness.blocking()).isEmpty();
        assertThat(readiness.minor()).hasSize(2);
        // 维度态如实反映待确认项（【主流程】【验收】条目→MINOR/⚡——收敛度呈现
        // 不因漂移失真）
        assertThat(readiness.dimensions()).contains(
                new PrdReadiness.DimensionState(ReadinessDimension.MAIN_FLOW, PrdReadiness.Status.MINOR),
                new PrdReadiness.DimensionState(ReadinessDimension.ACCEPTANCE, PrdReadiness.Status.MINOR));
    }

    @Test
    void given_items_without_group_heading_when_parse_then_minor_fallopen() {
        // 章标题后直接条目（无分组子标题）：形态漂移的兜底形——全归待确认池
        String prd = "## 验收与待确认\n\n1. 【主流程】发货方式未确认\n";
        PrdReadiness readiness = PrdReadiness.parse(prd);
        assertThat(readiness.blocking()).isEmpty();
        assertThat(readiness.minor()).containsExactly(new PrdReadiness.PendingItem(
                ReadinessDimension.MAIN_FLOW, "发货方式未确认"));
    }

    // ---------- 【维度】标注缺失/未命中：条目照进清单、不挂维、原文保留 ----------

    @Test
    void given_unlabeled_or_unknown_tag_when_parse_then_item_kept_without_dimension() {
        String prd = """
                # PRD

                ## 验收与待确认

                ### 阻碍核心流程

                1. 【主流程】发货方式未确认
                2. 未标注维度的缺项
                3. 【重要】非维度标注保留原文

                ### 次要未知

                4. 【验收】排序偏好未确认
                """;
        PrdReadiness readiness = PrdReadiness.parse(prd);
        // #462 不分级：旧「阻碍核心流程」组条目同归待确认池（恒空形状不变）
        assertThat(readiness.blocking()).isEmpty();
        assertThat(readiness.minor()).containsExactly(
                new PrdReadiness.PendingItem(ReadinessDimension.MAIN_FLOW, "发货方式未确认"),
                new PrdReadiness.PendingItem(null, "未标注维度的缺项"),
                new PrdReadiness.PendingItem(null, "【重要】非维度标注保留原文"),
                new PrdReadiness.PendingItem(ReadinessDimension.ACCEPTANCE, "排序偏好未确认"));
        // 未挂维条目不进维度态：主流程/验收按已挂维条目聚合、其余 READY
        assertThat(readiness.dimensions()).containsExactly(
                new PrdReadiness.DimensionState(ReadinessDimension.GOAL_ROLE, PrdReadiness.Status.READY),
                new PrdReadiness.DimensionState(ReadinessDimension.MAIN_FLOW, PrdReadiness.Status.MINOR),
                new PrdReadiness.DimensionState(ReadinessDimension.FEATURE_BOUNDARY, PrdReadiness.Status.READY),
                new PrdReadiness.DimensionState(ReadinessDimension.DATA_CONSTRAINT, PrdReadiness.Status.READY),
                new PrdReadiness.DimensionState(ReadinessDimension.ACCEPTANCE, PrdReadiness.Status.MINOR));
    }

    // ---------- 章界：下一章标题止章（章内非分组标题＝章界，同 DesignChecklist） ----------

    @Test
    void given_next_chapter_after_items_when_parse_then_chapter_ends_at_heading() {
        String prd = """
                # PRD

                ## 七、验收与待确认

                ### 次要未知

                1. 【验收】排序偏好未确认

                ## 附录

                2. 【主流程】附录里的编号行不该进章
                """;
        PrdReadiness readiness = PrdReadiness.parse(prd);
        assertThat(readiness.minor()).containsExactly(
                new PrdReadiness.PendingItem(ReadinessDimension.ACCEPTANCE, "排序偏好未确认"));
        assertThat(readiness.blocking()).isEmpty();
    }

    // ---------- 同维多条：聚合为待确认（不再分级的同维归并面） ----------

    @Test
    void given_same_dimension_multiple_items_when_aggregate_then_minor_once() {
        // #462 起级分组删除：同维条目不再「阻碍压过次要」，统一 MINOR 单态聚合
        String prd = """
                # PRD

                ## 验收与待确认

                ### 阻碍核心流程

                1. 【主流程】发货方式未确认

                ### 次要未知

                2. 【主流程】发票抬头收集时机（假设：下单后补填）
                """;
        PrdReadiness readiness = PrdReadiness.parse(prd);
        assertThat(readiness.blocking()).isEmpty();
        assertThat(readiness.dimensions()).contains(
                new PrdReadiness.DimensionState(ReadinessDimension.MAIN_FLOW, PrdReadiness.Status.MINOR));
    }
}
