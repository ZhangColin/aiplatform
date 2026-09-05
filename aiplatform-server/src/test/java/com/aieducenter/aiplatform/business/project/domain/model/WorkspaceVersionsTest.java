package com.aieducenter.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;

/**
 * {@link WorkspaceVersions}（容器内 git 管道，#91）：命令形状（幂等 init /
 * .gitignore 派生自非交付名单 / 收口成版提交携 Run-Id trailer / 三守卫退出码）、
 * git log 输出解析（只收带 Run-Id trailer 的成版 commit——智能体 shell 面的野
 * commit 不进版本序列）、版本引用校验（hex-only，shell 注入防线）。
 */
class WorkspaceVersionsTest {

    /** git log 格式分隔符（与命令构造同源）：字段 \u001f、记录 \u001e。 */
    private static final String FS = "\u001f";
    private static final String RS = "\u001e";

    // ---------- 命令构造 ----------

    @Test
    void given_workspace_when_ensure_repo_command_then_idempotent_init_with_repo_local_identity() {
        String command = WorkspaceVersions.ensureRepoCommand();

        // 幂等：已有 .git 不重复 init（容器重建后卷内仓库原样续用）
        assertThat(command).contains("if ! test -d .git; then");
        assertThat(command).contains("git init");
        // repo-local 身份（git 全局配置不进卷，随卷持久的是 repo-local config）
        assertThat(command).contains("git config user.name");
        assertThat(command).contains("git config user.email");
        // 工作区根锚定
        assertThat(command).startsWith("cd " + WorkspaceLayout.ROOT);
    }

    @Test
    void given_workspace_when_ensure_repo_command_then_exclude_derives_from_non_deliverable_facts() {
        String command = WorkspaceVersions.ensureRepoCommand();

        // .git/info/exclude 单一事实 = WorkspaceLayout 非交付名单 + .env 机密（与源码包/
        // 文件树同口径）；写 exclude 而非覆盖应用 .gitignore（脚手架自带 .gitignore 原样保留）
        for (String dir : WorkspaceLayout.NON_DELIVERABLE_DIRS) {
            assertThat(command).contains("'" + dir + "'");
        }
        assertThat(command).contains("'" + WorkspaceLayout.ENV_FILE + "'");
        assertThat(command).contains("> .git/info/exclude");
        assertThat(command).doesNotContain("> .gitignore");
    }

    @Test
    void given_closing_facts_when_commit_command_then_allow_empty_with_run_id_trailer() {
        String command = WorkspaceVersions.commitCommand("更新了系统", "1234567890");

        // --allow-empty：每轮收口必成版（含「无需改动」轮），保版本 ↔ 收尾卡 1:1 锚定
        assertThat(command).contains("--allow-empty");
        assertThat(command).contains("-m '更新了系统'");
        assertThat(command).contains("-m 'Run-Id: 1234567890'");
        // 全量暂存 + 提交后回读 HEAD hash（成版锚点回传）
        assertThat(command).contains("git add -A");
        assertThat(command).contains("git rev-parse HEAD");
    }

    @Test
    void given_subject_with_single_quote_when_commit_command_then_escaped() {
        String command = WorkspaceVersions.commitCommand("改了用户的'订单'页", "1");

        assertThat(command).contains("-m '改了用户的'\\''订单'\\''页'");
    }

    @Test
    void given_workspace_when_list_command_then_no_commit_guard_exit_3() {
        String command = WorkspaceVersions.listCommand();

        // 零版本守卫（尚无收口的仓库）：exit 3 与真实 git 故障分道
        assertThat(command).contains("git rev-parse --verify HEAD");
        assertThat(command).contains("exit 3");
        assertThat(command).contains("git log");
    }

    @Test
    void given_valid_ref_when_show_command_then_single_commit_metadata() {
        String command = WorkspaceVersions.showCommand("a1b2c3d");

        assertThat(command).contains("git rev-parse --verify 'a1b2c3d^{commit}'");
        assertThat(command).contains("exit 3");
        assertThat(command).contains("git log -1");
        assertThat(command).contains("'a1b2c3d'");
    }

