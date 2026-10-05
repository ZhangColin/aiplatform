package com.aieducenter.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 出图转存面规则单测（#288 出图工具件内核，形制照 {@link ProjectMaterialsTest}）：
 * 落点命名（design/ 前缀＋TSID 防撞）、扩展名收口（URL 推导/许可集/回落）、下载
 * 命令正本（容器内 curl 转存——守卫壳＋单引号转义＋stat 字节回执，逐字钉死）。
 */
class ImageTransfersTest {

    // ---------- 落点命名 ----------

    @Test
    void given_generated_image_when_stored_path_then_design_dir_with_tsid_prefix() {
        // 产物目录＝design/，TSID 前缀防撞（候选各代累积面，同名片不覆盖）
        assertThat(ImageTransfers.storedPath("123456", "hero-海报", "png"))
                .isEqualTo("design/123456-hero-海报.png");
    }

    // ---------- 扩展名收口 ----------

    @Test
    void given_url_with_extension_when_from_url_then_takes_path_segment_extension() {
        assertThat(ImageTransfers.extensionFromUrl(
                "https://cdn.example.com/img/abc.jpeg?Expires=1760000000"))
                .isEqualTo("jpeg");
        assertThat(ImageTransfers.extensionFromUrl("https://cdn.example.com/a/b/xyz.PNG"))
                .isEqualTo("png");
    }

    @Test
    void given_url_without_extension_when_from_url_then_png_fallback() {
        assertThat(ImageTransfers.extensionFromUrl("https://cdn.example.com/image?id=1"))
                .isEqualTo("png");
        assertThat(ImageTransfers.extensionFromUrl("not a url")).isEqualTo("png");
    }

    @Test
    void given_hint_outside_allowed_set_when_extension_of_then_png() {
        // 许可集＝点看面图片集减 svg（位图生成模型不出 svg）；svg/tiff/空一律回落
        assertThat(ImageTransfers.extensionOf("svg")).isEqualTo("png");
        assertThat(ImageTransfers.extensionOf("tiff")).isEqualTo("png");
        assertThat(ImageTransfers.extensionOf(null)).isEqualTo("png");
        assertThat(ImageTransfers.extensionOf("webp")).isEqualTo("webp");
    }

    // ---------- 转存命令正本（两形同走 .part 暂存＋失败清理） ----------

    @Test
    void given_url_form_when_download_command_then_curl_transfer_with_receipt() {
        assertThat(ImageTransfers.downloadCommand("design/123-hero.png",
                "https://cdn.example.com/img/abc.png?Expires=1760000000"))
                .isEqualTo("p='/workspace/design/123-hero.png'; q=\"$p.part\"; d=${p%/*};"
                        + " mkdir -p \"$d\""
                        + " && { curl -fsSL --max-time 30 -o \"$q\""
                        + " 'https://cdn.example.com/img/abc.png?Expires=1760000000'"
                        + " && test -s \"$q\" && mv \"$q\" \"$p\" && stat -c %s \"$p\""
                        + " || { rm -f -- \"$q\"; exit 1; }; }");
    }

    @Test
    void given_base64_form_when_write_command_then_stdin_part_then_atomic_rename() {
        // base64 形＝stdin 灌 .part 后原子改名（同 .part 语义：失败不留半张稿）
        assertThat(ImageTransfers.writeCommand("design/9-b.jpg"))
                .isEqualTo("p='/workspace/design/9-b.jpg'; q=\"$p.part\"; d=${p%/*};"
                        + " mkdir -p \"$d\""
                        + " && { cat > \"$q\" && test -s \"$q\" && mv \"$q\" \"$p\""
                        + " && stat -c %s \"$p\" || { rm -f -- \"$q\"; exit 1; }; }");
    }

    @Test
    void given_url_with_single_quote_when_download_command_then_escaped_no_injection() {
        // 单引号转义（' → '\''）：URL 含引号无注入面
        assertThat(ImageTransfers.downloadCommand("design/1-a.png", "https://x/y'z.png"))
                .contains("'https://x/y'\\''z.png'");
    }

    @Test
    void given_non_viewable_path_when_commands_then_rejected() {
        assertThatThrownBy(() -> ImageTransfers.downloadCommand("../escape.png", "https://x/y.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非可浏览路径");
        assertThatThrownBy(() -> ImageTransfers.downloadCommand("data/pg/x.png", "https://x/y.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageTransfers.writeCommand("../escape.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
