package com.aieducenter.aiplatform.base.workspace.infrastructure.docker;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 销毁级联清理的留痕面（#179，Docker CLI 假面 seam）：真失败（daemon 不可达/
 * 卷被占用）warn 带 stderr——删失败的资源无 DB 行、永远不在 #171 扫描面内，
 * 零留痕＝孤儿永久不可见（收敛靠 clean-ws-residue.sh 手工，直至触发器响）；
 * 「对象已不在」的幂等回执（no such container/volume，大小写两种形态——同
 * containerState 判据教训）保持静默不产噪音。命令序列形状与真链路验收归
 * {@code DockerEnvironmentBackendTest}。
 */
class DockerEnvironmentBackendCleanupTest {

    private static final WorkspaceHandle HANDLE =
            WorkspaceHandle.dev(WorkspaceId.of("42"), "ws-42", "previewnet");

    private Logger backendLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        backendLogger = (Logger) LoggerFactory.getLogger(DockerEnvironmentBackend.class);
        appender = new ListAppender<>();
        appender.start();   // AppenderBase 未 start 时 doAppend 静默丢弃
        backendLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        backendLogger.detachAppender(appender);
    }

    /** 假面：回执按命令全串可编（默认全成功），命令全记录。 */
    private static class ScriptedBackend extends DockerEnvironmentBackend {
        final List<String> commands = new ArrayList<>();

        @Override
        protected ExecResult runCapture(String... cmd) {
            commands.add(String.join(" ", cmd));
            return script(String.join(" ", cmd));
        }

        ExecResult script(String command) {
            return new ExecResult("", "", 0);
        }
    }

    @Test
    void given_daemon_unreachable_when_destroy_then_warned_and_best_effort_continues() {
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return command.equals("docker rm -f ws-42")
                        ? new ExecResult("", "Cannot connect to the Docker daemon", 1)
                        : super.script(command);
            }
        };

        backend.destroyWorkspace(HANDLE);

        // 真失败留痕：warn 带 what 与 stderr（孤儿风险口径）
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("删容器 ws-42")
                .contains("Cannot connect to the Docker daemon");
        // 尽力而为不中断：卷删除照发（失败不炸后续清理）
        assertThat(backend.commands).contains("docker volume rm vol-ws-42");
    }

    @Test
    void given_already_gone_receipts_when_destroy_then_silent() {
        // 幂等 no-op（经典大写「No such container」与 Docker Desktop 小写
        // 「no such volume」两种形态）不产噪音——重复销毁/封存后销毁是常态路径
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                if (command.equals("docker rm -f ws-42")) {
                    return new ExecResult("", "Error: No such container: ws-42", 1);
                }
                return command.equals("docker volume rm vol-ws-42")
                        ? new ExecResult("", "error: no such volume: vol-ws-42", 1)
                        : super.script(command);
            }
        };

        backend.destroyWorkspace(HANDLE);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void given_volume_in_use_when_destroy_then_volume_failure_warned() {
        ScriptedBackend backend = new ScriptedBackend() {
            @Override
            ExecResult script(String command) {
                return command.equals("docker volume rm vol-ws-42")
                        ? new ExecResult("", "volume is in use", 1)
                        : super.script(command);
            }
        };

        backend.destroyWorkspace(HANDLE);

        // 卷删失败（占用等）同样留痕：容器删净而卷滞留是最典型的孤儿形态
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("删卷 vol-ws-42")
                .contains("volume is in use");
    }
}
