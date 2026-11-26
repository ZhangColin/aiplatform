package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

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
import com.semgravio.aiplatform.base.workspace.infrastructure.docker.ThrowawayPreviewGateway;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersions;

/**
 * 回到某版活体（#627，ADR-0056 回滚面；真 dev 容器 + 真构建 + 真起服）：真工作区里
 * 两版代码 → restore 回 v1（只动 tracked）→ 停 8081 旧进程（探活至不可达）→ 删旧
 * .next 重新 build → 按平台起服路径（{@code startApp}，一次性网关作探活链路）重启
 * → 探活通过 → 记为新版本 v3（主题「回到 v1」）。断言：重启前应用确实停过、重启后
 * 跑的是 v1 代码（页面标记）、数据文件未随代码回退、时间线历史都在。daemon 不在跳过。
 */
@Tag("live")
class ProjectVersionRollbackLiveTest {

    private static final int TIMEOUT_SECONDS = 900;
    private static final Duration STOP_DEADLINE = Duration.ofSeconds(30);

    /** v2 页面（覆盖基座页——回滚后应回到基座页文案）。heredoc 单引号定界（argv 双引号坑）。 */
    private static final String WRITE_V2_PAGE =
            "cat > /workspace/src/app/page.tsx <<'PAGE_EOF'\n"
                    + "export default function Home() {\n"
                    + "  return <main>版本二标记</main>;\n"
                    + "}\n"
                    + "PAGE_EOF";

    private final DockerEnvironmentBackend backend = new DockerEnvironmentBackend();

    private WorkspaceProvision provision;
    private ThrowawayPreviewGateway gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
        if (provision != null) {
            backend.destroyWorkspace(provision.handle());
        }
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void given_two_versions_when_rollback_then_restops_rebuilds_restarts_probes_and_records_new_version() {
        requireDockerDaemon();
        gateway = ThrowawayPreviewGateway.start("rollback");
        provision = backend.createWorkspace(WorkspaceId.generate(), EnvKind.DEV);

        // v1 = 基座页面；v2 = 覆盖为「版本二标记」；业务数据落 data/（非跟踪面）
        String hash1 = platformVersionCommit(1, "run-1", "做一个预约系统");
        exec(WRITE_V2_PAGE);
        String hash2 = platformVersionCommit(2, "run-2", "把首页换个样子");
        exec("mkdir -p /workspace/data && echo 'keep-me' > /workspace/data/keep.txt");

        // 起服并确认正在服务 v2（旧进程 = v2 代码）
        backend.startApp(provision.handle());
        assertThat(curlHtml()).as("起服后应服务 v2 代码").contains("版本二标记");

        // 回滚编排（与 ProjectVersionAppService 同命令序列）：
        // restore → 停旧进程（探活至不可达）→ 删旧 .next 重新 build → 平台起服 → 探活
        ExecResult restored = exec(WorkspaceVersions.restoreCommand(hash1));
        assertThat(restored.exitCode()).as("代码复位应成功：%s", restored.stderr()).isZero();
        assertThat(exec("git -C /workspace rev-parse HEAD").stdout().trim())
                .as("restore 不提交——HEAD 仍停在 v2").isEqualTo(hash2);

        exec(ProjectVersionAppService.STOP_APP_COMMAND);
        assertThat(awaitAppDown()).as("旧应用进程应被停下（探活至 8081 不可达）").isTrue();

        ExecResult built = exec(ProjectVersionAppService.ROLLBACK_BUILD_COMMAND);
        assertThat(built.exitCode()).as("重新构建应成功：%s", built.stderr()).isZero();
        assertThat(exec("test -d /workspace/.next && echo yes").stdout().trim())
                .as("重新构建应产出 .next").isEqualTo("yes");

        backend.startApp(provision.handle());
        assertThat(probeReachable()).as("重启后 8081 应有响应（探活）").isTrue();

        // 成功成版：vN+1，主题「回到 v1」
        ExecResult rolled = exec(WorkspaceVersions.commitVersionCommand(3, "rollback-run", "回到 v1"));
        assertThat(rolled.exitCode()).as("回滚成版应成功：%s", rolled.stderr()).isZero();
        String rollbackHash = rolled.stdout().trim();

        // 时间线：新→旧 = 回滚版 v3 → v2 → v1，历史都在
        var versions = WorkspaceVersions.parseLog(exec(WorkspaceVersions.listCommand()).stdout());
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).commitHash()).isEqualTo(rollbackHash);
        assertThat(versions.get(0).versionNo()).isEqualTo(3);
        assertThat(versions.get(0).subject()).isEqualTo("回到 v1");
        assertThat(versions.get(1).commitHash()).isEqualTo(hash2);
        assertThat(versions.get(2).commitHash()).isEqualTo(hash1);

        // 工作区与运行时都是 v1 代码；业务数据保留
        assertThat(exec("cat /workspace/src/app/page.tsx").stdout())
                .as("工作树应回到 v1 基座页").contains("系统正在初始化").doesNotContain("版本二标记");
        assertThat(curlHtml()).as("重启后服务的应是 v1 代码").contains("系统正在初始化").doesNotContain("版本二标记");
        assertThat(exec("cat /workspace/data/keep.txt").stdout().trim())
                .as("活库/数据不倒带").isEqualTo("keep-me");
    }

    // ---------- 编排工具 ----------

    private String platformVersionCommit(int versionNo, String runId, String subject) {
        ExecResult committed = exec(WorkspaceVersions.commitVersionCommand(versionNo, runId, subject));
        assertThat(committed.exitCode()).as("提交应成功：%s", committed.stderr()).isZero();
        return committed.stdout().trim();
    }

    /** 轮询至 8081 回环不可达（旧进程已停）；超时返回 false。 */
    private boolean awaitAppDown() {
        long deadline = System.currentTimeMillis() + STOP_DEADLINE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!probeReachable()) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private boolean probeReachable() {
        return exec(GenerationAppService.CLOSING_PROBE).exitCode() == 0;
    }

    /** 回环取首页 HTML（服务真实响应；next dev 冷编译窗口内重试）。 */
    private String curlHtml() {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        String html = "";
        while (System.currentTimeMillis() < deadline) {
            ExecResult result = exec("curl -s --max-time 5 http://localhost:8081/");
            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                html = result.stdout();
                if (html.contains("版本二标记") || html.contains("系统正在初始化")) {
                    return html;
                }
            }
            sleep();
        }
        return html;
    }

    private ExecResult exec(String command) {
        return backend.exec(provision.handle(), command);
    }

    private void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }

    private static void requireDockerDaemon() {
        boolean available;
        try {
            available = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .start().waitFor() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "本机 docker daemon 不在，跳过活体验收");
    }
}
