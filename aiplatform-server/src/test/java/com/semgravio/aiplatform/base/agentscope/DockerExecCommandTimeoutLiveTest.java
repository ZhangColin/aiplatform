package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

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

import io.agentscope.core.agent.RuntimeContext;

/**
 * bash 工具超时语义活体验收（#653）：真 dev 工作区 + {@link DockerExecFilesystem}
 * 生产实例——挂死命令（{@code sleep 600}，无输出不退出）必须在超时秒数处被杀
 * 返 124（旧形态阻塞在 stdout EOF 之后才起计时，#624 首建实测卡 9.4min）；
 * 超时前已产出的输出不丢；正常长输出完整不被误杀。daemon 不在则跳过。
 */
@Tag("live")
class DockerExecCommandTimeoutLiveTest {

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();

    private WorkspaceProvision provision;

    @AfterEach
    void tearDown() {
        // 断言失败也不留容器：按命名约定兜底级联清理
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(300)
    void given_hanging_bash_when_executed_then_killed_at_timeout_with_partial_output() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        DockerExecFilesystem fs = new DockerExecFilesystem(provision.handle().containerName());
        RuntimeContext rc = RuntimeContext.empty();

        // ① 缺陷原形：静默挂死（无输出不退出）——超时窗从起进程起算，5s 处杀返 124
        long start = System.nanoTime();
        var hung = fs.execute(rc, "sleep 600", 5);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[#653 live] 挂死命令耗时: %d ms（旧形态 #624 实测卡 9.4 min）%n", elapsedMs);

        assertThat(elapsedMs).as("挂死命令须在超时秒数量级返回（600s 自然退出＝失明未修）")
                .isLessThan(60_000);
        assertThat(hung.exitCode()).isEqualTo(124);
        assertThat(hung.output()).contains("超时");

        // ② 超时前已产出的输出不丢（强杀后取泵已读到的部分 stdout）
        var partial = fs.execute(rc, "echo partial-before-hang; sleep 600", 5);
        assertThat(partial.exitCode()).isEqualTo(124);
        assertThat(partial.output()).contains("partial-before-hang").contains("超时");

        // ③ 正常长输出（~7.9 万字符）完整、不被超时误杀
        var big = fs.execute(rc, "seq 1 15000", 60);
        assertThat(big.exitCode()).as("长输出不得误杀: %s", big.output()).isEqualTo(0);
        assertThat(big.output()).contains("15000");
        assertThat(big.output()).hasSizeGreaterThan(70_000);
        assertThat(big.truncated()).isFalse();
    }

    // ---------- 门卫 ----------

    private static void requireDockerDaemon() {
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实链路");
    }

    private static boolean dockerAvailable() {
        return docker("version", "--format", "{{.Server.Version}}").ok();
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
}
