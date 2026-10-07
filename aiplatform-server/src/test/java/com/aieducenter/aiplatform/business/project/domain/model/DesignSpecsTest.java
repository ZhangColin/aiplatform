package com.aieducenter.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 设计规范提炼的规则单点（#295，ADR-0028）：界面类 :root 确定性直提（结构化
 * 直读、同稿同产出）、平面类出图参数随稿物化的路径派生与载荷形。纯函数面——
 * 协议保证形态、平台直读，不经模型判读；容器保真归 DesignSpecAppServiceLiveTest。
 */
class DesignSpecsTest {

    /** 真实形态稿（写稿协议：token 集中唯一 :root、正文引用变量不散落硬编码）。 */
    private static final String DRAFT_HTML = """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <style>
              :root {
                --background: #faf9f6;
                --foreground: #1c1917;
                --primary: #166534;
                --primary-foreground: #ffffff;
                --muted: #f5f5f4;
                --radius: 0.75rem;
                --font-sans: 'PingFang SC', 'Noto Sans SC', system-ui, sans-serif;
              }
              body { background: var(--background); color: var(--foreground);
                     border-radius: var(--radius); font-family: var(--font-sans); }
            </style>
            </head>
            <body><main>品牌主视觉</main></body>
            </html>
            """;

    @Test
    void given_root_block_when_extract_then_all_tokens_in_source_order() {
        // 灵魂用例（#295 验收②）：界面类确定性直提——首个 :root 块内全部
        // --token 按源序产出（非 token 声明不入集）；值原样（字体栈含逗号引号）
        Map<String, String> tokens = DesignSpecs.rootTokensOf(DRAFT_HTML);
        assertThat(tokens).containsExactly(
                Map.entry("--background", "#faf9f6"),
                Map.entry("--foreground", "#1c1917"),
                Map.entry("--primary", "#166534"),
                Map.entry("--primary-foreground", "#ffffff"),
                Map.entry("--muted", "#f5f5f4"),
                Map.entry("--radius", "0.75rem"),
                Map.entry("--font-sans",
                        "'PingFang SC', 'Noto Sans SC', system-ui, sans-serif"));
    }

    @Test
    void given_same_draft_when_extract_twice_then_identical() {
        // 确定性验收锚：同稿同产出（无模型调用、无随机面——两次直提逐位相等）
        assertThat(DesignSpecs.rootTokensOf(DRAFT_HTML))
                .isEqualTo(DesignSpecs.rootTokensOf(DRAFT_HTML));
    }

    @Test
    void given_no_root_block_or_blank_when_extract_then_empty() {
        assertThat(DesignSpecs.rootTokensOf("<!DOCTYPE html><p>无样式稿</p>")).isEmpty();
        assertThat(DesignSpecs.rootTokensOf("")).isEmpty();
        assertThat(DesignSpecs.rootTokensOf(null)).isEmpty();
        // :root 字样不在块内（选择器拼写偏离）＝协议偏离，零提取不猜
        assertThat(DesignSpecs.rootTokensOf("<style>:root --background: #fff;</style>"))
                .isEmpty();
    }

    @Test
    void given_multiple_root_blocks_when_extract_then_first_only() {
        // 多块＝协议偏离（约定唯一 :root）；确定性取首个，不并集不猜
        String html = """
                <style>
                :root { --primary: #111111; }
                :root { --primary: #222222; }
                </style>
                """;
        assertThat(DesignSpecs.rootTokensOf(html)).containsOnly(
                Map.entry("--primary", "#111111"));
    }

