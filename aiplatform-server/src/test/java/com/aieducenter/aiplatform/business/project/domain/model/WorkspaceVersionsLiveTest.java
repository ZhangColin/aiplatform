package com.aieducenter.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

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
