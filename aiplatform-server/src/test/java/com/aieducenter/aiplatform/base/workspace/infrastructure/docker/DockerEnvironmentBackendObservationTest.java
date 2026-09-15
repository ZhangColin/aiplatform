package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测探查物理面（#173）：Docker CLI 假面 seam 直测——覆写 {@code runCapture}
 * 编排 inspect/du 回执，免 daemon。验三件事：①实态一瞥的四值映射（含「容器
 * 不在」与「探查失败」的诚实区分——stderr 有无 No such object）；②卷用量
 * du 字节解析与容缺口径（卷不在/du 失败/解析不出 → null）；③探查零副作用
 * （卷不在时不起旁路容器——docker run -v 会自动建缺失卷，只读探查不能
 * resurrect）。真 daemon 的数值验收归活体联调（#175）。
 */
class DockerEnvironmentBackendObservationTest {

    /** 假面后端：命令可脚本（按命令前缀给回执），全命令记录（断言形状）。 */
    private static class ScriptedBackend extends DockerEnvironmentBackend {
        final List<String> commands = new ArrayList<>();
        final List<String> runCommands = new ArrayList<>();

        @Override
        protected ExecResult runCapture(String... cmd) {
            commands.add(String.join(" ", cmd));
            if (cmd.length > 1 && "run".equals(cmd[1])) {
                runCommands.add(String.join(" ", cmd));
            }
            return script(String.join(" ", cmd));
        }

        ExecResult script(String command) {
            return new ExecResult("", "", 0);
        }
    }

    private static WorkspaceHandle handle() {
        return WorkspaceHandle.dev(WorkspaceId.of("42"), "ws-42", "previewnet");
    }

    // ---------- 实态一瞥：四值映射 ----------

    @Test
    void given_inspect_true_when_container_state_then_running() {
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return command.startsWith("docker inspect -f")
                        ? new ExecResult("true\n", "", 0) : super.script(command);
            }
        };

        assertThat(backend.containerState(handle())).isEqualTo(ContainerState.RUNNING);
    }

    @Test
    void given_inspect_false_when_container_state_then_stopped() {
        // 容器在但没在跑（显式 stop 等残留）——与「不在」分示
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return command.startsWith("docker inspect -f")
                        ? new ExecResult("false\n", "", 0) : super.script(command);
            }
        };

        assertThat(backend.containerState(handle())).isEqualTo(ContainerState.STOPPED);
    }

    @Test
    void given_no_such_object_when_container_state_then_absent() {
        // 真实 docker 的对象缺失回执：no such object——休眠/封存后的正常态。两种
        // 大小写形态都认：经典 CLI 回「Error: No such object」、Docker Desktop 29
        // 回小写「error: no such object」（#176 联调实测：只认大写会把缺失全归
        // UNKNOWN，唤醒判定整体让路不收敛）
        assertThat(scriptedState("", "Error: No such object: ws-42", 1)
                .containerState(handle())).isEqualTo(ContainerState.ABSENT);
        assertThat(scriptedState("", "error: no such object: ws-42", 1)
                .containerState(handle())).isEqualTo(ContainerState.ABSENT);
    }

    @Test
    void given_daemon_unreachable_when_container_state_then_unknown_not_absent() {
        // 探查失败 ≠ 容器不在（诚实位）：docker 宕不伪装成全员漂移
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return command.startsWith("docker inspect -f")
                        ? new ExecResult("", "Cannot connect to the Docker daemon", 1)
                        : super.script(command);
            }
        };

        assertThat(backend.containerState(handle())).isEqualTo(ContainerState.UNKNOWN);
    }

    @Test
    void given_container_states_when_confidently_not_running_then_only_absent_and_stopped() {
        // 唤醒判据（#176）：重建权只及于有回执的不在（STOPPED/ABSENT）；RUNNING 与
        // UNKNOWN 不在其列——探查失败≠容器不在，盲重建的预清 rm -f 会杀健康容器
        assertThat(scriptedState("true\n", "", 0)
                .containerState(handle()).confidentlyNotRunning()).isFalse();
        assertThat(scriptedState("false\n", "", 0)
                .containerState(handle()).confidentlyNotRunning()).isTrue();
        assertThat(scriptedState("", "Error: No such object: ws-42", 1)
                .containerState(handle()).confidentlyNotRunning()).isTrue();
        assertThat(scriptedState("", "Cannot connect to the Docker daemon", 1)
                .containerState(handle()).confidentlyNotRunning()).isFalse();
    }

    private static ScriptedBackend scriptedState(String out, String err, int code) {
        return new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return command.startsWith("docker inspect -f")
                        ? new ExecResult(out, err, code) : super.script(command);
            }
        };
    }

    // ---------- 卷用量：du 字节解析与容缺 ----------

    @Test
    void given_volume_with_du_output_when_volume_size_then_bytes_parsed() {
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker volume inspect")) {
                    return new ExecResult("[{}]\n", "", 0);
                }
                if (command.startsWith("docker run")) {
                    return new ExecResult("2064384\t/workspace\n", "", 0);
                }
                return super.script(command);
            }
        };

        assertThat(backend.volumeSizeBytes(handle())).isEqualTo(2064384L);
        // 旁路形制：--entrypoint du、挂全卷、字节口径（-sb）
        assertThat(backend.runCommands).containsExactly(
                "docker run --rm --entrypoint du -v vol-ws-42:/workspace "
                        + "aiplatform/dev:0.8 -sb /workspace");
    }

    @Test
    void given_missing_volume_when_volume_size_then_null_and_no_bypass_container() {
        // 卷不在（封存已删/外部漂移）：null 容缺，且不起旁路容器——docker run -v
        // 对缺失卷会自动创建，只读探查不能有 resurrect 副作用
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker volume inspect")) {
                    return new ExecResult("", "no such volume", 1);
                }
                return super.script(command);
            }
        };

        assertThat(backend.volumeSizeBytes(handle())).isNull();
        assertThat(backend.runCommands).isEmpty();
    }

    @Test
    void given_du_fails_or_garbage_when_volume_size_then_null() {
        // du 失败（exit 1）与解析不出（非数值 stdout）一律 null：观测容缺不抛
        ScriptedBackend failing = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker run")) {
                    return new ExecResult("", "du failed", 1);
                }
                return super.script(command);
            }
        };
        ScriptedBackend garbage = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.startsWith("docker run")) {
                    return new ExecResult("not-a-number\t/workspace\n", "", 0);
                }
                return super.script(command);
            }
        };

        assertThat(failing.volumeSizeBytes(handle())).isNull();
        assertThat(garbage.volumeSizeBytes(handle())).isNull();
    }
}
