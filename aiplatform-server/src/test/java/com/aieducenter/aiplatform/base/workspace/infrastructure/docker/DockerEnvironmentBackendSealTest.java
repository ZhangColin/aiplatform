package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 封存物理面（#172，ADR-0016 删卷换包）与深度唤醒冷启依赖重装：Docker CLI 假面
 * seam 直测——覆写 {@code runCapture}/{@code runCaptureBinary} 捕获命令（含 tar
 * 字节流命令）、免 daemon。只验命令形状：打包排除清单、解包回卷的清 pid、删卷
 * 幂等、startApp 的 {@code pnpm install} 前置。真 daemon 的数据保全验收见
 * {@code WorkspaceSealLiveTest}。
 */
class DockerEnvironmentBackendSealTest {

    private static final WorkspaceHandle HANDLE = WorkspaceHandle.dev(
            WorkspaceId.of("42"), "ws-42", "previewnet");

    /** 捕获全部命令的假面后端：卷在/不在可编，docker run 容器可编。 */
    static class CapturingBackend extends DockerEnvironmentBackend {
        final List<String> commands = new ArrayList<>();
        final List<String> binaryCommands = new ArrayList<>();
        final List<List<String>> binaryArgv = new ArrayList<>();
        boolean volumePresent = true;
        boolean containerRunning = false;
        boolean appServing = false;
        String appStartStdout = "";

        @Override
        protected ExecResult runCapture(String... cmd) {
            commands.add(String.join(" ", cmd));
            if ("inspect".equals(cmd[1]) && "-f".equals(cmd[2])) {
                // 缺失回执照真实 docker 文案（No such object——#176 假面与真口径一致）
                return containerRunning ? new ExecResult("true\n", "", 0)
                        : new ExecResult("", "Error: No such object: " + cmd[cmd.length - 1], 1);
            }
            if ("volume".equals(cmd[1]) && "inspect".equals(cmd[2])) {
                return volumePresent ? new ExecResult("[{}]\n", "", 0)
                        : new ExecResult("", "no such volume", 1);
            }
            if (cmd.length > 5 && cmd[5].startsWith("curl")) {
                return appServing ? new ExecResult("", "", 0)
                        : new ExecResult("", "connection refused", 7);
            }
            if (cmd.length > 5 && cmd[5].startsWith("cd /workspace")) {
                // 起服命令执行即视为起服成功（探活随后的轮询即过，不进长窗）
                appServing = true;
                return new ExecResult(appStartStdout + "started\n", "", 0);
            }
            return new ExecResult(appStartStdout, "", 0);
        }

        @Override
        protected ByteExec runCaptureBinary(byte[] stdin, String... cmd) {
            binaryCommands.add(String.join(" ", cmd));
            binaryArgv.add(List.of(cmd));
            return new ByteExec("packed".getBytes(), "", 0);
        }
    }

    @Test
    void given_quiesced_volume_when_pack_volume_then_bypass_tar_excludes_rebuildable_caches_only() {
        CapturingBackend backend = new CapturingBackend();

        byte[] packed = backend.packVolume(HANDLE);

        // 命令形状：先探卷在 → 旁路容器（入口旁路）整卷 tar 流式写 stdout
        assertThat(packed).isEqualTo("packed".getBytes());
        assertThat(backend.commands).contains("docker volume inspect vol-ws-42");
        assertThat(backend.binaryCommands).containsExactly(
                "docker run --rm --entrypoint tar -v vol-ws-42:/workspace aiplatform/dev:0.8"
                        + " czf - --exclude=./node_modules --exclude=./.pnpm-store"
                        + " --exclude=./.next -C /workspace .");
        // 数据库随包：排除清单只有三大可重建缓存（无 --exclude data/.env 等）
        assertThat(backend.binaryCommands.get(0))
                .doesNotContain("--exclude=./data", "--exclude=./.env");
        // exclude 逐项独立 argv（docker run 直达 tar 无 shell 分词——空格拼接单 argv
        // 会让排除整体失效，活体验收实证过）：三项各自成参
        assertThat(backend.binaryArgv.get(0))
                .contains("--exclude=./node_modules", "--exclude=./.pnpm-store",
                        "--exclude=./.next");
    }

