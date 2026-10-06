package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.model.DesignScope;

/**
 * 设计清单解析（#289 首产推进序的清单源）：章界（任意层级标题行定界、下一章止）、
 * 编号条目（首行全文＝条目、出现序即推进序）、终点类型选章（设计主线＝设计物
 * 清单／系统＋设计＝功能清单按范围圈定——标签精确匹配、对照不上照录不漏做）、
 * 无章/无条目＝空清单（不造假清单）。解析口径与前端 feature-list.ts 同源（#285）。
 */
class DesignChecklistTest {

    private static final String DESIGN_PRD = """
            # 毛巾品牌设计需求

            ## 需求背景
            品牌初创，需要一套视觉资产。

            ## 设计物清单

            1. 品牌主 logo——用于包装与门头，方形构图，扁平风格，验收：单色下仍可辨
            2. 产品线海报（浴室场景）——用于电商详情页首屏，竖版 1242×1660，验收：主体清晰
            3. 包装盒展开图——毛巾礼盒，横版，验收：折叠线位置合理

            ## 待定项
            暂无
            """;

    private static final String SYSTEM_PRD = """
            # 门店管理系统 PRD

            ## 功能清单

            1. 用户能注册登录
            2. 用户能浏览商品下单
            3. 用户能查看订单与申请售后
            4. 店主能管理库存

            ## 关键约束
            设计硬约束：系统构建遵循本项目定稿设计。
            """;

    @Test
    void given_design_endpoint_prd_when_parse_then_checklist_items_in_order() {
        // 设计主线：读「设计物清单」章——编号条目按出现序即推进序（首行全文＝条目）
        DesignChecklist checklist = DesignChecklist.parse(DESIGN_PRD,
                ProjectEndpointType.DESIGN, null);
        assertThat(checklist.items()).containsExactly(
                "品牌主 logo——用于包装与门头，方形构图，扁平风格，验收：单色下仍可辨",
                "产品线海报（浴室场景）——用于电商详情页首屏，竖版 1242×1660，验收：主体清晰",
                "包装盒展开图——毛巾礼盒，横版，验收：折叠线位置合理");
    }

    @Test
    void given_system_design_all_pages_when_parse_then_feature_list_all() {
        // 系统＋设计·全部页面（含无范围记录）：功能清单全量、清单序即推进序
        assertThat(DesignChecklist.parse(SYSTEM_PRD, ProjectEndpointType.SYSTEM_DESIGN, null)
                .items())
                .containsExactly("用户能注册登录", "用户能浏览商品下单",
                        "用户能查看订单与申请售后", "店主能管理库存");
        assertThat(DesignChecklist.parse(SYSTEM_PRD, ProjectEndpointType.SYSTEM_DESIGN,
                        DesignScope.allPages()).items())
                .hasSize(4);
    }

    @Test
    void given_system_design_selected_pages_when_parse_then_anchored_subset() {
        // 系统＋设计·勾选页面：清单条目按标签精确匹配过滤（清单序保留），对照不上
        // 的标签照录缀后（标签锚漂移不静默丢页面——多跑不漏做）
        DesignScope scope = DesignScope.selected(List.of("用户能浏览商品下单", "用户能注册登录"));
        assertThat(DesignChecklist.parse(SYSTEM_PRD, ProjectEndpointType.SYSTEM_DESIGN, scope)
                .items())
                .containsExactly("用户能注册登录", "用户能浏览商品下单");

        // 标签漂移（PRD 演进后措辞变了）：匹配不上的标签照录——用户勾过的页面不消失
        DesignScope drifted = DesignScope.selected(List.of("用户能浏览商品", "店主能管理库存"));
        assertThat(DesignChecklist.parse(SYSTEM_PRD, ProjectEndpointType.SYSTEM_DESIGN, drifted)
                .items())
                .containsExactly("店主能管理库存", "用户能浏览商品");
    }

    @Test
    void given_design_prd_when_parse_system_design_then_feature_chapter_used() {
        // 选章跟终点类型走：系统＋设计读功能清单章（即使 PRD 同时有设计物清单章——
        // 「设计物清单」章只在设计主线 PRD 存在，ADR-0024 不两处正本）
        String bothChapters = DESIGN_PRD + "\n## 功能清单\n\n1. 用户能注册登录\n";
        assertThat(DesignChecklist.parse(bothChapters, ProjectEndpointType.SYSTEM_DESIGN, null)
                .items()).containsExactly("用户能注册登录");
        assertThat(DesignChecklist.parse(bothChapters, ProjectEndpointType.DESIGN, null).items())
                .hasSize(3);
    }

    @Test
    void given_missing_chapter_or_items_when_parse_then_empty() {
        // 无 PRD／无该章／无编号条目 → 空清单（调用侧如实不派发，不造假清单）
        assertThat(DesignChecklist.parse(null, ProjectEndpointType.DESIGN, null).items())
                .isEmpty();
        assertThat(DesignChecklist.parse("## 需求背景\n只有一章", ProjectEndpointType.DESIGN,
                null).items()).isEmpty();
        assertThat(DesignChecklist.parse("## 设计物清单\n\n无编号的段落", ProjectEndpointType.DESIGN,
                null).items()).isEmpty();
    }

    @Test
    void given_chapter_boundaries_when_parse_then_next_heading_ends_chapter() {
        // 章界：任意层级标题行（含 ###）止章；章标题自身任意层级可匹配；条目跨
        // 全角/半角编号形态
        String prd = """
                # PRD

                ### 设计物清单

                1、品牌主 logo
                2．辅助图形

                ### 待定项

                1. 不进清单的待定条目
                """;
        assertThat(DesignChecklist.parse(prd, ProjectEndpointType.DESIGN, null).items())
                .containsExactly("品牌主 logo", "辅助图形");
    }

    @Test
    void given_overlong_item_when_parse_then_truncated_to_column_limit() {
        // 超长条目截断保落库可行（与轨道表 title 列对齐）
        String longItem = "a".repeat(600);
        String prd = "## 设计物清单\n\n1. " + longItem + "\n";
        assertThat(DesignChecklist.parse(prd, ProjectEndpointType.DESIGN, null).items())
                .containsExactly("a".repeat(500));
    }
}
