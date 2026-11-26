package com.semgravio.aiplatform.business.project.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

/**
 * 跑前快照链与收口真实 diff 的容器内活体（#619）：真 dev 容器里跑
 * {@link WorkspaceChanges} 命令——快照提交成功且不带 Rollback-From（不进用户面
 * 版本序列）、收口 numstat 计入命令行改的文件与新增文件、排除名单（data/ 等）
 * 不跟、单文件 patch 可按文件切分。daemon 不在则跳过（跳过不算跑过——
 * 收口前本机真 docker 跑一遍并在票内留结果）。
 */
@Tag("live")
class WorkspaceChangesLiveTest {

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
    void given_snapshot_then_commit_not_a_version_and_diff_counts_command_line_and_new_files_only() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // 跑前形态：自己写一个被跟踪文件（容器基线另带应用代码，一并入快照）
        exec("mkdir -p /workspace/src && printf 'line1\\nline2\\n' > /workspace/src/a.txt");

        // 跑前快照（幂等仓库 + 全量提交）→ commit hash 基线
        ExecResult snapshot = exec(WorkspaceChanges.snapshotCommand("r-live-619"));
        assertThat(snapshot.exitCode()).as("快照提交应成功：%s", snapshot.stderr()).isZero();
        String baseline = snapshot.stdout().trim();
        assertThat(WorkspaceChanges.isCommitRef(baseline)).as("快照应回读 commit hash：%s", baseline).isTrue();
        // 提交信息只用平台标识、不带 Rollback-From——不是用户面版本
        assertThat(exec("git -C /workspace log -1 --format='%s'").stdout().trim())
                .isEqualTo("snapshot r-live-619");
        assertThat(exec("git -C /workspace log -1 --format='%b'").stdout().trim())
                .doesNotContain(WorkspaceVersions.ROLLBACK_TRAILER);

        // 内核面改动：命令行改被跟踪文件（非写文件工具）+ 新增文件 + 排除目录落物
        exec("printf 'line1\\nline2\\nline3\\n' > /workspace/src/a.txt");
        exec("printf 'brand-new\\n' > /workspace/src/b.txt");
        exec("mkdir -p /workspace/data && printf 'junk\\n' > /workspace/data/junk.txt");

        // 收口 numstat：命令行改的文件与新增文件都计入，data/ 排除不跟
        ExecResult numstat = exec(WorkspaceChanges.numstatCommand(baseline));
        assertThat(numstat.exitCode()).as("numstat 应成功：%s", numstat.stderr()).isZero();
        List<WorkspaceChanges.Numstat> entries = WorkspaceChanges.parseNumstat(numstat.stdout());
        assertThat(entries)
                .as("命令行改的文件与新增文件都应计入真实 diff")
                .contains(
                        new WorkspaceChanges.Numstat("/src/a.txt", 1, 0),
                        new WorkspaceChanges.Numstat("/src/b.txt", 1, 0));
        assertThat(entries).extracting(WorkspaceChanges.Numstat::path)
                .as("排除名单（data/）不跟")
                .doesNotContain("/data/junk.txt");

        // 单文件只读 patch：完整 patch 按文件切分，内容如实
        ExecResult patch = exec(WorkspaceChanges.patchCommand(baseline));
        assertThat(patch.exitCode()).as("patch 应成功：%s", patch.stderr()).isZero();
        Map<String, String> byFile = WorkspaceChanges.splitPatch(patch.stdout());
        assertThat(byFile).containsKeys("/src/a.txt", "/src/b.txt");
        assertThat(byFile.get("/src/a.txt")).contains("+line3");
        assertThat(byFile.get("/src/b.txt")).contains("+brand-new");

        // 快照提交不进版本序列（版本成员判据仍只认 Rollback-From）
        ExecResult log = exec(WorkspaceVersions.listCommand());
        assertThat(log.exitCode()).isZero();
        assertThat(WorkspaceVersions.parseLog(log.stdout())).isEmpty();
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_no_new_changes_when_snapshot_again_then_empty_commit_still_returns_baseline() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        ExecResult first = exec(WorkspaceChanges.snapshotCommand("r-live-619-a"));
        assertThat(first.exitCode()).as("首快照应成功：%s", first.stderr()).isZero();
        // 无新改动的下一场起跑：--allow-empty 兜住，仍返回可用基线（不会因空快照失败）
        ExecResult second = exec(WorkspaceChanges.snapshotCommand("r-live-619-b"));
        assertThat(second.exitCode()).as("空快照应成功：%s", second.stderr()).isZero();
        assertThat(WorkspaceChanges.isCommitRef(second.stdout().trim())).isTrue();
        assertThat(second.stdout().trim()).isNotEqualTo(first.stdout().trim());

        // 无改动时 diff 为空（空清单，不是不可用）
        ExecResult numstat = exec(WorkspaceChanges.numstatCommand(second.stdout().trim()));
        assertThat(numstat.exitCode()).isZero();
        assertThat(WorkspaceChanges.parseNumstat(numstat.stdout())).isEmpty();
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

    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(docker("version", "--format", "{{.Server.Version}}").ok(),
                "本机 docker daemon 不在，跳过真实链路");
    }
}
