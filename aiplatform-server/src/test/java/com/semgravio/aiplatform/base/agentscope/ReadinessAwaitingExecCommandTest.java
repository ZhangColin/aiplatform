package com.semgravio.aiplatform.base.agentscope;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceReadinessWaiter;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReadinessAwaitingExecCommand}：执行前隐式等工作区就绪（#62 同口径）——
 * 先经 {@link WorkspaceReadinessWaiter#awaitReadyById} 收敛、READY 才下放真实 exec；
 * 等待失败（置备 FAILED / 超时 / 记录不存在，{@link ApplicationException} 族）
 * 不抛出，以 125 + 归一化文案回 fail 结果（工具面「容器不可达」族语义），真实
 * exec 不被调用。动机：主智能体工作区工具裸 docker exec 撞置备窗口期报
 * 「No such container」（2026-11-13 本地实录，savePrd 三连败判悬空）；
 * 等待收敛逻辑本身归 {@code WorkspaceReadinessWaiterTest}，此处 mock 该缝、
 * 只验收装饰层的顺序与失败映射。
 */
@ExtendWith(MockitoExtension.class)
class ReadinessAwaitingExecCommandTest {

    private static final WorkspaceId WS_ID = WorkspaceId.of("42");

    @Mock
    private WorkspaceReadinessWaiter readinessWaiter;

    private final AtomicBoolean ready = new AtomicBoolean();
    private int delegateCalls;
    private final DockerExecFilesystem.ExecCommand delegate =
            new DockerExecFilesystem.ExecCommand() {
                @Override
                public DockerExecFilesystem.ExecOutput run(String command, byte[] stdin) {
                    delegateCalls++;
                    return new DockerExecFilesystem.ExecOutput(0, "ok".getBytes(), "");
                }

                @Override
                public DockerExecFilesystem.ExecOutput run(String command, byte[] stdin,
                        int timeoutSeconds) {
                    delegateCalls++;
                    return new DockerExecFilesystem.ExecOutput(0,
                            ("ok-" + timeoutSeconds).getBytes(), "");
                }
            };

    @Test
    void given_ready_workspace_when_run_then_awaited_before_delegate() {
        when(readinessWaiter.awaitReadyById(WS_ID)).thenAnswer(inv -> {
            ready.set(true);
            return null;
        });

        DockerExecFilesystem.ExecOutput out = command().run("cat x", null);

        assertThat(out.ok()).isTrue();
        assertThat(new String(out.stdout())).isEqualTo("ok");
        verify(readinessWaiter).awaitReadyById(WS_ID);
        assertThat(ready).as("就绪等待先于真实 exec").isTrue();
        assertThat(delegateCalls).isEqualTo(1);
    }

    @Test
    void given_timeout_aware_run_when_ready_then_timeout_passed_through() {
        DockerExecFilesystem.ExecOutput out = command().run("long", null, 300);

        assertThat(new String(out.stdout())).isEqualTo("ok-300");
    }

    @Test
    void given_await_failed_when_run_then_fail_result_without_delegate() {
        when(readinessWaiter.awaitReadyById(WS_ID))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_PROVISION_FAILED));

        DockerExecFilesystem.ExecOutput out = command().run("cat x", null);

        assertThat(out.ok()).isFalse();
        assertThat(out.exitCode()).isEqualTo(125);
        assertThat(out.stderr()).contains("WSP_010").contains("环境置备失败");
        assertThat(delegateCalls).as("等待失败不下放真实 exec").isZero();
    }

    @Test
    void given_await_timeout_when_run_then_fail_result_without_delegate() {
        when(readinessWaiter.awaitReadyById(WS_ID))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_PROVISION_TIMEOUT));

        DockerExecFilesystem.ExecOutput out = command().run("cat x", null);

        assertThat(out.ok()).isFalse();
        assertThat(out.exitCode()).isEqualTo(125);
        assertThat(out.stderr()).contains("WSP_011").contains("超时");
        assertThat(delegateCalls).isZero();
    }

    @Test
    void given_workspace_absent_when_run_then_fail_result_without_delegate() {
        when(readinessWaiter.awaitReadyById(WS_ID))
                .thenThrow(new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));

        DockerExecFilesystem.ExecOutput out = command().run("cat x", null);

        assertThat(out.ok()).isFalse();
        assertThat(out.stderr()).contains("WSP_001").contains("工作区不存在");
        assertThat(delegateCalls).isZero();
    }

    @Test
    void given_docker_exec_factory_when_created_then_no_exec_before_run() {
        // 装配口形态：工厂只包不执行（工具注册期不触就绪等待/docker）
        DockerExecFilesystem.ExecCommand exec =
                ReadinessAwaitingExecCommand.dockerExec("ws-42", WS_ID, readinessWaiter);
        verify(readinessWaiter, never()).awaitReadyById(any());
        assertThat(exec).isNotNull();
    }

    // ---------- 测试数据 ----------

    private ReadinessAwaitingExecCommand command() {
        return new ReadinessAwaitingExecCommand(delegate, readinessWaiter, WS_ID);
    }
}
