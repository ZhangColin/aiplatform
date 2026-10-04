package com.aieducenter.aiplatform.business.project.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 位图出口内核（#284，ADR-0026/0027）的纯规则：HTML→PNG（chromium）与 SVG→PNG
 * （resvg 旁路）的容器命令构造、画幅边界、路径守卫。命令字面量 = 与容器内
 * /opt/render 渲染器的契约，逐字符钉死（活体保真归 WorkspaceRendersLiveTest）。
 */
class WorkspaceRendersTest {

    // ---------- HTML→PNG（chromium 保真渲染） ----------

    @Test
    void when_html_to_png_command_then_guarded_render_then_size_stat() {
        // 存在守卫（1 = 源不在）→ 渲染器（画幅随载荷）→ stat 回执（stdout 首行
        // 即 PNG 字节数）；渲染/回执任一失败归一 4——不与「源不在」的 1 混淆
        assertThat(WorkspaceRenders.htmlToPngCommand(
                "design/poster.html", "design/poster.png", 800, 600)).isEqualTo(
                "p='/workspace/design/poster.html'; if ! test -f \"$p\"; then exit 1; fi;"
                        + " o='/workspace/design/poster.png';"
                        + " node /opt/render/render-html.mjs \"$p\" \"$o\" 800 600"
                        + " && stat -c %s \"$o\" || exit 4");
    }

    @Test
    void when_svg_to_png_command_then_bypass_render_then_size_stat() {
        // SVG 旁路（零浏览器）：同一守卫形制，渲染器换 resvg 旁路件、无画幅载荷
        // （原生尺寸渲染，多分辨率衍生是备案项不预埋）
        assertThat(WorkspaceRenders.svgToPngCommand(
                "design/logo.svg", "exports/logo.png")).isEqualTo(
                "p='/workspace/design/logo.svg'; if ! test -f \"$p\"; then exit 1; fi;"
                        + " o='/workspace/exports/logo.png';"
                        + " node /opt/render/render-svg.mjs \"$p\" \"$o\""
                        + " && stat -c %s \"$o\" || exit 4");
    }

    // ---------- 路径守卫（用户可控入参不进 shell 的同一道防线） ----------

    @Test
    void given_quote_in_paths_when_commands_then_shell_escaped() {
        // 单引号串内 ' 转义为 '\''，与 contentCommand/rawImageCommand 同款（isViewable
        // 已拒畸形，此处兜底）
        assertThat(WorkspaceRenders.htmlToPngCommand(
                "design/it's.html", "design/it's.png", 100, 100))
                .contains("p='/workspace/design/it'\\''s.html';");
        assertThat(WorkspaceRenders.svgToPngCommand(
                "design/it's.svg", "design/it's.png"))
                .contains("o='/workspace/design/it'\\''s.png';");
    }

    @Test
    void given_non_viewable_source_or_target_when_commands_then_rejected() {
        // 渲染面＝交付文件面：源（设计稿）与产物（PNG 衍生）都须工作区锚定的可浏览
        // 路径——非交付目录不是渲染出入面，命令构造拒绝
        assertThatThrownBy(() -> WorkspaceRenders.htmlToPngCommand(
                "data/pg/base.sql", "design/x.png", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceRenders.htmlToPngCommand(
                "design/x.html", ".env", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceRenders.htmlToPngCommand(
                "../escape.html", "design/x.png", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceRenders.svgToPngCommand(
                "design/logo.svg", "node_modules/logo.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 画幅边界 ----------

    @Test
    void given_viewport_out_of_bounds_when_html_command_then_rejected() {
        // 画幅上下界（1 ~ chromium 单边纹理上限 16384）：上界外是渲染必败的离谱画幅，
        // 命令构造层拒（诚实早拒，不进容器空跑）
        assertThatThrownBy(() -> WorkspaceRenders.htmlToPngCommand(
                "design/x.html", "design/x.png", 0, 600))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceRenders.htmlToPngCommand(
                "design/x.html", "design/x.png", 800, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceRenders.htmlToPngCommand(
                "design/x.html", "design/x.png", 16385, 600))
                .isInstanceOf(IllegalArgumentException.class);
        // 边界值本身合法
        assertThat(WorkspaceRenders.htmlToPngCommand(
                "design/x.html", "design/x.png", 16384, 16384))
                .contains(" 16384 16384");
    }
}
