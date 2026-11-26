package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.agentscope.DockerExecFilesystem.ExecOutput;

/**
 * {@link DockerExecFilesystem#runProcess}（进程执行缝，#653）：超时覆盖命令全程
 * ——起进程即计，挂死命令（进程不退出）在超时处被杀返 124，不再阻塞在 stdout
 * EOF 之后才起算（#624 首建实测卡 9.4min 的形态）。本地进程直测（os.name 分支
 * 选命令，不依赖 docker daemon）；真容器行为见 live 车道
 * （DockerExecCommandTimeoutLiveTest）。
 */
class DockerExecFilesystemRunProcessTest {

    private static final boolean WINDOWS = DockerExecFilesystem.isWindowsHostOs();

    @Test
    void given_hanging_command_when_run_then_killed_at_timeout_with_124() {
        // 挂死形态：进程 60s 不退出（Windows ping 持续吐行 / *nix sleep 静默）——
        // 旧形态（同步 readAllBytes 先读到 EOF 再起计时）会阻塞到进程自然退出，
        // preemptive 30s 兜底即红；正确形态 2s 超时窗起进程即计
        ProcessBuilder hang = WINDOWS
                ? new ProcessBuilder("ping", "-n", "60", "127.0.0.1")
                : new ProcessBuilder("sleep", "60");

        long start = System.nanoTime();
        ExecOutput out = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> DockerExecFilesystem.runProcess(hang, null, 2, "进程超时"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(out.exitCode()).isEqualTo(124);
        assertThat(out.stderr()).contains("进程超时");
        assertThat(elapsedMs).as("超时窗从起进程起算（挂死命令不得等到自然退出）").isLessThan(15_000);
    }

    @Test
    void given_large_output_command_when_run_then_complete_and_not_killed_by_timeout() {
        // ~10 万字符（远超 Windows 4KB 匿名管道容量）：泵跟不上即排水死锁；
        // 正常路径必须在超时窗内完整收完、不误杀
        ProcessBuilder big = WINDOWS
                ? new ProcessBuilder("cmd", "/c", "for /L %i in (1,1,20000) do @echo %i")
                : new ProcessBuilder("sh", "-c", "seq 1 20000");

        ExecOutput out = DockerExecFilesystem.runProcess(big, null, 30, "进程超时");

        assertThat(out.exitCode()).isEqualTo(0);
        String[] lines = new String(out.stdout(), StandardCharsets.UTF_8).trim().split("\\r?\\n");
        assertThat(lines).hasSize(20_000);
        assertThat(lines[0]).isEqualTo("1");
        assertThat(lines[lines.length - 1]).isEqualTo("20000");
    }

    @Test
    void given_nonzero_exit_and_stderr_when_run_then_passed_through() {
        ProcessBuilder failing = WINDOWS
                ? new ProcessBuilder("cmd", "/c", "echo err-face 1>&2 & exit /b 3")
                : new ProcessBuilder("sh", "-c", "echo err-face 1>&2; exit 3");

        ExecOutput out = DockerExecFilesystem.runProcess(failing, null, 30, "进程超时");

        assertThat(out.exitCode()).isEqualTo(3);
        assertThat(out.stderr()).contains("err-face");
        assertThat(out.stdout()).isEmpty();
    }

    @Test
    void given_stdin_payload_when_run_then_written_through_and_echoed_back() {
        // stdin 异步灌写不因 stdout 泵化回归（cat 族：stdin 闭环回 stdout）
        ProcessBuilder cat = WINDOWS
                ? new ProcessBuilder("cmd", "/c", "more")
                : new ProcessBuilder("sh", "-c", "cat");

        ExecOutput out = DockerExecFilesystem.runProcess(cat,
                "hello-stdin".getBytes(StandardCharsets.UTF_8), 30, "进程超时");

        assertThat(out.exitCode()).isEqualTo(0);
        assertThat(new String(out.stdout(), StandardCharsets.UTF_8)).contains("hello-stdin");
    }
}
