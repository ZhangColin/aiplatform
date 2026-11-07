package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.ApplicationException;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * runCapture 缝 argv 双引号防呆（#401，#399 家族的结构性止血）：Windows 下
 * Java {@code ProcessBuilder} 走 CreateProcess 平面命令行、对参数内嵌 {@code "}
 * 不转义，docker.exe（Go/MSVCRT 规则）重切 argv 时把引号吃掉——{@code sh -c}
 * 命令静默变形（#399：起服 {@code sh -c "$APP"} 沦 REPL 假起服；#397：
 * WRITE_SERVER_JS 同坑长期静默）。守卫把「静默怪象」变「当场报错」。
 *
 * <p><b>平台性口径（票内留痕）</b>：这是任何 Windows JVM→docker.exe 链的确定
 * 行为（换机不消失），非单机怪癖；Linux {@code execve} 传 argv 数组逐字节原样、
 * 生产免疫——故守卫必须 {@code os.name} 条件化（Linux 上 {@code sh -c} 内双引号
 * 是合法常用写法，不得一刀切禁）。Windows 形态经覆写 {@code isWindowsHost}
 * 注入（同既有假面覆写 {@code runCapture} 的形制），测试不依赖真跑在 Windows。</p>
 */
class DockerEnvironmentBackendQuoteGuardTest {

    /** Windows 形态假面：守卫判定恒真（OS 无关可测）。 */
    private static final class WindowsHostBackend extends DockerEnvironmentBackend {
        @Override
        protected boolean isWindowsHost() {
            return true;
        }
    }

    @Test
    void given_windows_host_when_run_capture_arg_has_embedded_quote_then_fail_fast_pointing_to_399() {
        WindowsHostBackend backend = new WindowsHostBackend();

        // 文案指路 #399 与单引号纪律（重写者按文案可自愈）
        assertThatThrownBy(() -> backend.runCapture("docker", "exec", "ws-42",
                "sh", "-c", "nohup sh -c \"$APP\" > .app.log 2>&1 &"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("#399")
                .hasMessageContaining("单引号")
                .extracting(e -> ((ApplicationException) e).getCodeMessage())
                .isEqualTo(WorkspaceMessage.ENVIRONMENT_COMMAND_EMBEDDED_QUOTE);
    }

    @Test
    void given_windows_host_when_run_capture_binary_arg_has_embedded_quote_then_fail_fast() {
        WindowsHostBackend backend = new WindowsHostBackend();

        assertThatThrownBy(() -> backend.runCaptureBinary(null, "docker", "exec", "-i", "ws-42",
                "sh", "-c", "tar xzf - -C \"dir with space\""))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("#399");
    }

    @Test
    void given_windows_host_when_clean_single_quote_command_then_guard_passes_through() {
        WindowsHostBackend backend = new WindowsHostBackend();

        // 单引号纪律形态不拦：守卫只拒内嵌双引号——命令照常执行（不存在的可执行
        // 件按 runCapture 容错口径回 exit 1，不抛）
        ExecResult result = backend.runCapture("aiplatform-no-such-bin", "sh", "-c", "echo 'x'");
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void given_linux_host_when_arg_has_embedded_quote_then_behavior_unchanged() {
        DockerEnvironmentBackend backend = new DockerEnvironmentBackend() {
            @Override
            protected boolean isWindowsHost() {
                return false;   // Linux 形态：execve 原样传 argv，双引号合法
            }
        };

        // Linux 不拦（行为不变）：不存在的可执行件回 exit 1，不抛守卫异常
        assertThatCode(() -> backend.runCapture("aiplatform-no-such-bin",
                "sh", "-c", "echo \"x\""))
                .doesNotThrowAnyException();
    }
}
