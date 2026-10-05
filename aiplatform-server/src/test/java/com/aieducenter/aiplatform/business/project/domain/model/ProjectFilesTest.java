package com.aieducenter.aiplatform.business.project.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件树浏览面（#27）的纯规则：可浏览路径判定（排除清单 + 锚定形）、容器命令
 * 构造、find 输出解析。命令字面量 = 与容器 shell 语义的契约，逐字符钉死。
 */
class ProjectFilesTest {

    // ---------- 可浏览路径判定 ----------

    @Test
    void given_workspace_anchored_paths_when_viewable_then_true() {
        assertThat(ProjectFiles.isViewable("docs/PRD.md")).isTrue();
        assertThat(ProjectFiles.isViewable("AGENTS.md")).isTrue();
        assertThat(ProjectFiles.isViewable("src/app/page.tsx")).isTrue();
        // 排除只锚工作区根级（与源码包 tar 严格同口径）：嵌套同名目录是应用自己的
        // 交付物，不误伤
        assertThat(ProjectFiles.isViewable("src/data/seed.sql")).isTrue();
        assertThat(ProjectFiles.isViewable("src/node_modules/react/index.js")).isTrue();
        assertThat(ProjectFiles.isViewable("apps/web/.env")).isTrue();
    }

    @Test
    void given_non_deliverable_paths_when_viewable_then_false() {
        // 根级排除清单与源码包同源：数据 / 平台产物 / 可重建依赖 / 机密
        assertThat(ProjectFiles.isViewable("data/pg/base.sql")).isFalse();
        assertThat(ProjectFiles.isViewable("data")).isFalse();
        assertThat(ProjectFiles.isViewable(".platform/logs/run.log")).isFalse();
        assertThat(ProjectFiles.isViewable("node_modules/react/index.js")).isFalse();
        assertThat(ProjectFiles.isViewable(".pnpm-store/v10/files/x")).isFalse(); // pnpm 依赖缓存
        assertThat(ProjectFiles.isViewable(".next/trace")).isFalse(); // 基座 Next 构建产物
        assertThat(ProjectFiles.isViewable("external/some-repo/README.md")).isFalse(); // 外部仓库资料
        assertThat(ProjectFiles.isViewable(".env")).isFalse();
    }

    @Test
    void given_escaping_or_malformed_paths_when_viewable_then_false() {
        assertThat(ProjectFiles.isViewable("../etc/passwd")).isFalse();   // 逃逸
        assertThat(ProjectFiles.isViewable("docs/../../etc")).isFalse(); // 中段逃逸
        assertThat(ProjectFiles.isViewable("/workspace/.env")).isFalse(); // 绝对路径
        assertThat(ProjectFiles.isViewable("   ")).isFalse();             // 空白
        assertThat(ProjectFiles.isViewable(null)).isFalse();
    }

    // ---------- 树列表命令与输出解析 ----------

    @Test
    void when_list_command_then_prunes_root_non_deliverables_at_source() {
        // find 从源头剪枝（不进 node_modules 巨树）：根级非交付目录 -path 锚定
        // prune、根级 .env 排除（与源码包 tar 同口径）、%P 相对路径、%s 字节大小
        assertThat(ProjectFiles.listCommand()).isEqualTo(
                "find /workspace \\( -path /workspace/node_modules -o -path /workspace/.pnpm-store"
                        + " -o -path /workspace/.next -o -path /workspace/data"
                        + " -o -path /workspace/.platform -o -path /workspace/agents"
                        + " -o -path /workspace/external -o -path /workspace/.git \\) -prune -o -type f"
                        + " ! -path /workspace/.env -printf '%s\\t%P\\n'");
    }

    @Test
    void given_find_output_when_parse_entries_then_sorted_by_path() {
        // find 顺序是目录序非字典序——Java 侧按路径稳定排序再出端点
        assertThat(ProjectFiles.parseEntries("340\tsrc/index.ts\n12\tdocs/PRD.md\n7\tAGENTS.md\n"))
                .containsExactly(
                        new ProjectFiles.Entry("AGENTS.md", 7),
                        new ProjectFiles.Entry("docs/PRD.md", 12),
                        new ProjectFiles.Entry("src/index.ts", 340));
    }

    @Test
    void given_empty_or_malformed_output_when_parse_entries_then_skip_broken_lines() {
        assertThat(ProjectFiles.parseEntries("")).isEmpty(); // 空工作区 = 空树，不是错误
        // 畸形行（文件名含换行等产生的碎行）跳过，不炸整树
        assertThat(ProjectFiles.parseEntries("12\tdocs/PRD.md\n碎行无制表符\nx\tnot-a-number\n"))
                .containsExactly(new ProjectFiles.Entry("docs/PRD.md", 12));
    }

    // ---------- 内容读取命令 ----------

    @Test
    void when_content_command_then_size_guarded_before_cat() {
        // 三段守卫各占退出码：1 = 不是文件/不存在、2 = 超大小上限（cat 前拦截，
        // 巨文件不进内存与 stdout）；0 = 首行字节大小 + 余文正文（PRD 读同构）
        assertThat(ProjectFiles.contentCommand("src/app/page.tsx")).isEqualTo(
                "p='/workspace/src/app/page.tsx'; if ! test -f \"$p\"; then exit 1; fi;"
                        + " s=$(stat -c %s \"$p\");"
                        + " if [ \"$s\" -gt " + ProjectFiles.MAX_CONTENT_BYTES + " ]; then exit 2; fi;"
                        + " printf '%s\\n' \"$s\"; cat \"$p\"");
    }

