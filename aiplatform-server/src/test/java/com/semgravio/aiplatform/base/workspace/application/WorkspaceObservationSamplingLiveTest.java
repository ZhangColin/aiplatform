package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 占用采样落库活体验收（#487 AC，真 docker daemon + 真库——非假面）：真卷写字节
 * → 采样轮（真 {@code DockerEnvironmentBackend}：inspect 实态＋旁路容器 du 全卷）
 * → 采样值落库 → <b>观测读面零 docker 纯读库回读</b>，采样值与独立 du 实测一致、
 * 实态如实、新鲜度标注随行。编排缝（fire-and-forget/在途让路/封存不采卷/宁漏
 * 勿误）归 {@code WorkspaceObservationSamplingAppServiceTest} 锁死，本类直驱采样
 * 轮方法（与扫描轮后台线程同一执行体）验真 daemon 面。daemon 不在本机时跳过。
 *
 * <p>测试卫生（硬约束）：只造/只清本用例自生成编号的卷与注册行；本机既有
 * vol-ws-* 历史现场不是本票现场，绝不触碰。</p>
 */
@IntegrationTest
@Tag("live")
class WorkspaceObservationSamplingLiveTest {

    /** 旁路容器镜像：与 DockerEnvironmentBackend.DEV_IMAGE 同源（本地必有，不另拉）。 */
    private static final String DEV_IMAGE = "aiplatform/dev:0.17";

    /** 种入卷内的已知负载：8 MiB（dd 整块写，du 与 ls 尺寸同源无碎片口径分歧）。 */
    private static final String LOAD_BYTES = "8388608";

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceObservationSampleRepository sampleRepository;

    @Autowired
    private WorkspaceObservationSamplingAppService samplingService;

    @Autowired
    private WorkspaceObservationAppService observationAppService;

    /** 本用例自生成编号（现场锚：注册行＋卷，二者同号同名约定）。 */
    private WorkspaceId workspaceId;

    @AfterEach
    void cleanup() {
        if (workspaceId != null) {
            dockerDiscard("volume", "rm", "vol-ws-" + workspaceId.value());
            workspaceRepository.findById(workspaceId.id())
                    .ifPresent(workspaceRepository::delete);
        }
    }

    @Test
    void given_real_volume_with_known_load_when_sample_round_then_sampled_value_matches_du() {
        requireDockerDaemon();
        // 现场铺场：注册休眠锚行（无物理容器——实态应采到无容器）＋真卷＋已知负载
        workspaceId = registerHibernatedAnchor();
        String volume = "vol-ws-" + workspaceId.value();
        dockerDiscard("volume", "create", volume);
        dockerDiscard("run", "--rm", "-v", volume + ":/workspace",
                "--entrypoint", "dd", DEV_IMAGE,
                "if=/dev/zero", "of=/workspace/known-load.bin",
                "bs=" + LOAD_BYTES, "count=1");

        // 采样轮（与扫描轮后台线程同一执行体直驱）
        samplingService.sampleRound();

        // 观测读面零 docker 纯读库回读（#487 读路径全链：注册表＋采样表 → 观测帧）
        WorkspaceObservation observation =
                observationAppService.observation(workspaceId.value());
        long duMeasured = independentDu(volume);

        // AC：真实 docker 下采样值与 du 实测一致（独立进程独立调用的 du——非同值
        // 复算：值经 探查→解析→落库→回读 全链保真才相等）
        assertThat(observation.volumeSizeBytes())
                .as("采样值与独立 du 实测一致（%s vs %s）",
                        observation.volumeSizeBytes(), duMeasured)
                .isEqualTo(duMeasured);
        assertThat(duMeasured).isGreaterThanOrEqualTo(Long.parseLong(LOAD_BYTES));
        // 实态如实（注册行无容器）＋新鲜度标注随行（采样时刻就在当下）
        assertThat(observation.containerState()).isEqualTo(ContainerState.ABSENT);
        assertThat(observation.sampledAt())
                .isAfter(LocalDateTime.now().minusMinutes(5));

        // 形状验收：每工作区一行（复采同行覆盖，不另落新行）＋注册行删除即级联清采样行
        samplingService.sampleRound();
        assertThat(sampleRepository.findAllById(List.of(workspaceId.id()))).hasSize(1);
        workspaceRepository.findById(workspaceId.id()).ifPresent(workspaceRepository::delete);
        assertThat(sampleRepository.findById(workspaceId.id()))
                .as("销毁删注册行即级联清采样行（FK）").isEmpty();
    }

    // ---------- 活体编排 ----------

    /** 在册休眠锚：行经状态机落库（READY→休眠意图），无物理容器——卷是唯一物理现场。 */
    private WorkspaceId registerHibernatedAnchor() {
        WorkspaceId id = WorkspaceId.generate();
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(handle(id)));
        pending.hibernate();
        workspaceRepository.save(pending);
        return id;
    }

    /**
     * 独立 du 实测（真值锚）：旁路容器 du -sb 全卷——与采样所用的探查形制同口径
     * （du 全卷含可重建缓存），但由本用例独立进程独立发起，值经全链落库回读相等
     * 才算一致。②族纪律：先读尽 stdout 再 waitFor。
     */
    private static long independentDu(String volume) {
        String out = dockerCapture("run", "--rm", "--entrypoint", "du",
                "-v", volume + ":/workspace", DEV_IMAGE, "-sb", "/workspace");
        return Long.parseLong(out.trim().split("\\s+")[0]);
    }

    private static WorkspaceHandle handle(WorkspaceId id) {
        return WorkspaceHandle.dev(id, "ws-" + id.value(), "previewnet");
    }

    /** docker CLI 取 stdout（②族纪律：读尽输出再 waitFor，退出码非 0 即抛）。 */
    private static String dockerCapture(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException(
                        "docker 命令失败(" + code + "): " + String.join(" ", cmd)
                                + " — " + err.trim());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 命令被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("docker 命令未成: " + String.join(" ", cmd), e);
        }
    }

    /** docker CLI 直发（无输出需求：DISCARD 直落 NUL——②族管道纪律，无管道无死锁）。 */
    private static void dockerDiscard(String... args) {
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