    @Test
    void given_comments_or_trailing_semicolonless_or_duplicates_when_extract_then_tolerant() {
        // 宽容只收字形变体：注释内伪声明剥离、末声明缺分号仍提取、块内同名后者
        // 覆盖（CSS 语义）；空值声明（`: ;`）如实不入集
        String html = """
                <style>
                :root {
                  /* --ghost: #000; 注释里的不是声明 */
                  --primary: #0f766e; /* 行尾注释 */
                  --radius: 1rem
                }
                </style>
                """;
        assertThat(DesignSpecs.rootTokensOf(html)).containsExactly(
                Map.entry("--primary", "#0f766e"),
                Map.entry("--radius", "1rem"));

        String duplicated = "<style>:root { --a: 1px; --a: 2px; }</style>";
        assertThat(DesignSpecs.rootTokensOf(duplicated)).containsOnly(
                Map.entry("--a", "2px"));

        String blankValue = "<style>:root { --empty: ; --real: #fff; }</style>";
        assertThat(DesignSpecs.rootTokensOf(blankValue)).containsOnly(
                Map.entry("--real", "#fff"));
    }

    @Test
    void given_image_path_when_sidecar_then_same_stem_spec_json() {
        // 平面类参数随稿物化：位图稿（图片模型路 TSID 落点名）→ 同词干侧车
        assertThat(DesignSpecs.sidecarPathOf("design/384926573-logo-a.png"))
                .isEqualTo("design/384926573-logo-a.spec.json");
        assertThat(DesignSpecs.sidecarPathOf("design/384926574-海报.webp"))
                .isEqualTo("design/384926574-海报.spec.json");
    }

    @Test
    void given_image_path_when_html_source_then_same_stem_html() {
        // 代码出图路回落：稿面是渲成 PNG（正身），:root 在同名 HTML 源里
        assertThat(DesignSpecs.htmlSourcePathOf("design/800x1200-poster.png"))
                .isEqualTo("design/800x1200-poster.html");
    }

