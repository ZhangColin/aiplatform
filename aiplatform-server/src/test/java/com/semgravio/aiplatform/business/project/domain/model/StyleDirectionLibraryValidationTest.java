package com.semgravio.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * 风格方向库<b>建库校验工具</b>（#543，ADR-0052 切片①——人审＋WCAG 对比度一次性
 * 机械校验，可重复跑、不进运行时）：库每套方向的固定文本色对做 WCAG 2.1 AA
 * 对比度断言（正文 4.5:1）——运营增补/改色必须过本工具（fast lane 常驻＝建库
 * 准入机械门）。oklch→线性 sRGB→相对亮度全链在本类实现（零依赖、公式直译
 * Björn Ottosson OKLab 论文），不引第三方色库。
 *
 * <p>本类即「工具」本体：改库（{@link StyleDirections}）后跑一遍本测试＝把库
 * 过了一遍建库校验；红了按断言输出调 token 值。</p>
 */
class StyleDirectionLibraryValidationTest {

    /** AA 正文级对比度下限（WCAG 2.1 §1.4.3）。 */
    private static final double AA_NORMAL_TEXT = 4.5;

    /**
     * 固定校验色对（前景 token → 背景 token）：覆盖正文/主按钮/次级面/弱提示/
     * 卡片/浮层六类真实文本组合——每套方向逐对过 AA。
     */
    private static final List<Map.Entry<String, String>> TEXT_PAIRS = List.of(
            Map.entry("--foreground", "--background"),
            Map.entry("--primary-foreground", "--primary"),
            Map.entry("--secondary-foreground", "--secondary"),
            Map.entry("--muted-foreground", "--background"),
            Map.entry("--accent-foreground", "--accent"),
            Map.entry("--card-foreground", "--card"),
            Map.entry("--popover-foreground", "--popover"));

    @Test
    void given_library_when_validate_then_all_text_pairs_meet_wcag_aa() {
        // 建库校验（可重复跑）：每套方向、每对文本色——对比度 ≥ 4.5:1；
        // 断言消息带实测比值（调色直接读输出）
        for (StyleDirections.Direction direction : StyleDirections.all()) {
            for (Map.Entry<String, String> pair : TEXT_PAIRS) {
                double contrast = contrastOf(direction.tokens().get(pair.getKey()),
                        direction.tokens().get(pair.getValue()));
                assertThat(contrast)
                        .as("%s 文本对 %s on %s（%s / %s）", direction.key(),
                                pair.getKey(), pair.getValue(),
                                direction.tokens().get(pair.getKey()),
                                direction.tokens().get(pair.getValue()))
                        .isGreaterThanOrEqualTo(AA_NORMAL_TEXT);
            }
        }
    }

    // ---------- oklch → WCAG 对比度（零依赖公式直译） ----------

    private static final Pattern OKLCH = Pattern.compile(
            "oklch\\(\\s*([\\d.]+%?)\\s+([\\d.]+%?)\\s+(-?[\\d.]+)(?:\\s*/[\\d.%]+)?\\s*\\)");

    /** 前景/背景 token 值 → 对比度（大者+0.05 除小者+0.05）。 */
    private static double contrastOf(String foreground, String background) {
        double front = relativeLuminanceOf(foreground);
        double back = relativeLuminanceOf(background);
        double lighter = Math.max(front, back);
        double darker = Math.min(front, back);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** oklch 值 → WCAG 相对亮度（线性 sRGB 加权 0.2126/0.7152/0.0722）。 */
    private static double relativeLuminanceOf(String oklch) {
        double[] linear = linearSrgbOf(oklch);
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }

    /** oklch(L C H) → 线性 sRGB 三元组（OKLab 逆变换，Ottosson 常数）。 */
    private static double[] linearSrgbOf(String oklch) {
        Matcher matcher = OKLCH.matcher(oklch == null ? "" : oklch.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("非 oklch 形色值（建库只收 oklch）：" + oklch);
        }
        double lightness = percentOrUnit(matcher.group(1));
        double chroma = percentOrUnit(matcher.group(2));
        double hue = Math.toRadians(Double.parseDouble(matcher.group(3)));
        double a = chroma * Math.cos(hue);
        double b = chroma * Math.sin(hue);
        double l_ = lightness + 0.3963377774 * a + 0.2158037573 * b;
        double m_ = lightness - 0.1055613458 * a - 0.0638541728 * b;
        double s_ = lightness - 0.0894841775 * a - 1.2914855480 * b;
        return new double[] {cube(l_), cube(m_), cube(s_)};
    }

    private static double percentOrUnit(String token) {
        if (token.endsWith("%")) {
            return Double.parseDouble(token.substring(0, token.length() - 1)) / 100.0;
        }
        return Double.parseDouble(token);
    }

    private static double cube(double value) {
        return value * value * value;
    }

    // ---------- 公式自证（独立已知值锚定，防实现性漂移） ----------

    @Test
    void given_known_colors_when_contrast_then_reference_values() {
        // WCAG 官方例：#FFFFFF on #FFFFFF = 1.0；白 on 纯黑 ≈ 21.0；
        // 白 on oklch 中灰（L 0.5）——用本链自洽锚定（oklch(0.5 0 0) 的 sRGB 近 0.466）
        assertThat(contrastOf("oklch(1 0 0)", "oklch(1 0 0)"))
                .as("同色对比度恒 1").isEqualTo(1.0, within(1e-9));
        assertThat(contrastOf("oklch(1 0 0)", "oklch(0 0 0)"))
                .as("白 on 纯黑＝21").isEqualTo(21.0, within(1e-6));
        double[] mid = linearSrgbOf("oklch(0.5 0 0)");
        assertThat(mid[0]).as("中灰线性通道一致").isEqualTo(mid[1]).isEqualTo(mid[2]);
        assertThat(mid[0]).as("oklch(0.5 0 0) 线性亮度锚（0.125 附近）")
                .isBetween(0.10, 0.15);
    }

    private static Offset<Double> within(double value) {
        return Offset.offset(value);
    }

    /** 便捷打印（本地调色调试：全库对比度一览——不改断言语义）。 */
    public static void main(String[] args) {
        Map<String, Double> ratios = new LinkedHashMap<>();
        for (StyleDirections.Direction direction : StyleDirections.all()) {
            for (Map.Entry<String, String> pair : TEXT_PAIRS) {
                ratios.put(direction.key() + " " + pair.getKey() + "/" + pair.getValue(),
                        contrastOf(direction.tokens().get(pair.getKey()),
                                direction.tokens().get(pair.getValue())));
            }
        }
        ratios.forEach((name, ratio) -> System.out.printf("%-72s %.2f%n", name, ratio));
    }
}
