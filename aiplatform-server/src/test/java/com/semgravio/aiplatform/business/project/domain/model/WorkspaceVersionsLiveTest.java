package com.semgravio.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

/**
 * 容器内 git 管道活体（#91 版本层地基，B0 §5 副作用以真实状态为准）：真 dev
 * 容器里跑 {@link WorkspaceVersions} 保留命令——仓库初始化（#430 身份自愈）、
 * 工作树脏检、回滚到此（追加版本、只回代码不回数据）、.git 不进源码包。
 * 收口成版（Run-Id trailer）与段失败回滚等旧生成栈辅助件随审查与收口组退役
 * （#605）——本活体只覆盖留存项。daemon 不在则跳过（CI 无 docker 时不红）。
 */
@Tag("live")
class WorkspaceVersionsLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 360;

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();

    private WorkspaceProvision provision;

    @AfterEach
    void tearDown() {
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_preexisting_repo_without_identity_when_ensure_then_commit_succeeds_with_platform_signature() {
        // #430 生产实测根因：存量仓库的 .git 早已存在，旧版身份配置藏在 init-if 内
        // 被整体跳过——提交必败「Author identity unknown」。身份写入移出 init-if 后
        // 本管道前置命令无条件自愈：预置无身份仓库 → ensure → 提交成功且署平台统一名。
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 预置「已有 .git 但无 identity」仓库（存量工作区同形）
        ExecResult init = exec("git init -q -b main .");
        assertThat(init.exitCode()).as("预置仓库应成功：%s", init.stderr()).isZero();
        ExecResult noName = exec("git config --get user.name");
        assertThat(noName.exitCode()).as("预置仓库应无 user.name（复现 #430 现场）").isNotZero();

        // ensure 后身份落 repo-local config（无条件幂等），提交成功
        ExecResult ensured = exec(WorkspaceVersions.ensureRepoCommand());
        assertThat(ensured.exitCode()).as("仓库初始化应成功：%s", ensured.stderr()).isZero();
        exec("echo '<html>v1</html>' > /workspace/index.html");
        ExecResult committed = exec("cd /workspace && git add -A && git commit -q -m '手动提交'");
        assertThat(committed.exitCode()).as("提交应成功：%s", committed.stderr()).isZero();
        assertThat(exec("git config --get user.name").stdout().trim())
                .as("ensure 后身份应落 repo-local config").isEqualTo("semgravio-builder");
        assertThat(exec("git config --get user.email").stdout().trim())
                .as("ensure 后邮箱应落 repo-local config").isEqualTo("builder@semgravio.com");
        // commit 的提交者 = 平台统一署名（可审计）
        assertThat(exec("git -C /workspace log -1 --format='%an <%ae>'").stdout().trim())
                .as("commit 应署平台统一名").isEqualTo("semgravio-builder <builder@semgravio.com>");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_committed_workspace_when_pack_source_then_git_metadata_excluded() throws Exception {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        exec(WorkspaceVersions.ensureRepoCommand());
        exec("echo '<html>v1</html>' > /workspace/index.html");
        exec("cd /workspace && git add -A && git commit -q -m '提交'");

        byte[] tarball = backend.packSource(provision.handle());

        // .git 进非交付名单（单一事实）：版本元数据不进源码包（与 .env/node_modules 同口径）
        Path tar = Files.createTempFile("aiplatform-version", ".tar.gz");
        try {
            Files.write(tar, tarball);
            String listing = hostTarListing(tar);
            assertThat(listing).contains("index.html");
            // .git 目录（条目形如 ./.git/config）不进包；.gitignore 是交付物照进
            assertThat(listing).doesNotContain(WorkspaceLayout.GIT_DIR + "/");
        } finally {
            Files.deleteIfExists(tar);
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_versions_when_rollback_then_append_version_code_reverted_data_kept_and_iteration_continues() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 两版（Rollback-From trailer 即版本成员判据）：v1 → v2（v2 改动 index.html）
        exec("echo '<html>v1</html>' > /workspace/index.html");
        String hash1 = versionCommit("首次生成了系统", "11111111");
        exec("echo '<html>v2</html>' > /workspace/index.html");
        String hash2 = versionCommit("更新了系统", "22222222");
        // 业务数据（非跟踪面，落 data/ 进卷）：回滚只回代码、不应触碰
        exec("mkdir -p /workspace/data && echo 'keep-me' > /workspace/data/keep.txt");

        // 回滚到 v1：追加新 commit、代码复位、数据保留
        ExecResult rolled = exec(WorkspaceVersions.rollbackCommand(hash1, "回滚到「首次生成了系统」"));
        assertThat(rolled.exitCode()).as("回滚应成功：%s", rolled.stderr()).isZero();
        String rollbackHash = rolled.stdout().trim();
        assertThat(rollbackHash).isNotEmpty().isNotEqualTo(hash1).isNotEqualTo(hash2);

        // git log 即版本序列：追加新版本（新→旧：回滚 → v2 → v1），历史只追加不改写
        ExecResult log = exec(WorkspaceVersions.listCommand());
        assertThat(log.exitCode()).isZero();
        var versions = WorkspaceVersions.parseLog(log.stdout());
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).commitHash()).isEqualTo(rollbackHash);
        assertThat(versions.get(0).rollbackFrom()).isEqualTo(hash1);
        assertThat(versions.get(0).runId()).isNull();
        assertThat(versions.get(1).commitHash()).isEqualTo(hash2);
        assertThat(versions.get(2).commitHash()).isEqualTo(hash1);

        // 系统代码回到当时（回滚 commit 树 = v1 内容）、数据保留
        assertThat(exec("git -C /workspace show " + rollbackHash + ":index.html").stdout().trim())
                .as("回滚 commit 树应含 v1 代码").isEqualTo("<html>v1</html>");
        assertThat(exec("cat /workspace/index.html").stdout().trim())
                .as("工作树应已复位到 v1 代码").isEqualTo("<html>v1</html>");
        assertThat(exec("cat /workspace/data/keep.txt").stdout().trim())
                .as("业务数据应保留（回滚只回代码）").isEqualTo("keep-me");
        // 回滚后数据仍可写（AC2「可继续读写」——非跟踪面数据目录随卷可写、不被回滚触碰）
        exec("echo 'after-rollback' > /workspace/data/keep2.txt");
        assertThat(exec("cat /workspace/data/keep2.txt").stdout().trim())
                .as("回滚后业务数据应可继续写").isEqualTo("after-rollback");

        // 回滚后可继续迭代：下一轮提交正常成新版本（基于回滚后代码）
        exec("echo '<html>v3-after-rollback</html>' > /workspace/index.html");
        String hash3 = versionCommit("回滚后继续迭代", "33333333");
        assertThat(hash3).isNotEmpty().isNotEqualTo(rollbackHash);
        assertThat(exec("git -C /workspace show " + hash3 + ":index.html").stdout().trim())
                .as("回滚后继续迭代的产物应基于回滚后代码").isEqualTo("<html>v3-after-rollback</html>");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_snapshot_then_user_version_commit_when_list_then_snapshot_excluded_version_included() {
        // #626 自动成版＋单轴时间线：真 git 仓库里跑「跑前快照 → 生成改文件 →
        // 用户面版本提交 → 列表」——快照提交（无平台版本标记）不入时间线，版本
        // 提交（Platform-Version＋Run-Id）入列且携版本号/主题，新→旧
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 跑前快照（#619）：幂等仓库 + 全量提交，hash 作 diff 基线；不是用户版本
        ExecResult snapshot = exec(WorkspaceChanges.snapshotCommand("run-1"));
        assertThat(snapshot.exitCode()).as("跑前快照应成功：%s", snapshot.stderr()).isZero();
        String snapshotHash = snapshot.stdout().trim();
        assertThat(snapshotHash).isNotEmpty();

        // 内核改动工作区后，平台提交用户面版本 v1
        exec("echo '<html>v1</html>' > /workspace/index.html");
        ExecResult committed =
                exec(WorkspaceVersions.commitVersionCommand(1, "run-1", "做一个预约系统"));
        assertThat(committed.exitCode()).as("成版应成功：%s", committed.stderr()).isZero();
        String versionHash = committed.stdout().trim();
        assertThat(versionHash).isNotEmpty();

        // 列表（git log 过滤）：只收带版本标记的提交——快照不入列，版本入列且字段齐
        ExecResult log = exec(WorkspaceVersions.listCommand());
        assertThat(log.exitCode()).isZero();
        var versions = WorkspaceVersions.parseLog(log.stdout());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).commitHash()).isEqualTo(versionHash);
        assertThat(versions.get(0).versionNo()).isEqualTo(1);
        assertThat(versions.get(0).runId()).isEqualTo("run-1");
        assertThat(versions.get(0).subject()).isEqualTo("做一个预约系统");
        assertThat(versions).noneMatch(version -> version.commitHash().equals(snapshotHash));
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_dirty_worktree_when_status_then_untracked_ignored_tracked_detected() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 干净树（提交后无未提交 tracked 改动）
        exec(WorkspaceVersions.ensureRepoCommand());
        exec("echo '<html>v1</html>' > /workspace/index.html");
        exec("cd /workspace && git add -A && git commit -q -m '提交'");
        ExecResult clean = exec(WorkspaceVersions.statusCommand());
        assertThat(clean.exitCode()).isZero();
        assertThat(WorkspaceVersions.hasUncommittedTrackedChanges(clean.stdout())).isFalse();

        // 仅 untracked（scratch.txt，非交付面）→ restore 不触，不算会被丢弃的改动
        exec("echo 'draft' > /workspace/scratch.txt");
        ExecResult onlyUntracked = exec(WorkspaceVersions.statusCommand());
        assertThat(WorkspaceVersions.hasUncommittedTrackedChanges(onlyUntracked.stdout())).isFalse();

        // 改动 tracked 交付物（index.html）→ 脏树检出（回滚会静默丢弃，须拒）
        exec("echo '<html>v2-wip</html>' > /workspace/index.html");
        ExecResult dirty = exec(WorkspaceVersions.statusCommand());
        assertThat(WorkspaceVersions.hasUncommittedTrackedChanges(dirty.stdout())).isTrue();
    }

    // ---------- 工具 ----------

    /** 幂等 init + 手动提交携 Rollback-From trailer（版本成员判据；回读 HEAD hash）。 */
    private String versionCommit(String subject, String rollbackFrom) {
        ExecResult ensured = exec(WorkspaceVersions.ensureRepoCommand());
        assertThat(ensured.exitCode()).as("仓库初始化应成功：%s", ensured.stderr()).isZero();
        ExecResult committed = exec("cd /workspace && git add -A"
                + " && git commit -q --allow-empty -m '" + subject + "'"
                + " -m 'Rollback-From: " + rollbackFrom + "'"
                + " && git rev-parse HEAD");
        assertThat(committed.exitCode()).as("提交应成功：%s", committed.stderr()).isZero();
        return committed.stdout().trim();
    }

    private ExecResult exec(String command) {
        return backend.exec(provision.handle(), command);
    }

    private static ExecResult docker(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            int code = p.waitFor();
            return new ExecResult(out, err, code);
        } catch (Exception e) {
            return new ExecResult("", String.valueOf(e.getMessage()), 1);
        }
    }

    private static String hostTarListing(Path tarFile) throws Exception {
        Process p = new ProcessBuilder("tar", "tzf", tarFile.toString()).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }

    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(docker("version", "--format", "{{.Server.Version}}").ok(),
                "本机 docker daemon 不在，跳过真实链路");
    }
}
