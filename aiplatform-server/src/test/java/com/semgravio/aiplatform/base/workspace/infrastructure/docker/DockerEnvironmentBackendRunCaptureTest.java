package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * runCapture 管道排水回归（#400）：串行读流（先读尽 stdout 再读 stderr）在子进程
 * stderr 输出超管道缓冲（~64KB）时死锁——子进程写 stderr 阻塞不退出、stdout 永无
 * EOF，Java 端 readAllBytes 卡死，双方互等（实测还会拖住 surefire fork 不退）。
 * 实测触发：live 车道 ensureDevImage 现场 docker build（进度全走 stderr）。
 * 修复口径＝stderr 独立线程排水、两路读完再 waitFor，stdout/stderr 分离与返回
 * 语义不变。探针带 5s 看门狗：父端回归不排水时灌流线程超时、子进程自行退出
 * （exit 3）给父端 EOF——测试秒级干净红（断言退出码 0 失败），不挂死 CI；
 * 修复后 260KB 灌流毫秒级完成、DONE 落 stdout、退出码 0。
 * java 可执行文件取当前 JVM 自身路径（不假设 PATH，CI/本机同口径）。
 */
class DockerEnvironmentBackendRunCaptureTest {

    @Test
    @Timeout(30)
    void given_child_floods_stderr_beyond_pipe_buffer_when_captured_then_no_deadlock_and_streams_intact()
            throws Exception {
        Path dir = Files.createTempDirectory("runcapture-drain");
        Path probe = dir.resolve("StderrFlood.java");
        Files.writeString(probe, """
                class StderrFlood {
                    public static void main(String[] args) throws Exception {
                        Thread flood = new Thread(() -> {
                            try {
                                for (int i = 0; i < 4000; i++) {
                                    System.err.print("x".repeat(64) + "\\n");
                                }
                                System.err.flush();
                            } catch (Exception ignored) {
                            }
                        });
                        flood.start();
                        flood.join(5000);
                        if (flood.isAlive()) {
                            System.exit(3);
                        }
                        System.out.print("DONE");
                    }
                }
                """);
        String java = ProcessHandle.current().info().command().orElse("java");

        ExecResult r = new DockerEnvironmentBackend().runCapture(java, probe.toString());

        assertThat(r.exitCode())
                .as("子进程应正常退出（exit 3＝父端未排水、看门狗自弃——runCapture 死锁回归）")
                .isZero();
        assertThat(r.stdout()).as("stdout 应完整收到（死锁下永远到不了这里）").isEqualTo("DONE");
        assertThat(r.stderr().length())
                .as("stderr 应完整排干（4000 行 × 65 字符）")
                .isEqualTo(4000 * 65);
    }
}
