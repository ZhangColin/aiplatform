package com.semgravio.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * {@link WorkspaceChanges}（跑前快照链 + 收口真实 diff，#619）：命令形状（幂等
 * 仓库前置、无 Rollback-From trailer、hex 基线注入防线）、numstat 解析、patch
 * 文本按文件切分、大小上限（单文件 300 行 / 20KB、整卡 128KB）与降级事实。
 */
class WorkspaceChangesTest {

    // ---------- 命令构造 ----------

    @Test
    void given_run_id_when_snapshot_command_then_idempotent_repo_add_all_commit_without_version_trailer() {
        String command = WorkspaceChanges.snapshotCommand("r-abc123");

        // 幂等仓库初始化前置（身份/exclude 自愈复用版本层单点）
        assertThat(command).startsWith("cd " + WorkspaceLayout.ROOT);
        assertThat(command).contains("> .git/info/exclude");
        // 全量入索引后提交，提交信息只用平台标识
        assertThat(command).contains("git add -A");
        assertThat(command).contains("git commit -q --allow-empty -m 'snapshot r-abc123'");
        // 不带 Rollback-From——快照不是用户面版本，不进版本序列
        assertThat(command).doesNotContain(WorkspaceVersions.ROLLBACK_TRAILER);
        assertThat(command).endsWith("git rev-parse HEAD");
    }

    @Test
    void given_subject_quote_when_snapshot_command_then_escaped_single_quote() {
        String command = WorkspaceChanges.snapshotCommand("r'x");

        assertThat(command).contains("'snapshot r'\\''x'");
    }

    @Test
    void given_baseline_when_diff_commands_then_staged_numstat_and_patch_against_baseline() {
        String numstat = WorkspaceChanges.numstatCommand("a1b2c3d");
        String patch = WorkspaceChanges.patchCommand("a1b2c3d");

        assertThat(numstat).startsWith("cd " + WorkspaceLayout.ROOT);
        assertThat(numstat).contains("git add -A");
        assertThat(numstat).contains("git -c core.quotePath=false diff --cached --no-renames --numstat 'a1b2c3d'");
        assertThat(patch).contains("git -c core.quotePath=false diff --cached --no-renames 'a1b2c3d'");
    }

