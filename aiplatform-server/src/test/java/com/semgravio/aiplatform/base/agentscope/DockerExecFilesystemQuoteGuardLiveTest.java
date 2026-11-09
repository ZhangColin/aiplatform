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
 * 智能体自产命令 ①族守卫活体验收（#440）：真 dev 工作区 +
 * {@link DockerExecFilesystem} 生产实例（windowsHost 按 os.name 真实定型）——
 * Windows 宿主上 #434 排练的挂死原形命令（内嵌双引号的 grep -E 正则族）必须
 * <b>秒级拒绝</b>而非无界挂死（#434 实测同类挂死 35 分钟、吃掉 60 分钟排练预算
 * 的 58%）；单引号改写形态（智能体按拒命文案改写重试的路径）必须照常执行成功。
 * Linux 宿主（execve 免疫）验证守卫不启用、行为不变。daemon 不在则跳过。
 */
@Tag("live")
class DockerExecFilesystemQuoteGuardLiveTest {

    private static final int PROBE_TIMEOUT_SECONDS = 300;

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
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_agent_command_when_windows_argv_violation_then_fail_fast_and_rewrite_succeeds() {
        requireDockerDaemon();
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);
        DockerExecFilesystem fs = new DockerExecFilesystem(provision.handle().containerName());
        RuntimeContext rc = RuntimeContext.empty();

        // 被检材料：含 .env 字样的真实文件（grep 有命中、退出码 0 才是真的执行了）
        assertThat(fs.write(rc, "/docs/PRD.md", "# PRD\n\n部署环境变量：.env 配置。\n").isSuccess())
                .as("探针材料写入").isTrue();

        boolean windowsHost = DockerExecFilesystem.isWindowsHostOs();
        if (windowsHost) {
            // ① 挂死原形：内嵌双引号（#434 实测命令族）——拒命秒级返回（此处能走到
            // 断言本身即证无界挂死已消），且给出改写指引
            long start = System.nanoTime();
            var rejected = fs.execute(rc, "grep -E \"\\.env\" '/workspace/docs/PRD.md'", 30);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[#440 live] 拒命耗时: %d ms（#434 同类挂死 35 min）%n", elapsedMs);

            assertThat(elapsedMs).as("fail-fast 须秒级返回（#434 挂死 35 分钟）").isLessThan(10_000);
            assertThat(rejected.exitCode())
                    .isEqualTo(DockerExecFilesystem.SHELL_GUARD_REJECTED_EXIT_CODE);
            assertThat(rejected.output())
                    .contains("#440")
                    .contains("单引号")
                    .contains("未执行");

            // ② $() 命令替换同拒（①族纪律第二条）
            var substitution = fs.execute(rc, "s=$(stat -c %s '/workspace/docs/PRD.md')", 30);
            assertThat(substitution.exitCode())
                    .isEqualTo(DockerExecFilesystem.SHELL_GUARD_REJECTED_EXIT_CODE);

            // ③ 改写重试成功路径：单引号形态（拒命文案指引的写法）照常到达容器执行
            var rewritten = fs.execute(rc, "grep -E '\\.env' '/workspace/docs/PRD.md'", 30);
            assertThat(rewritten.exitCode()).as("单引号改写应真执行: %s", rewritten.output()).isEqualTo(0);
            assertThat(rewritten.output()).contains(".env");
        }
        else {
            // Linux 形态：守卫 os.name 条件化不启用——双引号命令原样到达容器且
            // 真执行成功（生产免疫面，防一刀切回归）
            var resp = fs.execute(rc, "grep -E \"\\.env\" '/workspace/docs/PRD.md'", 30);
            assertThat(resp.exitCode()).as("Linux 守卫不得启用: %s", resp.output()).isEqualTo(0);
            assertThat(resp.output()).contains(".env");
        }
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