    @Test
    void given_non_hex_ref_when_show_command_then_rejected_before_shell() {
        // ref 是 API 用户可控入参：hex-only 校验在命令构造前拦截（shell 注入防线）
        assertThatThrownBy(() -> WorkspaceVersions.showCommand("main; rm -rf /"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceVersions.showCommand("$(id)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceVersions.showCommand(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_ref_and_subject_when_rollback_command_then_restore_then_append_commit_with_rollback_trailer() {
        String command = WorkspaceVersions.rollbackCommand("a1b2c3d", "回滚到「更新了系统」");

        // 复位工作树到目标 commit（restore --source，只动 tracked——数据不在跟踪面保留）→
        // 追加新 commit（HEAD 不动、无 rebase/force）→ 携 Rollback-From trailer 锚定源版本
        assertThat(command).startsWith("cd " + WorkspaceLayout.ROOT);
        assertThat(command).contains("git restore --source='a1b2c3d' --staged --worktree .");
        assertThat(command).contains("git commit -q --allow-empty");
        assertThat(command).contains("-m '回滚到「更新了系统」'");
        assertThat(command).contains("-m 'Rollback-From: a1b2c3d'");
        assertThat(command).contains("git rev-parse HEAD");
        // 历史只追加不改写——绝无 reset --hard / rebase / push --force 类操作
        assertThat(command).doesNotContain("rebase").doesNotContain("--hard").doesNotContain("--force");
    }

    @Test
    void given_non_hex_ref_when_rollback_command_then_rejected_before_shell() {
        assertThatThrownBy(() -> WorkspaceVersions.rollbackCommand("main; rm -rf /", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceVersions.rollbackCommand("$(id)", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- log 解析 ----------

    @Test
    void given_log_output_when_parse_then_versions_newest_first_with_run_id() {
        String stdout = "c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2" + FS + "1700000200" + FS
                + "更新了系统" + FS + "Run-Id: 222\n" + RS
                + "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1" + FS + "1700000100" + FS
                + "首次生成了系统" + FS + "Run-Id: 111\n" + RS;

        List<WorkspaceVersion> versions = WorkspaceVersions.parseLog(stdout);

        assertThat(versions).hasSize(2);
        // git log 原生序（新→旧）即版本序列
        assertThat(versions.get(0).commitHash()).isEqualTo("c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2");
        assertThat(versions.get(0).committedAtEpochSeconds()).isEqualTo(1700000200L);
        assertThat(versions.get(0).subject()).isEqualTo("更新了系统");
        assertThat(versions.get(0).runId()).isEqualTo("222");
        assertThat(versions.get(1).commitHash()).startsWith("a1a1");
        assertThat(versions.get(1).runId()).isEqualTo("111");
    }

    @Test
    void given_wild_commit_without_trailer_when_parse_then_excluded_from_version_sequence() {
        // 智能体 shell 面技术上可跑 git——无 Run-Id trailer 的野 commit 不是版本
        String stdout = "d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3" + FS + "1700000300" + FS
                + "wip" + FS + RS
                + "c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2" + FS + "1700000200" + FS
                + "更新了系统" + FS + "Run-Id: 222\n" + RS;

        List<WorkspaceVersion> versions = WorkspaceVersions.parseLog(stdout);

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).runId()).isEqualTo("222");
    }

    @Test
    void given_rollback_commit_when_parse_then_version_with_rollback_from_and_null_run_id() {
        // 回滚版本是版本序列成员（Rollback-From trailer 第二判据）——runId 空、rollbackFrom 锚源
        String stdout = "d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3d3" + FS + "1700000300" + FS
                + "回滚到「首次生成了系统」" + FS + "Rollback-From: a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1\n" + RS;

        List<WorkspaceVersion> versions = WorkspaceVersions.parseLog(stdout);

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).runId()).isNull();
        assertThat(versions.get(0).rollbackFrom()).isEqualTo("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1");
        assertThat(versions.get(0).subject()).isEqualTo("回滚到「首次生成了系统」");
    }

    @Test
    void given_empty_output_when_parse_then_empty_list() {
        assertThat(WorkspaceVersions.parseLog("")).isEmpty();
    }

    @Test
    void given_malformed_record_when_parse_then_skipped_without_failing_whole_log() {
        String stdout = "碎行" + RS
                + "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1" + FS + "1700000100" + FS
                + "首次生成了系统" + FS + "Run-Id: 111\n" + RS;

        List<WorkspaceVersion> versions = WorkspaceVersions.parseLog(stdout);

        assertThat(versions).hasSize(1);
    }
}
