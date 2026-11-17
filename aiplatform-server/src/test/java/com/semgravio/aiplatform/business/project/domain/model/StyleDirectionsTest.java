package com.semgravio.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 风格方向库结构面（#543，ADR-0052 切片①——平台常量正本）：首批 8 套含缺省套、
 * 键/名唯一、双件齐全（token 值集覆盖基座语义变量全集＋人读规则句非空）、目录
 * 注入块单源呈现。对比度机械校验归 {@link StyleDirectionLibraryValidationTest}
 * （建库工具），解析行为（PRD 字段→方向、缺省兜底）同在本面钉死。
 */
class StyleDirectionsTest {

    /** 每套必备的基座语义变量全集（色板/字体对/圆角——物化直刷 :root 的面）。 */
    private static final List<String> CORE_TOKENS = List.of(
            "--background", "--foreground", "--card", "--card-foreground",
            "--popover", "--popover-foreground", "--primary", "--primary-foreground",
            "--secondary", "--secondary-foreground", "--muted", "--muted-foreground",
            "--accent", "--accent-foreground", "--border", "--input", "--ring",
            "--radius", "--font-sans");

    @Test
    void given_library_when_structure_then_eight_directions_with_default_and_unique_keys() {
        assertThat(StyleDirections.all()).hasSize(8);
        assertThat(StyleDirections.all())
                .extracting(StyleDirections.Direction::key).doesNotHaveDuplicates();
        assertThat(StyleDirections.all())
                .extracting(StyleDirections.Direction::name).doesNotHaveDuplicates();
        assertThat(StyleDirections.defaultDirection().key()).isEqualTo(StyleDirections.DEFAULT_KEY);
        assertThat(StyleDirections.all().get(0).key()).isEqualTo(StyleDirections.DEFAULT_KEY);
    }

    @Test
    void given_library_when_each_direction_then_double_piece_complete() {
        // 双件同构设计规范：token 值集（基座语义变量全集、值非空、oklch/字面值域）
        // ＋人读规则句非空＋领域气质谱系句非空
        for (StyleDirections.Direction direction : StyleDirections.all()) {
            assertThat(direction.tokens().keySet())
                    .as("%s 覆盖基座语义变量全集", direction.key())
                    .containsExactlyInAnyOrderElementsOf(CORE_TOKENS);
            direction.tokens().forEach((name, value) -> assertThat(value)
                    .as("%s 的 %s 值非空", direction.key(), name)
                    .isNotBlank());
            assertThat(direction.rules()).isNotBlank();
            assertThat(direction.domains()).isNotBlank();
        }
        // token 值域：颜色 token 一律 oklch 形（WCAG 工具只认此形）、圆角 rem 形、
        // 字体栈非空（离线容器安全——只引用系统/通用族）
        for (StyleDirections.Direction direction : StyleDirections.all()) {
            direction.tokens().forEach((name, value) -> {
                if (!name.equals("--radius") && !name.equals("--font-sans")) {
                    assertThat(value).as("%s 的 %s", direction.key(), name)
                            .startsWith("oklch(");
                }
            });
            assertThat(direction.tokens().get("--radius")).endsWith("rem");
            assertThat(direction.tokens().get("--font-sans"))
                    .satisfiesAnyOf(
                            font -> assertThat(font).contains("sans-serif"),
                            font -> assertThat(font).contains("serif"));
        }
    }

    @Test
    void given_prd_field_when_direction_of_prd_then_deterministic_parse_and_default_fallback() {
        // 关键约束节风格方向行解析：精确名命中、括号微调注释截停、列表符/加粗容忍
        String prd = """
                # PRD
                ## 关键约束
                - 风格方向：清新教育（微调：圆角再大一点）
                - 其他约束……
                """;
        assertThat(StyleDirections.directionOfPrd(prd).name()).isEqualTo("清新教育");

        assertThat(StyleDirections.directionOfPrd("## 关键约束\n**风格方向:** 沉稳金融\n").name())
                .isEqualTo("沉稳金融");

        // 兜底面：字段缺失/库无此名/空文本 → 缺省套（生成不被风格层卡死）
        assertThat(StyleDirections.directionOfPrd("# PRD\n## 关键约束\n- 无风格行\n"))
                .isEqualTo(StyleDirections.defaultDirection());
        assertThat(StyleDirections.directionOfPrd("## 关键约束\n- 风格方向：赛博朋克\n"))
                .isEqualTo(StyleDirections.defaultDirection());
        assertThat(StyleDirections.directionOfPrd(null))
                .isEqualTo(StyleDirections.defaultDirection());
        assertThat(StyleDirections.directionOfPrd(""))
                .isEqualTo(StyleDirections.defaultDirection());
    }

    @Test
    void given_field_outside_constraints_chapter_when_direction_of_prd_then_ignored() {
        // 字段单义锚在「关键约束」章：他章提及（功能清单备注等）不参与解析——
        // 关键约束章内的方向胜出；无章可锚（含他章孤行）＝缺省套兜底
        String prd = """
                # PRD
                ## 功能清单
                - 备注：风格方向：赛博朋克（本处仅为讨论提及，不构成选定）

                ## 关键约束
                - 风格方向：清新教育
                """;
        assertThat(StyleDirections.directionOfPrd(prd).name()).isEqualTo("清新教育");

        assertThat(StyleDirections.directionOfPrd(
                "## 功能清单\n- 风格方向：赛博朋克\n"))
                .isEqualTo(StyleDirections.defaultDirection());
    }

    @Test
    void given_catalog_when_markdown_then_single_source_block_with_all_names() {
        String catalog = StyleDirections.catalogMarkdown();
        assertThat(catalog)
                .contains("【风格方向库·目录】")
                .contains("风格方向：")
                .contains("关键约束");
        for (StyleDirections.Direction direction : StyleDirections.all()) {
            assertThat(catalog).contains("- " + direction.name() + "：");
        }
    }
}
