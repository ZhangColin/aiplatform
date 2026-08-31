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
        assertThat(ProjectFiles.isViewable(".platform/sessions/x.json")).isFalse();
        assertThat(ProjectFiles.isViewable("node_modules/react/index.js")).isFalse();
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
                "find /workspace \\( -path /workspace/node_modules -o -path /workspace/data"
                        + " -o -path /workspace/.platform \\) -prune -o -type f"
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
}
