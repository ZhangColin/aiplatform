package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 休眠物理面（#171，ADR-0016 删容器保卷；#183 删前优雅关库）与快照运行期清扫：
 * Docker CLI 假面 seam 直测——覆写 {@code runCapture} 捕获命令、免 daemon。只验命令
 * 形状：删的是容器、卷命令零出现（保卷 = 什么都不做，正是不发 {@code docker volume}
 * 命令的形状）。真 daemon 的数据保全验收见 {@code WorkspaceHibernationLiveTest}。
 */
class DockerEnvironmentBackendHibernationTest {

    /** 捕获全部命令的假面后端（全命令 exit 0；容器实态可编——inspect 回放）。 */
    private static final class CapturingBackend extends DockerEnvironmentBackend {
        final List<String> commands = new ArrayList<>();
        boolean containerRunning = true;

        @Override
        protected ExecResult runCapture(String... cmd) {
            commands.add(String.join(" ", cmd));
            if (cmd.length > 1 && "inspect".equals(cmd[1]) && "-f".equals(cmd[2])) {
                return containerRunning
                        ? new ExecResult("true\n", "", 0)
                        : new ExecResult("", "No such object", 1);
            }
            return new ExecResult("", "", 0);
        }
    }

    @Test
    void given_running_workspace_when_hibernate_then_postgres_stopped_fast_then_container_removed_volume_untouched() {
        CapturingBackend backend = new CapturingBackend();
        WorkspaceHandle handle = WorkspaceHandle.dev(
                WorkspaceId.of("42"), "ws-42", "previewnet");

        backend.hibernate(handle);

        // 删容器保卷 + 优雅关库（#183）的全命令面：探实态 → pg_ctl fast 停（断连接/
        // 回滚未竟事务，秒级——唤醒不再经历 WAL 崩溃恢复）→ 删容器；卷零触碰
        assertThat(backend.commands).containsExactly(
                "docker inspect -f {{.State.Running}} ws-42",
                "docker exec ws-42 su postgres -c "
                        + "/usr/lib/postgresql/15/bin/pg_ctl -D /workspace/data/pg "
                        + "-m fast -w -t 30 stop",
                "docker rm -f ws-42");
    }

    @Test
    void given_dead_workspace_when_hibernate_then_rm_only_without_pg_stop() {
        CapturingBackend backend = new CapturingBackend();
        backend.containerRunning = false;

        backend.hibernate(WorkspaceHandle.dev(WorkspaceId.of("42"), "ws-42", "previewnet"));

        // 已死/不在容器无多余动作（关库无从谈起），删容器口径不变
        assertThat(backend.commands).noneMatch(c -> c.contains("pg_ctl"));
        assertThat(backend.commands).contains("docker rm -f ws-42");
    }

    @Test
    void given_hibernate_when_cli_fails_then_no_throw_best_effort() {
        // 删失败（如 daemon 抖动）不抛：尽力而为，意图未落库则下轮扫描重试收敛
        DockerEnvironmentBackend backend = new DockerEnvironmentBackend() {
            @Override
            protected ExecResult runCapture(String... cmd) {
                return new ExecResult("", "daemon down", 1);
            }
        };

        backend.hibernate(WorkspaceHandle.dev(WorkspaceId.of("42"), "ws-42", "previewnet"));
    }

    @Test
    void given_snapshot_containers_when_sweep_except_then_only_non_keep_removed() {
        // 假面 docker ps：列出两个快照容器，其一在保留集（在用会话）
        List<String> commands = new ArrayList<>();
        DockerEnvironmentBackend backend = new DockerEnvironmentBackend() {
            @Override
            protected ExecResult runCapture(String... cmd) {
                commands.add(String.join(" ", cmd));
                if ("ps".equals(cmd[1])) {
                    return new ExecResult("ws-42-snap-1\nws-42-snap-2\n", "", 0);
                }
                return new ExecResult("", "", 0);
            }
        };

        int removed = backend.sweepSnapshotContainersExcept(Set.of("ws-42-snap-1"));

        // 保留集外才删（在用快照不受影响）；返回清扫数
        assertThat(removed).isEqualTo(1);
        assertThat(commands).contains("docker rm -f ws-42-snap-2");
        assertThat(commands).doesNotContain("docker rm -f ws-42-snap-1");
    }
}
