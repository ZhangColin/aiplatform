package com.semgravio.aiplatform.base.workspace.application;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 销毁终态一致活体验收（#485，真 docker daemon + 真库 + 真编排——非假面）：
 * destroy 并入收敛互斥后，销毁×唤醒/触碰竞态两向交错收场均三清（容器/卷/注册行
 * 零残留）；级联删不动的卷（占卷容器真造）由尾部三清校验 WARN 留痕、注册行照删
 * （受理即删）。daemon 不在本机时跳过。
 */
@IntegrationTest
@Tag("live")
class WorkspaceDestroyLiveTest {

    /** 占卷容器镜像：与 DockerEnvironmentBackend.DEV_IMAGE 同源（本地必有，不另拉）。 */
    private static final String DEV_IMAGE = "aiplatform/dev:0.17";

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

    /** 三清校验留痕观测面（#485）：lifecycle 日志挂 ListAppender 断言 WARN。 */
    private Logger lifecycleLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        lifecycleLogger = (Logger) LoggerFactory.getLogger(WorkspaceLifecycleAppService.class);
        appender = new ListAppender<>();
        appender.start();   // AppenderBase 未 start 时 doAppend 静默丢弃
        lifecycleLogger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        lifecycleLogger.detachAppender(appender);
        if (workspaceId == null) {
            return;
        }
        // 真删级联已尽力：清掉测试可能留下的物理残面（占卷容器先删，卷才删得动）
        docker("rm", "-f", rogueContainerName());
        docker("rm", "-f", "ws-" + workspaceId.value());
        docker("volume", "rm", "vol-ws-" + workspaceId.value());
        workspaceRepository.findById(workspaceId.id())
                .ifPresent(workspaceRepository::delete);
    }

    @Test
    void given_wakeup_in_flight_when_destroy_then_waits_then_three_clean() throws Exception {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();
        // #168 型事故形态：容器被杀（卷保留）——触碰触发唤醒重建（真 createWorkspace
        // 在途，分钟级窗内销毁杀入）
        killContainer(workspace);
        convergence.convergeAsync(workspaceId, ConvergenceFace.TOUCH, false);
        Thread.sleep(500);   // 唤醒任务进入重建（互斥在途）后再删——竞态第一向

        lifecycle.destroy(workspaceId.value());

        // destroy 等唤醒落定再级联：唤醒刚重建的容器/卷随后被销毁收走——三清零残留
        assertThat(backend.containerState(workspace.toHandle()))
                .as("唤醒在途时删除：容器被级联收走").isEqualTo(ContainerState.ABSENT);
        assertThat(backend.volumeExists(workspace.toHandle()))
                .as("唤醒在途时删除：卷被级联收走").isFalse();
        assertThat(workspaceRepository.findById(workspaceId.id())).isEmpty();
        assertThat(threeCleanWarns()).isEmpty();   // 三清校验通过：常态零 WARN
    }

    @Test
    void given_destroy_in_flight_when_touch_then_three_clean_no_resurrect() throws Exception {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();
        AtomicReference<RuntimeException> destroyFailure = new AtomicReference<>();
        Thread destroyer = new Thread(() -> {
            try {
                lifecycle.destroy(workspaceId.value());
            } catch (RuntimeException e) {
                destroyFailure.set(e);
            }
        });
        destroyer.start();
        // 删除在途时触碰（真级联窗：docker rm/扫快照/删卷进行中）——竞态第二向；
        // 销毁收口后再触碰一次：行已删，拨针重取守卫不复活
        convergence.convergeAsync(workspaceId, ConvergenceFace.TOUCH, false);
        destroyer.join(120_000);
        assertThat(destroyFailure.get()).as("销毁收口无异常").isNull();
        convergence.convergeAsync(workspaceId, ConvergenceFace.TOUCH, false);

        // 三清零残留：容器/卷被级联收走、注册行删净且不被触碰复活
        assertThat(backend.containerState(workspace.toHandle()))
                .as("删除在途时触碰：容器被级联收走").isEqualTo(ContainerState.ABSENT);
        assertThat(backend.volumeExists(workspace.toHandle()))
                .as("删除在途时触碰：卷被级联收走").isFalse();
        assertThat(workspaceRepository.findById(workspaceId.id()))
                .as("销毁后触碰不复活注册行").isEmpty();
        assertThat(threeCleanWarns()).isEmpty();
    }

    @Test
    void given_undeletable_volume_when_destroy_then_three_clean_warn_trace() {
        requireDockerDaemon();
        Workspace workspace = provisionReadyWorkspace();
        // 占卷容器（真 docker 造）：引用卷的容器在（哪怕仅 created）——docker volume
        // rm 拒删占用卷，级联删卷必败
        docker("create", "--name", rogueContainerName(),
                "-v", "vol-ws-" + workspaceId.value() + ":/hold", DEV_IMAGE);

        lifecycle.destroy(workspaceId.value());

        // 三清校验失败留痕可查：WARN 带工作区标识与「卷仍在」
        List<ILoggingEvent> warns = threeCleanWarns();
        assertThat(warns).as("删不动的卷：三清校验 WARN 留痕").hasSize(1);
        assertThat(warns.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(warns.get(0).getFormattedMessage())
                .contains(workspaceId.value())
                .contains("三清")
                .contains("卷仍在");
        // 卷确实删不动（占用仍在）；注册行照删（受理即删）
        assertThat(backend.volumeExists(workspace.toHandle()))
                .as("占卷容器在：卷删不动（残留待收编）").isTrue();
        assertThat(workspaceRepository.findById(workspaceId.id())).isEmpty();
    }

    // ---------- 活体编排 ----------

    private Workspace provisionReadyWorkspace() {
        workspaceId = WorkspaceId.generate();
        workspaceRepository.save(Workspace.registerPending(workspaceId, EnvKind.DEV));
        provisioner.provisionForWake(workspaceId, EnvKind.DEV);   // 同步幂等置备（真容器）
        return workspaceRepository.findById(workspaceId.id()).orElseThrow();
    }

    private void killContainer(Workspace workspace) {
        docker("rm", "-f", workspace.getContainerName());
        assertThat(backend.containerState(workspace.toHandle()))
                .isEqualTo(ContainerState.ABSENT);
    }

    /** 三清校验 WARN（区分级联单步 warn：按「三清」关键语过滤 lifecycle 日志）。 */
    private List<ILoggingEvent> threeCleanWarns() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("三清"))
                .toList();
    }

    private String rogueContainerName() {
        return "rogue-vol-holder-" + (workspaceId != null ? workspaceId.value() : "na");
    }

    /** docker CLI 直发（无输出需求：DISCARD 直落 NUL——②族管道纪律，无管道无死锁）。 */
    private void docker(String... args) {
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
