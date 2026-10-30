package com.semgravio.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
 * 容器里跑 {@link WorkspaceVersions} 命令——两轮收口成版 → git log 即版本序列
 * （带 Run-Id trailer）；容器销毁重建 → 版本历史不丢（.git 落卷内）；.git 不进
 * 源码包（非交付名单单一事实）。daemon 不在则跳过（CI 无 docker 时不红）。
 */
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
    void given_two_closings_when_commit_then_git_log_is_version_sequence() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 首轮成版：写交付物 → 收口成版（摘要 + Run-Id trailer）
        exec("echo '<html>v1</html>' > /workspace/index.html");
        String hash1 = commit("首次生成了系统", "111");
        assertThat(hash1).isNotEmpty();

        // 次轮成版：改动交付物 → 收口成版（历史只追加）
        exec("echo '<html>v2</html>' > /workspace/index.html");
        String hash2 = commit("更新了系统", "222");
        assertThat(hash2).isNotEmpty().isNotEqualTo(hash1);

        // git log 即版本序列（新→旧），只收带 Run-Id trailer 的成版 commit
        ExecResult log = exec(WorkspaceVersions.listCommand());
        assertThat(log.exitCode()).isZero();
        var versions = WorkspaceVersions.parseLog(log.stdout());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).runId()).isEqualTo("222");
        assertThat(versions.get(0).subject()).isEqualTo("更新了系统");
        assertThat(versions.get(0).commitHash()).isEqualTo(hash2);
        assertThat(versions.get(1).runId()).isEqualTo("111");
        assertThat(versions.get(1).subject()).isEqualTo("首次生成了系统");
        assertThat(versions.get(1).commitHash()).isEqualTo(hash1);

        // 内容对应两轮产物：各 commit 树里的 index.html 确为对应轮内容（非空/脏 commit）
        assertThat(exec("git -C /workspace show " + hash1 + ":index.html").stdout().trim())
                .as("首版 commit 树应含 v1 产物").isEqualTo("<html>v1</html>");
        assertThat(exec("git -C /workspace show " + hash2 + ":index.html").stdout().trim())
                .as("次版 commit 树应含 v2 产物").isEqualTo("<html>v2</html>");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_committed_versions_when_container_rebuilt_then_history_survives() {
        requireDockerDaemon();
        WorkspaceId workspaceId = WorkspaceId.generate();
        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);
        exec("echo '<html>v1</html>' > /workspace/index.html");
        String hash1 = commit("首次生成了系统", "111");

        // 销毁容器（卷保留）→ 原地重建：.git 落卷内，版本历史原样续用
        docker("rm", "-f", provision.handle().containerName());
        provision = backend.createWorkspace(workspaceId, EnvKind.DEV);

        ExecResult log = exec(WorkspaceVersions.listCommand());
        assertThat(log.exitCode()).isZero();
        var versions = WorkspaceVersions.parseLog(log.stdout());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).commitHash()).isEqualTo(hash1);
        assertThat(versions.get(0).runId()).isEqualTo("111");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_committed_versions_when_pack_source_then_git_metadata_excluded() throws Exception {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        exec("echo '<html>v1</html>' > /workspace/index.html");
        commit("首次生成了系统", "111");

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

        // 两轮成版：v1 → v2（v2 改动 index.html）
        exec("echo '<html>v1</html>' > /workspace/index.html");
        String hash1 = commit("首次生成了系统", "111");
        exec("echo '<html>v2</html>' > /workspace/index.html");
        String hash2 = commit("更新了系统", "222");
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

        // 回滚后可继续迭代：下一轮 run 收口正常成新版本（基于回滚后代码）
        exec("echo '<html>v3-after-rollback</html>' > /workspace/index.html");
        String hash3 = commit("回滚后继续迭代", "333");
        assertThat(hash3).isNotEmpty().isNotEqualTo(rollbackHash);
        assertThat(exec("git -C /workspace show " + hash3 + ":index.html").stdout().trim())
                .as("回滚后继续迭代的产物应基于回滚后代码").isEqualTo("<html>v3-after-rollback</html>");
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_dirty_worktree_when_status_then_untracked_ignored_tracked_detected() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 干净树（收口成版后无未提交 tracked 改动）
        exec("echo '<html>v1</html>' > /workspace/index.html");
        commit("首次生成了系统", "111");
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

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_segment_failure_scene_when_reset_to_segment_start_then_scene_recorded_and_workspace_clean() {
        // #353 段失败回滚全链（容器活体）：无 HEAD → 锚定段起点 → 前段收口成版 →
        // 段内改坏（tracked 改 + 野 commit 新文件 + 未提交新文件 + 数据面写入）→
        // 留账落盘 → 干净回退——前段成果不动、证据可查、数据面不触、版本序列不染
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 全新工作区无 HEAD：段起点捕获以非 0 分道 → 锚 commit 钉起点（无 trailer）
        exec(WorkspaceVersions.ensureRepoCommand());
        ExecResult noHead = exec(WorkspaceVersions.headRefCommand());
        assertThat(noHead.exitCode()).as("无 HEAD 应以非 0 退出").isNotZero();
        ExecResult anchored = exec(WorkspaceVersions.anchorStartCommand());
        assertThat(anchored.exitCode()).as("锚 commit 应成功：%s", anchored.stderr()).isZero();
        assertThat(anchored.stdout().trim()).as("锚 commit 应回读 hash").isNotEmpty();

        // 前段收口成版（阶段 0）：段起点 = 前段收口 commit
        exec("echo '<html>stage0</html>' > /workspace/index.html");
        String stage0 = commit("起服了系统骨架", "111");

        // 段内改坏（切片失败现场）：tracked 改坏 + 野 commit（含新文件）+ 未提交新文件
        // + 数据面写入（回滚不触面）
        exec("echo '<html>broken</html>' > /workspace/index.html");
        exec("mkdir -p /workspace/src && echo 'broken-page' > /workspace/src/broken.tsx");
        exec("cd /workspace && git add -A && git commit -q -m '执行体野 commit'");
        exec("echo 'uncommitted-new' > /workspace/uncommitted-new.txt");
        exec("mkdir -p /workspace/data && echo 'kept' > /workspace/data/keep.txt");

        // 留账（回滚前）：失败现场相对段起点的全量 diff 落盘
        String scenePath = ".platform/failed-scene-segment-1-1.patch";
        ExecResult recorded = exec(WorkspaceVersions.recordFailureSceneCommand(stage0, scenePath));
        assertThat(recorded.exitCode()).as("留账应成功：%s", recorded.stderr()).isZero();

        // 回滚：工作树干净回退段起点
        ExecResult reset = exec(WorkspaceVersions.resetToSegmentStartCommand(stage0));
        assertThat(reset.exitCode()).as("回滚应成功：%s", reset.stderr()).isZero();

        // ① 前段成果不动：tracked 回前段内容、HEAD = 段起点（野 commit 脱落）
        assertThat(exec("cat /workspace/index.html").stdout().trim())
                .as("tracked 文件应回前段内容").isEqualTo("<html>stage0</html>");
        assertThat(exec("cd /workspace && git rev-parse HEAD").stdout().trim())
                .as("HEAD 应回段起点（野 commit 脱落）").isEqualTo(stage0);
        // ② 段内新文件两路清除：野 commit 内的由 reset 清、未提交的由 clean 清
        assertThat(exec("test -f /workspace/src/broken.tsx").exitCode())
                .as("野 commit 新文件应随 reset 脱落").isNotZero();
        assertThat(exec("test -f /workspace/uncommitted-new.txt").exitCode())
                .as("未提交新文件应被 clean 清掉").isNotZero();
        // ③ 数据面不动（git 排除面不在回滚面）
        assertThat(exec("cat /workspace/data/keep.txt").stdout().trim())
                .as("业务数据应保留").isEqualTo("kept");
        // ④ 失败现场可查（回滚不丢证据）：patch 落 .platform/、含改坏内容
        ExecResult scene = exec("cat '/workspace/" + scenePath + "'");
        assertThat(scene.stdout()).as("留账 patch 应含失败现场")
                .contains("broken").contains("broken.tsx");
        // ⑤ 版本序列不染：锚 commit 与野 commit 都不进版本流（只收 trailer 成版）
        ExecResult log = exec(WorkspaceVersions.listCommand());
        var versions = WorkspaceVersions.parseLog(log.stdout());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).commitHash()).isEqualTo(stage0);
    }

    // ---------- 工具 ----------

    /** 幂等 init + 成版提交（两道命令串联，回读 HEAD hash）。 */
    private String commit(String summary, String runId) {
        ExecResult ensured = exec(WorkspaceVersions.ensureRepoCommand());
        assertThat(ensured.exitCode()).as("仓库初始化应成功：%s", ensured.stderr()).isZero();
        ExecResult committed = exec(WorkspaceVersions.commitCommand(summary, runId));
        assertThat(committed.exitCode()).as("成版提交应成功：%s", committed.stderr()).isZero();
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
