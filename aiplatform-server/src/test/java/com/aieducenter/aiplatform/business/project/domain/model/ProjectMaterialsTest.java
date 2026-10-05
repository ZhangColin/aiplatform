package com.aieducenter.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 物料上传面规则（#286，纯函数）：五格式判定（与点看扩展名面同集）、原始名净化
 * （路径剥脱/控制字符与引号剔除/空白压缩/超长截词干）、TSID 前缀落点、写入命令
 * 正本（守卫 + mkdir + cat + stat 回执——逐字钉死，命令字符串是容器契约）。
 */
class ProjectMaterialsTest {

    @Test
    void given_filenames_when_uploadable_check_then_five_image_extensions_case_insensitive() {
        for (String name : new String[] {"a.png", "b.jpg", "c.jpeg", "d.webp", "e.gif",
                "f.svg", "g.PNG", "h.WebP"}) {
            assertThat(ProjectMaterials.isUploadableImage(name)).as(name).isTrue();
        }
        for (String name : new String[] {"a.pdf", "b.docx", "c.txt", "d.mp4", "e",
                null, ""}) {
            assertThat(ProjectMaterials.isUploadableImage(name)).as(String.valueOf(name)).isFalse();
        }
    }

    @Test
    void given_client_path_and_dangerous_characters_when_sanitize_then_basename_stripped_clean() {
        // 客户端可能给路径形（历史 IE 全路径、恶意 ../）——只取末段；控制字符与
        // 引号剔除（文件系统/响应头两面的注入字）；空白压缩
        assertThat(ProjectMaterials.sanitizeName("/Users/me/Desktop/logo.png")).isEqualTo("logo.png");
        assertThat(ProjectMaterials.sanitizeName("C:\\Users\\me\\海报.png")).isEqualTo("海报.png");
        assertThat(ProjectMaterials.sanitizeName("../../e'vil\".png")).isEqualTo("evil.png");
        assertThat(ProjectMaterials.sanitizeName("a\u0000b\u0007c.png")).isEqualTo("abc.png");
        assertThat(ProjectMaterials.sanitizeName("我的   图.png")).isEqualTo("我的 图.png");
        assertThat(ProjectMaterials.sanitizeName(" 屏幕截图 .png")).isEqualTo("屏幕截图 .png");
    }

    @Test
    void given_blank_or_weird_names_when_sanitize_then_fallback_or_passthrough() {
        assertThat(ProjectMaterials.sanitizeName(null)).isEqualTo("material");
        assertThat(ProjectMaterials.sanitizeName("  ")).isEqualTo("material");
        assertThat(ProjectMaterials.sanitizeName("'\"'\"")).isEqualTo("material");
        // 病态输入如实直通（TSID 前缀保证落点仍唯一）：整名即扩展词（无词干）
        assertThat(ProjectMaterials.sanitizeName(".png")).isEqualTo(".png");
    }

    @Test
    void given_overlong_stem_when_sanitize_then_stem_truncated_extension_kept() {
        String longStem = "字".repeat(100); // 300 UTF-8 字节词干
        String sanitized = ProjectMaterials.sanitizeName(longStem + ".png");

        assertThat(sanitized).endsWith(".png");
        assertThat(sanitized.substring(0, sanitized.length() - ".png".length())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(120);
    }

    @Test
    void given_tsid_and_name_when_stored_path_then_materials_dir_with_tsid_prefix() {
        // TSID 前缀保证不撞名不覆盖（物料累积面），词干保留原始名供文件区人读
        assertThat(ProjectMaterials.storedPath("3897654321098765432", "logo.png"))
                .isEqualTo("materials/3897654321098765432-logo.png");
    }

    @Test
    void given_viewable_path_when_upload_command_then_verbatim_guard_mkdir_cat_stat() {
        // 命令正本逐字钉死（容器契约）：路径单引号转义、父目录幂等落位、stdin 灌
        // 字节、stat 字节回执
        assertThat(ProjectMaterials.uploadCommand("materials/123-logo.png"))
                .isEqualTo("p='/workspace/materials/123-logo.png';"
                        + " d=${p%/*}; mkdir -p \"$d\" && cat > \"$p\" && stat -c %s \"$p\"");
    }

    @Test
    void given_escaping_or_non_viewable_path_when_upload_command_then_rejected() {
        // 平台生成形之外的路径（逃逸/非交付面）命令构造层拒绝——用户可控面不进 shell
        assertThatThrownBy(() -> ProjectMaterials.uploadCommand("../escape.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectMaterials.uploadCommand("data/x.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectMaterials.uploadCommand("/abs.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_path_with_quote_when_upload_command_then_single_quote_escaped() {
        // 净化已剔引号，此处是命令构造层的独立防线（转义不依赖上游卫生）
        assertThat(ProjectMaterials.uploadCommand("materials/1-it's.png"))
                .startsWith("p='/workspace/materials/1-it'\\''s.png';");
    }
}
