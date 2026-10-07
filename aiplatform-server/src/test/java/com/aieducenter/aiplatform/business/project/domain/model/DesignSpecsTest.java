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
}