    @Test
    void given_missing_volume_when_pack_volume_then_null_without_bypass_container() {
        CapturingBackend backend = new CapturingBackend();
        backend.volumePresent = false;

        byte[] packed = backend.packVolume(HANDLE);

        // 卷不在（已删/外部漂移）：无包可记，不起旁路容器
        assertThat(packed).isNull();
        assertThat(backend.binaryCommands).isEmpty();
    }

    @Test
    void given_pack_fails_when_pack_volume_then_throws() {
        DockerEnvironmentBackend backend = new DockerEnvironmentBackend() {
            @Override
            protected ExecResult runCapture(String... cmd) {
                return new ExecResult("[{}]\n", "", 0);
            }

            @Override
            protected ByteExec runCaptureBinary(byte[] stdin, String... cmd) {
                return new ByteExec(new byte[0], "tar: cannot open", 1);
            }
        };

        // 打包失败如实上抛（封存编排据此不翻意图、下轮重试）
        assertThatThrownBy(() -> backend.packVolume(HANDLE))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                .isEqualTo(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
    }

    @Test
    void given_archive_when_restore_volume_then_volume_recreated_and_pid_cleared() {
        CapturingBackend backend = new CapturingBackend();

        backend.restoreVolume(HANDLE, "archive".getBytes());

        // 命令形状：先删后建（干净落位）→ 旁路容器 stdin 解包 + 清 PGDATA 陈旧 pid
        assertThat(backend.commands).containsSequence(
                "docker volume rm vol-ws-42",
                "docker volume create vol-ws-42");
        assertThat(backend.binaryCommands).containsExactly(
                "docker run --rm -i --entrypoint sh -v vol-ws-42:/workspace aiplatform/dev:0.8"
                        + " -c tar xzf - -C /workspace && rm -f /workspace/data/pg/postmaster.pid");
    }

    @Test
    void given_restore_fails_when_restore_volume_then_throws() {
        DockerEnvironmentBackend backend = new DockerEnvironmentBackend() {
            @Override
            protected ExecResult runCapture(String... cmd) {
                return new ExecResult("", "", 0);
            }

            @Override
            protected ByteExec runCaptureBinary(byte[] stdin, String... cmd) {
                return new ByteExec(new byte[0], "tar: unexpected end", 1);
            }
        };

        assertThatThrownBy(() -> backend.restoreVolume(HANDLE, new byte[] {1}))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                .isEqualTo(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
    }

    @Test
    void given_volume_present_when_delete_volume_then_removed_and_true() {
        CapturingBackend backend = new CapturingBackend();

        boolean deleted = backend.deleteVolume(HANDLE);

        assertThat(deleted).isTrue();
        assertThat(backend.commands).containsExactly(
                "docker volume inspect vol-ws-42", "docker volume rm vol-ws-42");
    }

    @Test
    void given_volume_absent_when_delete_volume_then_noop_false() {
        CapturingBackend backend = new CapturingBackend();
        backend.volumePresent = false;

        assertThat(backend.deleteVolume(HANDLE)).isFalse();
        // 卷不在：不发 docker volume rm（已封存工作区每轮扫描的幂等 no-op 形状）
        assertThat(backend.commands).containsExactly("docker volume inspect vol-ws-42");
    }

    @Test
    void given_restored_volume_without_node_modules_when_start_app_then_deps_installed_first() {
        CapturingBackend backend = new CapturingBackend();

        backend.startApp(HANDLE);

        // 深度唤醒冷启形状：package.json 在而 node_modules 缺失 → 先 pnpm install
        //（再封存排除的依赖在此重建）→ 起服判据链不变
        assertThat(backend.commands).anyMatch(c -> c.startsWith("docker exec ws-42 sh -c cd")
                && c.contains("pnpm install")
                && c.contains("npm start")
                && c.contains("echo started"));
    }
}