    @Test
    void given_non_hex_baseline_when_diff_commands_then_rejected_before_shell() {
        assertThatThrownBy(() -> WorkspaceChanges.numstatCommand("main; rm -rf /"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceChanges.patchCommand("$(id)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceChanges.numstatCommand(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_commit_hash_when_is_commit_ref_then_hex_only() {
        assertThat(WorkspaceChanges.isCommitRef("a1b2c3d")).isTrue();
        assertThat(WorkspaceChanges.isCommitRef("0123456789abcdef0123456789abcdef01234567")).isTrue();
        assertThat(WorkspaceChanges.isCommitRef("")).isFalse();
        assertThat(WorkspaceChanges.isCommitRef("abc")).isFalse();
        assertThat(WorkspaceChanges.isCommitRef("not-a-hash")).isFalse();
    }

    // ---------- numstat 解析 ----------

    @Test
    void given_numstat_output_when_parse_then_file_level_added_removed_workspace_anchored() {
        String stdout = "12\t3\tsrc/App.jsx\n"
                + "4\t0\tsrc/pages/Orders.jsx\n"
                + "0\t9\tsrc/old.js\n"
                + "-\t-\tpublic/logo.png\n";

        List<WorkspaceChanges.Numstat> entries = WorkspaceChanges.parseNumstat(stdout);

        assertThat(entries).containsExactly(
                new WorkspaceChanges.Numstat("/src/App.jsx", 12, 3),
                new WorkspaceChanges.Numstat("/src/pages/Orders.jsx", 4, 0),
                new WorkspaceChanges.Numstat("/src/old.js", 0, 9),
                new WorkspaceChanges.Numstat("/public/logo.png", 0, 0));
    }

    @Test
    void given_malformed_or_quoted_lines_when_parse_then_skipped_or_unquoted() {
        String stdout = "碎行\n"
                + "1\t2\n"
                + "a\tb\tsrc/x.js\n"
                + "1\t1\t\"src/with space.js\"\n";

        List<WorkspaceChanges.Numstat> entries = WorkspaceChanges.parseNumstat(stdout);

        assertThat(entries).containsExactly(
                new WorkspaceChanges.Numstat("/src/x.js", 0, 0),
                new WorkspaceChanges.Numstat("/src/with space.js", 1, 1));
    }

    // ---------- patch 切分 ----------

    @Test
    void given_full_patch_when_split_then_one_block_per_file_keyed_by_workspace_path() {
        String patch = "diff --git a/src/App.jsx b/src/App.jsx\n"
                + "index 111..222 100644\n"
                + "--- a/src/App.jsx\n"
                + "+++ b/src/App.jsx\n"
                + "@@ -1,2 +1,2 @@\n"
                + "-old\n"
                + "+new\n"
                + "diff --git a/src/pages/Orders.jsx b/src/pages/Orders.jsx\n"
                + "--- a/src/pages/Orders.jsx\n"
                + "+++ b/src/pages/Orders.jsx\n"
                + "@@ -1 +1 @@\n"
                + "-a\n"
                + "+b\n";

        Map<String, String> byFile = WorkspaceChanges.splitPatch(patch);

        assertThat(byFile).containsOnlyKeys(
                "/src/App.jsx", "/src/pages/Orders.jsx");
        assertThat(byFile.get("/src/App.jsx"))
                .startsWith("diff --git a/src/App.jsx b/src/App.jsx")
                .contains("-old").contains("+new")
                .doesNotContain("Orders.jsx");
        assertThat(byFile.get("/src/pages/Orders.jsx")).contains("@@ -1 +1 @@");
    }

    @Test
    void given_empty_patch_when_split_then_empty_map() {
        assertThat(WorkspaceChanges.splitPatch("")).isEmpty();
        assertThat(WorkspaceChanges.splitPatch(null)).isEmpty();
    }

    // ---------- 装配与上限 ----------

    @Test
    void given_numstat_and_patches_when_assemble_then_sorted_and_patch_attached() {
        List<WorkspaceChanges.Numstat> entries = List.of(
                new WorkspaceChanges.Numstat("/src/pages/Orders.jsx", 12, 3),
                new WorkspaceChanges.Numstat("/src/App.jsx", 4, 0));
        Map<String, String> patches = Map.of(
                "/src/App.jsx", "diff --git a/src/App.jsx b/src/App.jsx\n-a\n+b\n",
                "/src/pages/Orders.jsx", "diff --git a/src/pages/Orders.jsx b/src/pages/Orders.jsx\n-x\n+y\n");

        WorkspaceChanges.ChangeSet set = WorkspaceChanges.assemble(entries, patches, true);

        assertThat(set.unavailable()).isFalse();
        assertThat(set.files()).extracting(WorkspaceChanges.FileDiff::path)
                .containsExactly("/src/App.jsx", "/src/pages/Orders.jsx");
        assertThat(set.files().get(0).patch()).contains("-a").contains("+b");
        assertThat(set.files()).allSatisfy(file -> assertThat(file.patchOmitted()).isFalse());
    }

    @Test
    void given_first_build_when_assemble_without_patches_then_line_counts_only() {
        List<WorkspaceChanges.Numstat> entries = List.of(
                new WorkspaceChanges.Numstat("/src/App.jsx", 4, 0));
        Map<String, String> patches = Map.of(
                "/src/App.jsx", "diff --git a/src/App.jsx b/src/App.jsx\n-a\n+b\n");

        WorkspaceChanges.ChangeSet set = WorkspaceChanges.assemble(entries, patches, false);

        assertThat(set.files()).singleElement().satisfies(file -> {
            assertThat(file.added()).isEqualTo(4);
            assertThat(file.patch()).isNull();
            assertThat(file.patchOmitted()).isFalse();
        });
    }

    @Test
    void given_patch_over_line_limit_when_assemble_then_patch_omitted_but_lines_kept() {
        List<WorkspaceChanges.Numstat> entries = List.of(
                new WorkspaceChanges.Numstat("/src/Huge.jsx", 999, 0));
        Map<String, String> patches = Map.of("/src/Huge.jsx", lines(WorkspaceChanges.MAX_PATCH_LINES + 1));

        WorkspaceChanges.ChangeSet set = WorkspaceChanges.assemble(entries, patches, true);

        assertThat(set.files()).singleElement().satisfies(file -> {
            assertThat(file.added()).isEqualTo(999);
            assertThat(file.patch()).isNull();
            assertThat(file.patchOmitted()).isTrue();
        });
    }

    @Test
    void given_patch_over_byte_limit_when_assemble_then_patch_omitted_but_lines_kept() {
        List<WorkspaceChanges.Numstat> entries = List.of(
                new WorkspaceChanges.Numstat("/src/Wide.jsx", 10, 0));
        String wide = "diff --git a/src/Wide.jsx b/src/Wide.jsx\n"
                + "x".repeat(WorkspaceChanges.MAX_PATCH_BYTES + 1);
        Map<String, String> patches = Map.of("/src/Wide.jsx", wide);

        WorkspaceChanges.ChangeSet set = WorkspaceChanges.assemble(entries, patches, true);

        assertThat(set.files()).singleElement().satisfies(file -> {
            assertThat(file.added()).isEqualTo(10);
            assertThat(file.patch()).isNull();
            assertThat(file.patchOmitted()).isTrue();
        });
    }

    @Test
    void given_many_patches_when_total_over_cap_then_remaining_patches_omitted() {
        // 每块 ≈19KB（未超单文件 20KB 上限），8 块合计 >128KB——超出总预算的后几块省略
        List<WorkspaceChanges.Numstat> entries = new ArrayList<>();
        Map<String, String> patches = new java.util.LinkedHashMap<>();
        String block = "diff --git a/src/F%s.jsx b/src/F%s.jsx\n" + "y".repeat(19 * 1024);
        for (int index = 0; index < 8; index++) {
            String path = "/src/F" + index + ".jsx";
            entries.add(new WorkspaceChanges.Numstat(path, 1, 0));
            patches.put(path, block.formatted(index, index));
        }

        WorkspaceChanges.ChangeSet set = WorkspaceChanges.assemble(entries, patches, true);

        assertThat(set.files()).hasSize(8);
        long kept = set.files().stream().filter(file -> file.patch() != null).count();
        assertThat(kept).isLessThan(8).isGreaterThan(0);
        assertThat(set.files().stream().filter(file -> file.patchOmitted()).count())
                .isEqualTo(8 - kept);
        int totalBytes = set.files().stream()
                .filter(file -> file.patch() != null)
                .mapToInt(file -> file.patch().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .sum();
        assertThat(totalBytes).isLessThanOrEqualTo(WorkspaceChanges.MAX_TOTAL_PATCH_BYTES);
    }

    @Test
    void given_empty_numstat_when_assemble_then_empty_available_set() {
        WorkspaceChanges.ChangeSet set = WorkspaceChanges.assemble(List.of(), Map.of(), true);

        assertThat(set.files()).isEmpty();
        assertThat(set.unavailable()).isFalse();
    }

    @Test
    void given_unavailable_when_change_set_then_empty_and_flagged() {
        WorkspaceChanges.ChangeSet set = WorkspaceChanges.ChangeSet.degraded();

        assertThat(set.files()).isEmpty();
        assertThat(set.unavailable()).isTrue();
    }

    /** 恰好 n 行（含首尾）的 patch 文本。 */
    private static String lines(int count) {
        StringBuilder text = new StringBuilder("diff --git a/src/Huge.jsx b/src/Huge.jsx");
        for (int index = 1; index < count; index++) {
            text.append("\n+line ").append(index);
        }
        return text.toString();
    }
}
