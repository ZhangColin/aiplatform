package com.aieducenter.aiplatform.business.project.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平面稿规则单点（#292）：画幅声明的确定性直提（协议形全收、结构性变形不认）、
 * HTML 稿判定、同名 PNG 派生、首行读取命令正本。
 */
class DesignPrintsTest {

    @Test
    void given_canonical_declaration_when_parse_then_size_present() {
        assertThat(DesignPrints.declaredSizeOf("<!-- print: 800x1200 -->"))
                .contains(new DesignPrints.PrintSize(800, 1200));
    }

    @Test
    void given_glyph_variants_when_parse_then_all_accepted() {
        // 协议原文是半角冒号＋半角 x；宽容只收字形变体（全角冒号、×/X 分隔、空白伸缩、BOM）
        assertThat(DesignPrints.declaredSizeOf("<!--print:1080x1920-->"))
                .contains(new DesignPrints.PrintSize(1080, 1920));
        assertThat(DesignPrints.declaredSizeOf("<!-- print：800X600 -->"))
                .contains(new DesignPrints.PrintSize(800, 600));
        assertThat(DesignPrints.declaredSizeOf("<!-- print: 800×600 -->"))
                .contains(new DesignPrints.PrintSize(800, 600));
        assertThat(DesignPrints.declaredSizeOf("   <!-- print: 100 x 200 -->   "))
                .contains(new DesignPrints.PrintSize(100, 200));
        assertThat(DesignPrints.declaredSizeOf("﻿<!-- print: 750x1334 -->"))
                .contains(new DesignPrints.PrintSize(750, 1334));
        // 首行读取命令的 stdout 携尾换行——解析层容忍
        assertThat(DesignPrints.declaredSizeOf("<!-- print: 800x1200 -->\n"))
                .contains(new DesignPrints.PrintSize(800, 1200));
    }

    @Test
    void given_no_declaration_or_structural_drift_when_parse_then_empty() {
        // 界面稿首行（DOCTYPE 等）＝无声明 → 零渲染；声明不在首行/非整行/宽高非数字
        // ＝协议偏离，同样不认（稿保持 HTML 形态如实）
        assertThat(DesignPrints.declaredSizeOf("<!DOCTYPE html>")).isEmpty();
        assertThat(DesignPrints.declaredSizeOf("<html lang=\"zh\"><!-- print: 800x1200 --></html>"))
                .isEmpty();
        assertThat(DesignPrints.declaredSizeOf("<!-- print: 800 -->")).isEmpty();
        assertThat(DesignPrints.declaredSizeOf("<!-- print: 800xabc -->")).isEmpty();
        assertThat(DesignPrints.declaredSizeOf("<!-- print: -800x1200 -->")).isEmpty();
        assertThat(DesignPrints.declaredSizeOf("标题：<!-- print: 800x1200 -->")).isEmpty();
        assertThat(DesignPrints.declaredSizeOf("")).isEmpty();
        assertThat(DesignPrints.declaredSizeOf(null)).isEmpty();
    }

    @Test
    void given_paths_when_html_draft_then_by_extension() {
        assertThat(DesignPrints.htmlDraft("/design/poster-1.html")).isTrue();
        assertThat(DesignPrints.htmlDraft("/design/home-1.HTML")).isTrue();
        assertThat(DesignPrints.htmlDraft("/design/logo-1.png")).isFalse();
        assertThat(DesignPrints.htmlDraft("/design/poster-1.htm")).isFalse();
        assertThat(DesignPrints.htmlDraft(null)).isFalse();
    }

    @Test
    void given_html_path_when_png_path_then_same_stem_swapped_extension() {
        assertThat(DesignPrints.pngPathOf("/design/poster-1.html"))
                .isEqualTo("/design/poster-1.png");
        assertThat(DesignPrints.pngPathOf("design/brand-2.html"))
                .isEqualTo("design/brand-2.png");
        assertThatThrownBy(() -> DesignPrints.pngPathOf("/design/logo-1.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_source_when_head_command_then_guard_then_head() {
        assertThat(DesignPrints.headFirstLineCommand("design/poster-1.html")).isEqualTo(
                "p='/workspace/design/poster-1.html'; if ! test -f \"$p\"; then exit 1; fi;"
                        + " head -n 1 \"$p\"");
    }

    @Test
    void given_quote_in_path_when_head_command_then_escaped_literal() {
        assertThat(DesignPrints.headFirstLineCommand("design/it's.html")).isEqualTo(
                "p='/workspace/design/it'\\''s.html'; if ! test -f \"$p\"; then exit 1; fi;"
                        + " head -n 1 \"$p\"");
    }

}
