package com.aieducenter.aiplatform.base.workspace.application;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.aieducenter.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 唤醒自愈活体验收（#170 AC#1/#AC#4，真 docker daemon + 真库 + 真编排——非假面）：
 * 删掉 READY 工作区容器（#168 型事故）→ 项目域触碰 → 自动幂等重建（卷保留）→
 * 已生成项目（卷内有起服入口）拉起应用 → 探活通过 → 预览可用，全程接口侧只见待期。
 * 未生成路径（无起服入口）唤醒后不拉应用、预览保持未生成口径。daemon 不在本机时跳过。
 */
@IntegrationTest
class WorkspaceWakeupLiveTest {

    /** 等待唤醒收敛的上界：重建（容器+中间件 ~20s）+ 应用拉起探活（60s 窗）。 */
    private static final Duration WAKE_DEADLINE = Duration.ofSeconds(120);

    /** 卷内起服入口替身（startApp 判据链 server.js → node server.js）。 */
    private static final String WRITE_SERVER_JS =
            "printf '%s' \"require('http').createServer((q,s)=>{s.end('ok')}).listen(8081)\""
                    + " > /workspace/server.js";

    @Autowired
    private WorkspaceLifecycleAppService lifecycle;

    @Autowired
    private WorkspaceConvergenceAppService convergence;

    @Autowired
    private WorkspaceProvisionAppService provisioner;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DockerEnvironmentBackend backend;

    private WorkspaceId workspaceId;

    @AfterEach
    void cleanup() {
        if (workspaceId != null) {
            workspaceRepository.findById(workspaceId.id()).ifPresent(workspace -> {
                try {
                    backend.destroyWorkspace(workspace.toHandle());
                } catch (RuntimeException ignored) {
                    // 尽力而为清理
                }
                workspaceRepository.delete(workspace);
            });
        }
    }

    @Test
    void given_container_killed_when_touch_generated_workspace_then_rebuilt_app_started_preview_ok() {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();

        // 已生成项目形态：卷内放起服入口（卷在，删容器后仍存——唤醒后可拉起），
        // 并如 run 执行体那样后台常驻起服（「已生成」的事实形态 = 应用在服）
        backend.exec(workspace.toHandle(), WRITE_SERVER_JS);
        backend.exec(workspace.toHandle(),
                "cd /workspace && nohup node server.js > .app.log 2>&1 & echo started");
        assertThat(lifecycle.exposePreview(workspaceId.value())).isEqualTo(previewUrl());

        // #168 型事故：容器被杀（卷保留）
        killContainer(workspace);

        // 触碰（项目域 API 面的收口调用）：立即返回（异步自愈启动）
        convergence.convergeAsync(workspaceId, ConvergenceFace.TOUCH, true);

        // 收敛：容器重建 + 应用拉起 + 探活通过 → 预览可用（全程待期后自然恢复）
        assertThat(awaitPreviewServing())
                .as("删容器后触碰：自动重建 + 应用拉起 + 探活通过，预览可用")
                .isEqualTo(previewUrl());
        Workspace healed = workspaceRepository.findById(workspaceId.id()).orElseThrow();
        assertThat(healed.getStatus()).isEqualTo(ProvisioningStatus.READY);
    }

    @Test
    void given_container_killed_when_touch_never_generated_workspace_then_rebuilt_without_app() {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();

        // 从未生成形态：卷内无起服入口；唤醒不拉应用（startAppOnWake=false 路径）
        killContainer(workspace);

        convergence.convergeAsync(workspaceId, ConvergenceFace.TOUCH, false);

        // 容器回来（READY），预览保持未生成口径（WSP_012，无应用可拉）
        assertThat(awaitWorkspaceReady()).isTrue();
        ApplicationException pending = awaitPreviewPending();
        assertThat(pending).isNotNull();
        assertThat(pending.getCodeMessage()).isEqualTo(WorkspaceMessage.PREVIEW_NOT_SERVING);
    }

    // ---------- 活体编排 ----------

    private Workspace provisionReadyWorkspace() {
        workspaceId = WorkspaceId.generate();
        workspaceRepository.save(Workspace.registerPending(workspaceId, EnvKind.DEV));
        provisioner.provisionForWake(workspaceId, EnvKind.DEV);   // 同步幂等置备
        return workspaceRepository.findById(workspaceId.id()).orElseThrow();
    }

    private void killContainer(Workspace workspace) {
        try {
            new ProcessBuilder("docker", "rm", "-f", workspace.getContainerName())
                    .start().waitFor();
        } catch (Exception e) {
            throw new IllegalStateException("杀容器失败", e);
        }
        assertThat(backend.containerState(workspace.toHandle())).isEqualTo(ContainerState.ABSENT);
    }

    private URI previewUrl() {
        return URI.create("http://" + workspaceId.value() + ".localhost/");
    }

    private URI awaitPreviewServing() {
        long deadline = System.currentTimeMillis() + WAKE_DEADLINE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                return lifecycle.exposePreview(workspaceId.value());
            } catch (ApplicationException e) {
                if (e.getCodeMessage() != WorkspaceMessage.WORKSPACE_STARTING
                        && e.getCodeMessage() != WorkspaceMessage.PREVIEW_NOT_SERVING) {
                    throw e;   // 非待期的真错误立即失败
                }
            }
            sleep();
        }
        return null;
    }

    private boolean awaitWorkspaceReady() {
        long deadline = System.currentTimeMillis() + WAKE_DEADLINE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Workspace current = workspaceRepository.findById(workspaceId.id()).orElse(null);
            if (current != null && current.getStatus() == ProvisioningStatus.READY) {
                return true;
            }
            sleep();
        }
        return false;
    }

    private ApplicationException awaitPreviewPending() {
        long deadline = System.currentTimeMillis() + WAKE_DEADLINE.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                lifecycle.exposePreview(workspaceId.value());
                return null;   // 起服了（未生成路径不该发生）
            } catch (ApplicationException e) {
                if (e.getCodeMessage() == WorkspaceMessage.PREVIEW_NOT_SERVING) {
                    return e;
                }
            }
            sleep();
        }
        return null;
    }

    private void sleep() {
        try {
            Thread.sleep(1000);
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
