package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 唤醒底座后端能力的命令面测试（#170，Docker CLI 假面 seam）：startApp 的命令
 * 序列与判据——不依赖真实 daemon（真链路 createWorkspace 幂等重建在
 * {@code DockerEnvironmentBackendTest}；容器实态四值映射与唤醒判定归
 * {@code DockerEnvironmentBackendObservationTest}，#176 起判定共用同一探查）。
 * 假面 = 覆写 {@code runCapture} 按命令形状回放既定结果——形状谓词单源
 * {@link ProbeShape}（#401），探针形态演化不再各改字符串。
 */
class DockerEnvironmentBackendAppStartTest {

    private static final WorkspaceId ID = WorkspaceId.of("42");
    private static final WorkspaceHandle HANDLE = WorkspaceHandle.dev(ID, "ws-42", "previewnet");

    /** 假面：按命令形状回放（网关探针 / exec 拉起），余者记录。 */
    private static final class ScriptedBackend extends DockerEnvironmentBackend {

        /** 探活结果序列（true=在服）；耗尽后取末值。 */
        final List<Boolean> serving = new ArrayList<>();
        /** 拉起命令 exitCode 回放。 */
        int startExitCode = 0;
        /** 拉起命令 stdout 回放（无入口分支 = 空——哨兵判据的假面形态）。 */
        String startStdout = "started";
        /** 实际执行的命令记录（断言序列用）。 */
        final List<String> commands = new ArrayList<>();

        @Override
        protected ExecResult runCapture(String... cmd) {
            String joined = String.join(" ", cmd);
            commands.add(joined);
            // #397 幂等守卫：回环单发探「进程在不在服」（curl 退出码，任何响应=在服）
            if (ProbeShape.isLoopbackGuard(joined)) {
                boolean ok = serving.isEmpty() || serving.remove(0);
                return new ExecResult("", "", ok ? 0 : 1);
            }
            // #397 起服判据：经网关浏览器形态——回执末行状态码（200=绿，无 /_next
            // 引用即无 chunk 复测）
            if (ProbeShape.isRootProbe(joined)) {
                boolean ok = serving.isEmpty() || serving.remove(0);
                return new ExecResult(ok ? "200" : "", "", ok ? 0 : 1);
            }
            if (joined.contains("nohup")) {
                return new ExecResult(startStdout, "", startExitCode);
            }
            return new ExecResult("", "", 0);
        }

        /** 回环守卫探活调用次数。 */
        long loopbackProbeCount() {
            return commands.stream().filter(ProbeShape::isLoopbackGuard).count();
        }

        /** 经网关判据探活调用次数。 */
        long gatewayProbeCount() {
            return commands.stream().filter(ProbeShape::isRootProbe).count();
        }
    }

    @Test
    void given_app_serving_when_start_app_then_no_start_command_issued() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.serving.add(true);   // 回环守卫：进程在服
        // 网关判据随后的首探即绿（serving 耗尽取末值=true）

        backend.startApp(HANDLE);

        // 幂等：守卫在服即不重跑起服命令（重复 nohup 只产 EADDRINUSE 死实例），
        // 起服完成仍经网关判据验收（#397 与收口同款）
        assertThat(backend.commands).noneMatch(c -> c.contains("nohup"));
        assertThat(backend.gatewayProbeCount()).isPositive();
    }

    @Test
    void given_app_dead_when_start_app_then_start_command_then_wait_for_serving() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.serving.add(false);   // 拉起前探活：不在服
        backend.serving.add(false);   // 拉起后首轮：尚在启动
        backend.serving.add(true);    // 次轮：起服

        backend.startApp(HANDLE);

        assertThat(backend.commands).anySatisfy(c -> {
            assertThat(c).contains("nohup");
            // 与快照起服同款判据链（server.js → node；package.json → npm start），
            // 主容器差异：无 serve.js 静态兜底（#45 平台不代起）、无 DATABASE_URL 注入
            //（主容器 .env 在位、应用自读）
            assertThat(c).contains("server.js");
            assertThat(c).contains("npm start");
            assertThat(c).doesNotContain("serve.js");
            assertThat(c).doesNotContain("DATABASE_URL=");
        });
        // #340 演示种子施用：拉起链路起服前施用（apply.sh 恒退 0 不阻断拉起），
        // 先于判据链与 nohup（应用首屏即有内容）
        String startCommand = backend.commands.stream()
                .filter(c -> c.contains("nohup")).findFirst().orElse("");
        assertThat(startCommand).contains("sh /opt/seed/apply.sh");
        assertThat(startCommand.indexOf("/opt/seed/apply.sh"))
                .isLessThan(startCommand.indexOf("nohup"));
    }

    @Test
    void given_no_start_entry_when_start_app_then_quiet_return_without_serving_wait() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.serving.add(false);   // 前置单发探活：不在服
        backend.startStdout = "";     // 无入口分支：无 started 哨兵

        backend.startApp(HANDLE);

        // 无起服入口不拉不等待：静默返回（不视为环境故障），守卫单发回环 1 次、
        // 网关判据零探（不进长窗——唤醒未生成工作区即刻收口，不空耗）
        assertThat(backend.loopbackProbeCount()).isEqualTo(1);
        assertThat(backend.gatewayProbeCount()).isZero();
    }

    @Test
    void given_start_command_fails_when_start_app_then_environment_error() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.serving.add(false);
        backend.startExitCode = 1;

        assertThatThrownBy(() -> backend.startApp(HANDLE))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                .isEqualTo(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
    }
}