    @Test
    void given_path_without_extension_when_derive_then_rejected() {
        assertThatThrownBy(() -> DesignSpecs.sidecarPathOf("design/logo"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DesignSpecs.htmlSourcePathOf("design/logo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_params_when_payload_then_json_form_single_source() {
        // 物化载荷形单点：{palette, style}——两面各按在场呈现；消费面（定稿转正）
        // 与生产面（出图物化）同一形状
        assertThat(DesignSpecs.sidecarPayload(List.of("#166534", "#faf9f6"), "扁平暖调"))
                .containsExactly(
                        Map.entry("palette", List.of("#166534", "#faf9f6")),
                        Map.entry("style", "扁平暖调"));
        assertThat(DesignSpecs.sidecarPayload(List.of("#166534"), null))
                .containsOnly(Map.entry("palette", List.of("#166534")));
        assertThat(DesignSpecs.sidecarPayload(null, "极简几何"))
                .containsOnly(Map.entry("style", "极简几何"));
    }

    @Test
    void given_params_when_blank_or_overflow_then_sanitized() {
        // 空白条目剔除、色板截 8（品牌附加色量级）、风格截 500（正本列宽）；
        // 两面皆空＝无设计意图，不物化空壳
        assertThat(DesignSpecs.sidecarPayload(
                List.of(" #166534 ", "", "  ", "#faf9f6"), null))
                .containsOnly(Map.entry("palette", List.of("#166534", "#faf9f6")));
        assertThat(DesignSpecs.sidecarPayload(null, "   ")).isEmpty();
        assertThat(DesignSpecs.sidecarPayload(List.of(), null)).isEmpty();
        List<?> clamped = (List<?>) DesignSpecs.sidecarPayload(
                List.of("#1", "#2", "#3", "#4", "#5", "#6", "#7", "#8", "#9", "#10"),
                null).get("palette");
        assertThat(clamped).hasSize(8);
        String clampedStyle = (String) DesignSpecs.sidecarPayload(null, "风".repeat(600))
                .get("style");
        assertThat(clampedStyle).hasSize(500);
    }

    @Test
    void given_relative_path_when_write_command_then_stdin_write_with_receipt() {
        // 侧车写入命令（对偶 ProjectMaterials.uploadCommand 形制）：单引号包裹
        // 绝对路径＋父目录幂等＋cat 接 stdin＋stat 字节回执
        String command = DesignSpecs.sidecarWriteCommand("design/384926573-logo.spec.json");
        assertThat(command)
                .contains("'/workspace/design/384926573-logo.spec.json'")
                .contains("mkdir -p")
                .contains("cat > \"$p\"")
                .contains("stat -c %s \"$p\"");
    }

    // ---------- 遵守面（#296 消费侧：:root 刷值 / 收窄块 / 品牌色 / 规则文件） ----------

    /** 真实形态基座 globals.css（#293 起基座即此形——物化的改写对象）。 */
    private static final String BASELINE_CSS = """
            @import "tailwindcss";
            @import "shadcn/tailwind.css";

            @custom-variant dark (&:is(.dark *));

            @theme inline {
              --color-primary: var(--primary);
              --color-muted-foreground: var(--muted-foreground);
            }

            :root {
              --primary: oklch(0.205 0 0);
              --muted-foreground: oklch(0.556 0 0);
              --radius: 0.625rem;
            }
            """;

    @Test
    void given_spec_tokens_when_refresh_root_then_values_swapped_and_rest_kept() {
        // 灵魂用例（#296 验收②：中途更新先刷值——语义引用不变时刷值即全局换肤）：
        // 已声明 token 原位换值、未声明的（--brand-1）追加进块尾、执行体自加
        // token（--chart-1）与注释原样保留；@theme inline 引用不动
        String refreshed = DesignSpecs.refreshRootValues(BASELINE_CSS, Map.of(
                "--primary", "oklch(0.55 0.2 260)",
                "--muted-foreground", "oklch(0.4 0 0)",
                "--brand-1", "#7c3aed"));
        assertThat(refreshed)
                .contains("--primary: oklch(0.55 0.2 260);")
                .contains("--muted-foreground: oklch(0.4 0 0);")
                .contains("--brand-1: #7c3aed;")
                .contains("--radius: 0.625rem;")
                .contains("--color-primary: var(--primary);")
                .doesNotContain("--primary: oklch(0.205 0 0)");
        // 幂等：同输入重复刷值稳定（重复派发＝重复刷值不叠加）
        assertThat(DesignSpecs.refreshRootValues(refreshed, Map.of(
                "--primary", "oklch(0.55 0.2 260)",
                "--muted-foreground", "oklch(0.4 0 0)",
                "--brand-1", "#7c3aed"))).isEqualTo(refreshed);
    }

    @Test
    void given_no_root_block_when_refresh_then_block_appended() {
        // 执行体重构了样式文件（:root 不在）＝整块追加在文件尾——生效位由平台落定
        String css = "@import \"tailwindcss\";\n\nbody { margin: 0; }\n";
        String refreshed = DesignSpecs.refreshRootValues(css, Map.of("--primary", "#123456"));
        assertThat(refreshed)
                .startsWith("@import \"tailwindcss\";")
                .endsWith(":root {\n  --primary: #123456;\n}\n")
                .contains("body { margin: 0; }");
    }

    @Test
    void given_blank_or_empty_when_refresh_then_unchanged() {
        assertThat(DesignSpecs.refreshRootValues(BASELINE_CSS, Map.of())).isEqualTo(BASELINE_CSS);
        assertThat(DesignSpecs.refreshRootValues(null, Map.of("--primary", "#fff"))).isNull();
    }

    @Test
    void given_no_spec_theme_when_upsert_then_inserted_after_imports_before_theme_inline() {
        // 首次物化：收窄块插在最后一条 @import 之后——必须先于 @theme inline
        // （实测序即语义：重置块在后会连语义映射一并清掉）
        String block = DesignSpecs.specThemeBlockOf(Map.of("--brand-1", "#7c3aed"));
        String css = DesignSpecs.upsertSpecTheme(BASELINE_CSS, block);
        assertThat(css.indexOf("@import \"shadcn/tailwind.css\";")).isLessThan(css.indexOf(DesignSpecs.SPEC_THEME_BEGIN));
        assertThat(css.indexOf(DesignSpecs.SPEC_THEME_END)).isLessThan(css.indexOf("@theme inline"));
        assertThat(css).contains("--color-*: initial;").contains("--color-brand-1: var(--brand-1);");
    }

    @Test
    void given_existing_spec_theme_when_upsert_then_replaced_wholesale() {
        // 再派发（新定稿刷新）：标记块整块替换——色板演进不残留旧映射
        String first = DesignSpecs.upsertSpecTheme(BASELINE_CSS,
                DesignSpecs.specThemeBlockOf(Map.of("--brand-1", "#111111", "--brand-2", "#222222")));
        String second = DesignSpecs.upsertSpecTheme(first,
                DesignSpecs.specThemeBlockOf(Map.of("--brand-9", "#999999")));
        assertThat(second).contains("--color-brand-9: var(--brand-9);");
        assertThat(second).doesNotContain("--brand-1").doesNotContain("--brand-2");
        // 标记块外的原内容不动（@import 与 @theme inline 保持唯一）
        assertThat(second.indexOf("@theme inline")).isGreaterThan(second.indexOf(DesignSpecs.SPEC_THEME_END));
        int imports = second.split("@import", -1).length - 1;
        assertThat(imports).isEqualTo(2);
    }

    @Test
    void given_tokens_when_spec_theme_block_then_only_brand_exposed() {
        // 品牌色暴露只认 --brand-* 命名约定（语义 token 已在 @theme inline——重复
        // 暴露无收益）；无品牌色＝仅收窄行
        String noBrand = DesignSpecs.specThemeBlockOf(Map.of("--primary", "#123", "--radius", "1rem"));
        assertThat(noBrand).contains("--color-*: initial;").doesNotContain("--color-primary");
    }

    @Test
    void given_palette_when_brand_tokens_then_order_deterministic() {
        // 参数面确定性映射：--brand-N 名序即色板序（不猜语义角色）；空白剔除、
        // 截 8 防御（正本列已截）
        assertThat(DesignSpecs.brandTokensOf(List.of("#7c3aed", " #0ea5e9 ", "")))
                .containsExactly(Map.entry("--brand-1", "#7c3aed"), Map.entry("--brand-2", "#0ea5e9"));
        assertThat(DesignSpecs.brandTokensOf(null)).isEmpty();
        List<String> ten = List.of("#1", "#2", "#3", "#4", "#5", "#6", "#7", "#8", "#9", "#10");
        assertThat(DesignSpecs.brandTokensOf(ten)).hasSize(8);
    }

    @Test
    void given_token_face_when_design_rules_then_table_and_terms_present() {
        // 规则文件（双件之规则面）：token 表直陈＋遵守条目常量＋视觉正源指路＋
        // 机器正本指路——平台确定性拼装、非模型动作
        String md = DesignSpecs.designRulesMarkdown("/design/home-1.html",
                Map.of("--primary", "#166534", "--brand-1", "#7c3aed"), null, null);
        assertThat(md)
                .contains("# 设计规范")
                .contains("/design/home-1.html")
                .contains("src/app/globals.css")
                .contains("| `--primary` | `#166534` |")
                .contains("token 优先")
                .contains("禁止裸色")
                .contains("--color-*: initial")
                .contains("@shadcn/lint")
                .doesNotContain("## 品牌色板");
    }

    @Test
    void given_params_face_when_design_rules_then_palette_and_style_sections() {
        // 参数面：品牌色板（含工具类指引）＋风格短语各按在场呈现
        String md = DesignSpecs.designRulesMarkdown("/design/logo-1.png", null,
                List.of("#7c3aed", "#0ea5e9"), "几何极简");
        assertThat(md)
                .contains("## 品牌色板")
                .contains("`--brand-1`：#7c3aed（工具类 bg-brand-1 等）")
                .contains("## 风格")
                .contains("几何极简")
                .doesNotContain("## 设计 token");
        // 仅风格正本（无色板）也成文——两面各按在场
        String styleOnly = DesignSpecs.designRulesMarkdown("/design/logo-2.png", null, null, "手写体温度");
        assertThat(styleOnly).contains("## 风格").doesNotContain("## 品牌色板");
    }
}
