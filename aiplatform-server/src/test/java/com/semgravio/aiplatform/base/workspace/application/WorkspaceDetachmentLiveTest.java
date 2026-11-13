package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import cn.hutool.core.collection.CollUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在册失联分级处置活体验收（#488 AC，真 docker daemon + 真库——非假面）：两形态
 * 直驱分级处置面（与扫描轮同一调用形）——①行在、项目删（认领集不含）、容器卷在
 * → 只标记失联、行与物理资源原封不动（机器不删可能是用户数据的卷，ADR-0045）；
 * ②行在、项目删、物理全空 → 删行做注册表卫生，标记/采样行随 FK 级联消亡（#487
 * 先例）。扫描轮编排接线（认领集来源、时序）归 {@code WorkspaceHibernationAppServiceTest}
 * 服务缝。daemon 不在本机时跳过。
 *
 * <p>测试卫生（硬约束）：认领集＝注册表全量编号 − 自造目标行（本机/测试库既有
 * 行全部认领，绝不误触他人现场）；收尾只清自造现场（容器/卷/注册行——标记与采样
 * 行随注册行级联消亡）。</p>
 */
@IntegrationTest
@Tag("live")
class WorkspaceDetachmentLiveTest {

    /** 无主现场容器镜像：与 DockerEnvironmentBackend.DEV_IMAGE 同源（本地必有，不另拉）。 */
    private static final String DEV_IMAGE = "aiplatform/dev:0.17";

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceLostMarkRepository lostMarkRepository;

    @Autowired
    private WorkspaceObservationSampleRepository sampleRepository;

    @Autowired
    private DockerEnvironmentBackend backend;

    @Autowired
    private WorkspaceDetachmentAppService detachment;

    /** 自造目标行（分级处置唯一合法目标）。 */
    private WorkspaceId targetId;

    @AfterEach
    void cleanup() {
        if (targetId != null) {
            dockerDiscard("rm", "-f", "ws-" + targetId.value());
            dockerDiscard("volume", "rm", "vol-ws-" + targetId.value());
            workspaceRepository.findById(targetId.id())
                    .ifPresent(workspaceRepository::delete);   // 标记/采样行随 FK 级联消亡
        }
    }

    @Test
    void given_unclaimed_row_with_physical_resources_when_reconcile_then_marked_row_and_resources_kept() {
        requireDockerDaemon();
        // 现场铺场：注册行（出宽限窗）＋真容器（在跑）＋真卷——销毁中途吞错的静止现场
        targetId = registerAgedRow();
        dockerDiscard("volume", "create", "vol-ws-" + targetId.value());
        dockerDiscard("run", "-d", "--name", "ws-" + targetId.value(),
                "--entrypoint", "sleep", DEV_IMAGE, "infinity");
        assertThat(backend.containerState(handle(targetId))).isEqualTo(ContainerState.RUNNING);

        detachment.reconcileDetached(registeredRows(), claimsExcept(targetId), LocalDateTime.now());

        // 形态①（AC）：被标记失联而非删除——后台清单可见（lostAt 有值）、行在、
        // 容器卷原封不动（两清单口径分离：在册≠无主，物理收编永不碰它）
        assertThat(workspaceRepository.findById(targetId.id())).isPresent();
        assertThat(lostMarkRepository.findById(targetId.id())).isPresent();
        assertThat(backend.containerState(handle(targetId)))
                .as("在册失联的容器不被分级处置动（待人工裁决）")
                .isEqualTo(ContainerState.RUNNING);
        assertThat(backend.volumeExists(handle(targetId)))
                .as("在册失联的卷不被分级处置动（可能是用户数据 ADR-0016）")
                .isTrue();
    }

    @Test
    void given_unclaimed_hollow_row_when_reconcile_then_row_purged_and_children_cascade() {
        requireDockerDaemon();
        // 现场铺场：注册行（出宽限窗）＋无物理（无容器无卷）＋采样行（级联验收锚）
        targetId = registerAgedRow();
        sampleRepository.save(WorkspaceObservationSample.record(
                targetId, ContainerState.ABSENT, null, LocalDateTime.now()));
        assertThat(backend.containerState(handle(targetId))).isEqualTo(ContainerState.ABSENT);
        assertThat(backend.volumeExists(handle(targetId))).isFalse();

        detachment.reconcileDetached(registeredRows(), claimsExcept(targetId), LocalDateTime.now());

        // 形态②（AC）：空壳行被卫生删除；标记不落、采样行随 FK 级联消亡（#487 通道）
        assertThat(workspaceRepository.findById(targetId.id()))
                .as("物理全空的在册失联行被卫生删除").isEmpty();
        assertThat(lostMarkRepository.findById(targetId.id())).isEmpty();
        assertThat(sampleRepository.findById(targetId.id()))
                .as("采样行随注册行级联消亡（无需另写清理）").isEmpty();
        // 收尾防泄漏：cleanup 不再需要删行（已删），幂等
        targetId = null;
    }

    // ---------- 活体编排 ----------

    /** 注册行（READY、出宽限窗——最近触碰拨 1 小时前）。 */
    private WorkspaceId registerAgedRow() {
        WorkspaceId id = WorkspaceId.generate();
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(handle(id)));
        pending.markTouched(LocalDateTime.now().minusHours(1));
        workspaceRepository.save(pending);
        return id;
    }

    /** 注册表全量行（分级处置与扫描轮同一入参形）。 */
    private List<Workspace> registeredRows() {
        return workspaceRepository.findAll();
    }

    /** 认领集＝注册表全量编号 − 自造目标（既有现场全部认领，绝不误触）。 */
    private Set<Long> claimsExcept(WorkspaceId target) {
        Set<Long> claims = CollUtil.newHashSet();
        workspaceRepository.findAll().forEach(workspace -> claims.add(workspace.getId()));
        claims.remove(target.id());
        return claims;
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
