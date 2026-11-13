package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.collection.CollUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无主收编活体验收（#486 AC，真 docker daemon + 真库注册表——非假面）：不在注册表
 * 的工作区容器/卷被收编删除（注册表准绳点名删，非 prune）；新建宽限窗内不误杀；
 * 在册休眠工作区卷（ADR-0016 用户数据）永不被触碰。扫描轮编排接线（保留集来源、
 * 宽限线口径、库不可达整体跳过）归 {@code WorkspaceHibernationAppServiceTest}
 * 服务缝锁死，本类直驱收编端口（与扫描轮同一调用形）验真 daemon 面。daemon 不在
 * 本机时跳过。
 *
 * <p>测试卫生（硬约束）：保留集＝注册表全量 ∪ 开测前本机既有 ws/vol-ws 现场全量
 * ——本机历史 vol-ws-* 不是本票现场，绝不顺手清；收编面只点名删本次自造的无主
 * 资源。在册锚卷刻意在保留集快照<b>之后</b>创建（其编号经注册表行入保留集）——
 * 锚卷存活只归因注册表准绳，与「快照在场」无涉。</p>
 */
@IntegrationTest
@Tag("live")
class WorkspaceOrphanSweepLiveTest {

    /** 无主现场容器镜像：与 DockerEnvironmentBackend.DEV_IMAGE 同源（本地必有，不另拉）。 */
    private static final String DEV_IMAGE = "aiplatform/dev:0.17";

    /** 容器侧既有现场命名（含快照/fork 等一切 ws-<digits> 前缀资源）。 */
    private static final Pattern WS_NAMED_RESOURCE = Pattern.compile("^ws-(\\d+).*$");

    /** 卷侧既有现场命名（全名工作区卷）。 */
    private static final Pattern VOL_WS_RESOURCE = Pattern.compile("^vol-ws-(\\d+)$");

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DockerEnvironmentBackend backend;

    /** 在册休眠锚（行在、容器无、卷在＝ADR-0016 用户数据形态）：卷永不收编。 */
    private WorkspaceId registeredId;

    /** 无主现场（容器在跑＋卷在）：收编面唯一合法目标。 */
    private WorkspaceId orphanId;

    @AfterEach
    void cleanup() {
        if (orphanId != null) {
            dockerDiscard("rm", "-f", "ws-" + orphanId.value());
            dockerDiscard("volume", "rm", "vol-ws-" + orphanId.value());
        }
        if (registeredId != null) {
            dockerDiscard("volume", "rm", "vol-ws-" + registeredId.value());
            workspaceRepository.findById(registeredId.id())
                    .ifPresent(workspaceRepository::delete);
        }
    }

    @Test
    void given_unowned_resources_when_sweep_then_grace_first_then_reclaim_registered_kept() {
        requireDockerDaemon();
        // 现场铺场：①注册表落休眠锚行（此刻尚无卷——锚卷存活只归因注册表准绳）；
        // ②保留集快照（注册表 ∪ 本机既有现场）；③造锚卷＋无主容器（在跑）＋无主卷
        registeredId = registerHibernatedAnchor();
        Set<Long> keep = keepSetSnapshot();
        dockerDiscard("volume", "create", "vol-ws-" + registeredId.value());
        orphanId = WorkspaceId.generate();
        dockerDiscard("run", "-d", "--name", "ws-" + orphanId.value(),
                "--entrypoint", "sleep", DEV_IMAGE, "infinity");
        dockerDiscard("volume", "create", "vol-ws-" + orphanId.value());

        // 第一向（AC：新建宽限窗防误杀）：生产口径宽限线（本轮时刻减 10 分钟）——
        // 刚造的无主容器/卷都在窗内，不收
        backend.sweepOrphanWorkspaceContainers(keep, LocalDateTime.now().minusMinutes(10));
        backend.sweepOrphanWorkspaceVolumes(keep, LocalDateTime.now().minusMinutes(10));
        assertThat(backend.containerState(handle(orphanId)))
                .as("宽限窗内的无主容器不被误杀").isEqualTo(ContainerState.RUNNING);
        assertThat(backend.volumeExists(handle(orphanId)))
                .as("宽限窗内的无主卷不被误杀").isTrue();

        // 第二向（AC：真无主收编删除）：宽限线推到未来（＝下轮起资源已出窗）——
        // 无主容器/卷被收编点名删除；在册休眠卷永不被触碰（ADR-0016）
        backend.sweepOrphanWorkspaceContainers(keep, LocalDateTime.now().plusMinutes(1));
        backend.sweepOrphanWorkspaceVolumes(keep, LocalDateTime.now().plusMinutes(1));
        assertThat(backend.containerState(handle(orphanId)))
                .as("出窗的真无主容器被收编").isEqualTo(ContainerState.ABSENT);
        assertThat(backend.volumeExists(handle(orphanId)))
                .as("出窗的真无主卷被收编").isFalse();
        assertThat(backend.volumeExists(handle(registeredId)))
                .as("在册休眠卷永不被触碰（ADR-0016）").isTrue();
    }

    // ---------- 活体编排 ----------

    /** 在册休眠锚：行经状态机落库（READY→休眠意图），无物理容器——休眠保卷形态。 */
    private WorkspaceId registerHibernatedAnchor() {
        WorkspaceId id = WorkspaceId.generate();
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(handle(id)));
        pending.hibernate();
        workspaceRepository.save(pending);
        return id;
    }

    /**
     * 收编保留集快照：注册表全量编号（准绳正本——休眠锚经此入集）∪ 本机既有
     * ws/vol-ws 现场全量（测试卫生：历史资源不是本票现场，绝不顺手清）。清单
     * 失败即抛（fail-safe：拿不到既有现场时绝不发起收编，宁可不测不误删）。
     */
    private Set<Long> keepSetSnapshot() {
        Set<Long> keep = CollUtil.newHashSet();
        for (Workspace workspace : workspaceRepository.findAll()) {
            keep.add(workspace.getId());
        }
        keep.addAll(listedResourceIds(
                new String[] {"docker", "ps", "-a", "--format", "{{.Names}}"},
                WS_NAMED_RESOURCE));
        keep.addAll(listedResourceIds(
                new String[] {"docker", "volume", "ls", "--format", "{{.Name}}"},
                VOL_WS_RESOURCE));
        return keep;
    }

    /** 按命名提取既有现场的工作区编号（②族纪律：先读尽 stdout 再 waitFor）。 */
    private static Set<Long> listedResourceIds(String[] cmd, Pattern pattern) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException("docker 清单失败: " + String.join(" ", cmd));
            }
            Set<Long> ids = CollUtil.newHashSet();
            for (String line : out.lines().map(String::trim).toList()) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    ids.add(Long.parseLong(matcher.group(1)));
                }
            }
            return ids;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 清单被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("docker 清单未成: " + String.join(" ", cmd), e);
        }
    }

    private static WorkspaceHandle handle(WorkspaceId id) {
        return WorkspaceHandle.dev(id, "ws-" + id.value(), "previewnet");
    }

    /** docker CLI 直发（无输出需求：DISCARD 直落 NUL——②族管道纪律，无管道无死锁）。 */
    private void dockerDiscard(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 命令被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("docker 命令失败: " + String.join(" ", cmd), e);
        }
    }

    private static void requireDockerDaemon() {
        boolean available;
        try {
            available = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "本机 docker daemon 不在，跳过活体验收");
    }
}