    @Test
    void given_quote_in_filename_when_content_command_then_shell_escaped() {
        // 用户可控路径进单引号串：' 转义为 '\''，防注入（isViewable 已拒畸形，此处兜底）
        assertThat(ProjectFiles.contentCommand("docs/it's.md"))
                .contains("p='/workspace/docs/it'\\''s.md';");
    }

    @Test
    void given_non_viewable_path_when_content_command_then_rejected() {
        // 命令构造只收已判定可浏览的路径——防线在调用侧先行，此处不代偿
        assertThatThrownBy(() -> ProjectFiles.contentCommand("data/pg/base.sql"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 图片点看判定与 raw 直出命令（#283） ----------

    @Test
    void given_image_extensions_when_image_path_then_true() {
        // 扩展名面（点看判定对图片放行）：五格式大小写不敏感；无扩展名/文本/二进制
        // 非图片扩展名不在 raw 伺服面（文本走 content、真二进制非图片仍 PRJ_023）
        assertThat(ProjectFiles.isImagePath("materials/ref.png")).isTrue();
        assertThat(ProjectFiles.isImagePath("materials/ref.PNG")).isTrue();
        assertThat(ProjectFiles.isImagePath("design/poster.Jpg")).isTrue();
        assertThat(ProjectFiles.isImagePath("a/b/c.webp")).isTrue();
        assertThat(ProjectFiles.isImagePath("logo.svg")).isTrue();
        assertThat(ProjectFiles.isImagePath("anim.gif")).isTrue();
    }

    @Test
    void given_non_image_paths_when_image_path_then_false() {
        assertThat(ProjectFiles.isImagePath("docs/PRD.md")).isFalse();
        assertThat(ProjectFiles.isImagePath("src/app/page.tsx")).isFalse();
        assertThat(ProjectFiles.isImagePath("assets/logo.bin")).isFalse();
        assertThat(ProjectFiles.isImagePath("no-extension")).isFalse();
        assertThat(ProjectFiles.isImagePath("png")).isFalse(); // 文件名恰好叫 png，不是扩展名
    }

    @Test
    void when_raw_image_command_then_size_guarded_before_cat_without_text_header() {
        // 同 contentCommand 的三段守卫（1 = 不存在、2 = 超图片查看上限），但 stdout 是
        // 文件原始字节（无「大小首行 + 正文」的文本形——二进制不经文本通道）
        assertThat(ProjectFiles.rawImageCommand("materials/ref.png")).isEqualTo(
                "p='/workspace/materials/ref.png'; if ! test -f \"$p\"; then exit 1; fi;"
                        + " s=$(stat -c %s \"$p\");"
                        + " if [ \"$s\" -gt " + ProjectFiles.MAX_RAW_IMAGE_BYTES + " ]; then exit 2; fi;"
                        + " cat \"$p\"");
    }

    @Test
    void given_quote_in_filename_when_raw_image_command_then_shell_escaped() {
        assertThat(ProjectFiles.rawImageCommand("materials/it's.png"))
                .contains("p='/workspace/materials/it'\\''s.png';");
    }

    @Test
    void given_non_viewable_path_when_raw_image_command_then_rejected() {
        assertThatThrownBy(() -> ProjectFiles.rawImageCommand(".env"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 单文件下载命令（#287 通用下载＋支付门） ----------

    @Test
    void when_download_command_then_existence_guard_then_cat_without_size_cap() {
        // 下载＝带走，无点看式大小上限（与源码包整卷 tar 同一 exec 通道同口径，
        // 巨文件护面不在命令层）；唯一退出码语义：1 = 不存在（cat 前拦截）
        assertThat(ProjectFiles.downloadCommand("materials/ref.png"))
                .isEqualTo("p='/workspace/materials/ref.png'; if ! test -f \"$p\"; then exit 1; fi;"
                        + " cat \"$p\"");
    }

    @Test
    void given_quote_in_filename_when_download_command_then_shell_escaped() {
        assertThat(ProjectFiles.downloadCommand("exports/it's.zip"))
                .contains("p='/workspace/exports/it'\\''s.zip';");
    }

    @Test
    void given_non_viewable_path_when_download_command_then_rejected() {
        assertThatThrownBy(() -> ProjectFiles.downloadCommand(".env"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void when_content_type_then_mapped_by_extension() {
        assertThat(ProjectFiles.contentTypeOf("materials/ref.png")).isEqualTo("image/png");
        assertThat(ProjectFiles.contentTypeOf("materials/photo.jpg")).isEqualTo("image/jpeg");
        assertThat(ProjectFiles.contentTypeOf("materials/photo.jpeg")).isEqualTo("image/jpeg");
        assertThat(ProjectFiles.contentTypeOf("materials/shot.webp")).isEqualTo("image/webp");
        assertThat(ProjectFiles.contentTypeOf("design/anim.gif")).isEqualTo("image/gif");
        assertThat(ProjectFiles.contentTypeOf("design/logo.svg")).isEqualTo("image/svg+xml");
        // 非图片扩展名兜底字节流（调用侧图片判定先行，此处不代偿）
        assertThat(ProjectFiles.contentTypeOf("docs/PRD.md")).isEqualTo("application/octet-stream");
    }
}
