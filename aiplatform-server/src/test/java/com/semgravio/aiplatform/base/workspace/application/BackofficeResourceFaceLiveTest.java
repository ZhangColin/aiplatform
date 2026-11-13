package com.semgravio.aiplatform.base.workspace.application;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.workspace.application.dto.response.BackofficeResourceOverviewResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.BackofficeResourceSweepResponse;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.HostObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceProvision;
import com.semgravio.aiplatform.base.workspace.domain.repository.HostObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceSweepActionRepository;
import com.semgravio.aiplatform.base.workspace.infrastructure.docker.DockerEnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源面活体验收（#489 AC，真 docker daemon＋真库——非假面）：
 * <ul>
 * <li><b>overview 口径对账</b>：采样轮落库的大盘读回，与独立发起的 docker
 * system df（四类计数）／docker ps·volume ls（无主计数＝清单对注册表求差的独立
 * 复算）／statfs（主机盘）对得上；读面零 docker（编排缝钉死）、新鲜度随行；</li>
 * <li><b>sweep 逐项报告</b>：自造无主现场（在跑容器＋卷）先被宽限窗拦（SKIPPED/
 * GRACE_WINDOW、物理原封），出窗后被收编（SWEPT、物理点名删）；在册锚（休眠
 * 保卷，ADR-0016 用户数据）永不进刀（SKIPPED/REGISTERED）；留痕行逐次落库。</li>
 * </ul>
 *
 * <p>测试卫生（硬约束）：手动清扫的保留集＝注册表全量——本机既有 ws/vol-ws 历史
 * 现场不是本票现场，<b>全部经注册表保护行入保留集</b>（绝不顺手清）；只造/只清
 * 本用例自生成编号的现场，收尾全部自清（含主机观测帧与留痕行——不淹其他缝）。
 * 测试 profile 休眠总开关关闭（扫描轮静默），本类直驱采样轮与清扫面（与生产
 * 同一执行体）。daemon 不在本机时跳过。</p>
 */
@IntegrationTest
@Tag("live")
class BackofficeResourceFaceLiveTest {

    /** 无主现场容器镜像：与 DockerEnvironmentBackend.DEV_IMAGE 同源（本地必有，不另拉）。 */
    private static final String DEV_IMAGE = "aiplatform/dev:0.17";

    private static final Pattern MAIN_CONTAINER_NAME = Pattern.compile("^ws-(\\d+)$");
    private static final Pattern WORKSPACE_VOLUME_NAME = Pattern.compile("^vol-ws-(\\d+)$");
    private static final Pattern WS_NAMED_ANY = Pattern.compile("^ws-(\\d+).*$");

    /** 现场锚（自造）：在册休眠锚＋无主容器/卷。 */
    private WorkspaceId registeredId;
    private WorkspaceId orphanId;

    /** 注册表既有行快照（护本机现场：只删本用例落的行）。 */
    private Set<Long> preExistingWorkspaceIds;

    /** 本用例落的保护行（历史现场经注册表入保留集——清扫面准绳）。 */
    private final Set<Long> protectiveRowIds = new HashSet<>();

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private HostObservationSampleRepository hostRepository;

    @Autowired
    private WorkspaceSweepActionRepository sweepActionRepository;

    @Autowired
    private WorkspaceObservationSamplingAppService samplingService;

    @Autowired
    private WorkspaceResourceAppService resourceService;

    @Autowired
    private DockerEnvironmentBackend backend;

    @AfterEach
    void cleanup() {
        if (orphanId != null) {
            dockerDiscard("rm", "-f", "ws-" + orphanId.value());
            dockerDiscard("volume", "rm", "vol-ws-" + orphanId.value());
        }
        if (registeredId != null) {
            dockerDiscard("volume", "rm", "vol-ws-" + registeredId.value());
        }
        // 本用例落的行全清（既有行快照护住他票/现场）：保护行＋锚行＋留痕＋主机帧
        for (Long id : protectiveRowIds) {
            workspaceRepository.findById(id).ifPresent(workspaceRepository::delete);
        }
        if (registeredId != null) {
            workspaceRepository.findById(registeredId.id()).ifPresent(workspaceRepository::delete);
        }
        sweepActionRepository.deleteAll();
        hostRepository.findById(HostObservationSample.SINGLETON_ID)
                .ifPresent(hostRepository::delete);
    }

    // ---------- overview：口径与 df / docker system df / statfs 对账 ----------

    @Test
    void given_real_daemon_when_sample_round_then_overview_matches_df_and_system_df() {
        requireDockerDaemon();

        // 采样轮直驱（与扫描轮后台线程同一执行体）：主机帧落库
        samplingService.sampleRound();

        BackofficeResourceOverviewResponse overview = resourceService.overview();

        // ① 主机盘面：与 statfs 独立实测对账（total 恒定精确等、usable 容许在途漂移）
        File anchor = new File(System.getProperty("user.dir"));
        assertThat(overview.host()).as("主机盘面在场（采样轮已落库）").isNotNull();
        assertThat(overview.host().path()).isEqualTo(anchor.getAbsolutePath());
        assertThat(overview.host().totalBytes()).isEqualTo(anchor.getTotalSpace());
        assertThat(overview.host().usableBytes()).isPositive();
        assertThat(overview.host().usedBytes())
                .isEqualTo(overview.host().totalBytes() - overview.host().usableBytes());
        assertThat(overview.sampledAt()).isAfter(LocalDateTime.now().minusMinutes(5));

        // ② docker 四类用量：与独立发起的 docker system df 对账（计数精确等、
        // 体量为人类可读串换算的量级一致——同基十进制）
        SystemDf independentDf = independentSystemDf();
        assertThat(overview.docker()).as("docker 用量面在场").isNotNull();
        assertThat(overview.docker().imageCount()).isEqualTo(independentDf.imageCount);
        assertThat(overview.docker().containerCount()).isEqualTo(independentDf.containerCount);
        assertThat(overview.docker().volumeCount()).isEqualTo(independentDf.volumeCount);
        if (independentDf.imageSizePositive) {
            assertThat(overview.docker().imageSizeBytes()).isPositive();
        }
        if (independentDf.buildCacheSizePositive) {
            assertThat(overview.docker().buildCacheSizeBytes()).isPositive();
        }

        // ③ 无主计数：与「独立 docker 清单 − 注册表」的复算对账（历史现场如实
        // 计入无主——计数面不动刀，红线只在判定/清扫）
        Set<Long> registeredIds = workspaceRepository.findAll().stream()
                .map(Workspace::getId).collect(Collectors.toSet());
        long expectedOrphanContainers = listedWorkspaceIds(
                new String[] {"docker", "ps", "--format", "{{.Names}}"},
                MAIN_CONTAINER_NAME).stream().filter(id -> !registeredIds.contains(id)).count();
        long expectedOrphanVolumes = listedWorkspaceIds(
                new String[] {"docker", "volume", "ls", "--format", "{{.Name}}"},
                WORKSPACE_VOLUME_NAME).stream().filter(id -> !registeredIds.contains(id)).count();
        assertThat(overview.orphans()).isNotNull();
        assertThat(overview.orphans().containerCount())
                .as("无主容器计数与独立复算一致").isEqualTo(expectedOrphanContainers);
        assertThat(overview.orphans().volumeCount())
                .as("无主卷计数与独立复算一致").isEqualTo(expectedOrphanVolumes);
    }

    // ---------- sweep：宽限窗拦 → 出窗收编；在册锚永不进刀；留痕逐次落库 ----------

    @Test
    void given_self_made_orphans_when_manual_sweep_then_grace_first_then_swept_registered_kept() {
        requireDockerDaemon();
        preExistingWorkspaceIds = workspaceRepository.findAll().stream()
                .map(Workspace::getId).collect(Collectors.toSet());

        // 现场铺场：①历史现场全量经注册表保护行入保留集（手动清扫准绳＝注册表，
        // 本机既有 ws/vol-ws 绝不进刀）；②在册休眠锚（此刻尚无卷——锚卷存活只
        // 归因注册表准绳）＋锚卷；③无主现场（在跑容器＋卷）
        protectExistingScene();
        registeredId = registerHibernatedAnchor();
        dockerDiscard("volume", "create", "vol-ws-" + registeredId.value());
        orphanId = WorkspaceId.generate();
        dockerDiscard("run", "-d", "--name", "ws-" + orphanId.value(),
                "--entrypoint", "sleep", DEV_IMAGE, "infinity");
        dockerDiscard("volume", "create", "vol-ws-" + orphanId.value());
        Operator operator = new Operator("990001", "运营管理员");

        // 第一向（AC：宽限窗防误杀＋跳过原因）：真实宽限线（触发时刻减 10 分钟）
        // ——刚造的无主现场都在窗内：SKIPPED/GRACE_WINDOW、物理原封
        BackofficeResourceSweepResponse inWindow =
                resourceService.sweep(operator, LocalDateTime.now());
        assertThat(itemOf(inWindow, "ws-" + orphanId.value()).disposition())
                .isEqualTo("SKIPPED");
        assertThat(itemOf(inWindow, "ws-" + orphanId.value()).skipReason())
                .isEqualTo("GRACE_WINDOW");
        assertThat(itemOf(inWindow, "vol-ws-" + orphanId.value()).skipReason())
                .isEqualTo("GRACE_WINDOW");
        assertThat(inWindow.sweptContainers()).isZero();
        assertThat(inWindow.sweptVolumes()).isZero();
        assertThat(backend.containerState(handle(orphanId)))
                .as("宽限窗内的无主容器不被误杀").isEqualTo(ContainerState.RUNNING);
        assertThat(backend.volumeExists(handle(orphanId)))
                .as("宽限窗内的无主卷不被误杀").isTrue();

        // 第二向（AC：真无主收编＋逐项报告）：触发时刻推后 11 分钟＝宽限线越今
        // ——无主现场出窗被点名收编（SWEPT、物理删）；在册锚卷永不进刀（在册）
        BackofficeResourceSweepResponse outWindow =
                resourceService.sweep(operator, LocalDateTime.now().plusMinutes(11));
        assertThat(itemOf(outWindow, "ws-" + orphanId.value()).disposition()).isEqualTo("SWEPT");
        assertThat(itemOf(outWindow, "vol-ws-" + orphanId.value()).disposition()).isEqualTo("SWEPT");
        assertThat(itemOf(outWindow, "vol-ws-" + registeredId.value()).disposition())
                .isEqualTo("SKIPPED");
        assertThat(itemOf(outWindow, "vol-ws-" + registeredId.value()).skipReason())
                .isEqualTo("REGISTERED");
        assertThat(backend.containerState(handle(orphanId)))
                .as("出窗的真无主容器被收编").isEqualTo(ContainerState.ABSENT);
        assertThat(backend.volumeExists(handle(orphanId)))
                .as("出窗的真无主卷被收编").isFalse();
        assertThat(backend.volumeExists(handle(registeredId)))
                .as("在册休眠卷永不被触碰（ADR-0016）").isTrue();

        // 留痕（append-only）：两次触发两行、操作者落库、报告 JSON 全量留档
        assertThat(sweepActionRepository.findAll()).hasSize(2);
        sweepActionRepository.findAll().forEach(action -> {
            assertThat(action.getOperatorId()).isEqualTo("990001");
            assertThat(action.isRegistryReachable()).isTrue();
            assertThat(action.getItemsJson()).isNotBlank();
        });

        // 大盘收编结果面：最近一次清扫直读（第二向——sweptContainers ≥ 1）
        BackofficeResourceOverviewResponse overview = resourceService.overview();
        assertThat(overview.lastSweep()).isNotNull();
        assertThat(overview.lastSweep().operatorName()).isEqualTo("运营管理员");
        assertThat(overview.lastSweep().sweptContainers()).isGreaterThanOrEqualTo(1);
    }

    // ---------- 活体编排 ----------

    /** 历史现场全量入注册表保护行（手动清扫保留集＝注册表全量——本机既有 ws/vol-ws 绝不进刀）。 */
    private void protectExistingScene() {
        Set<Long> existing = new HashSet<>();
        existing.addAll(listedWorkspaceIds(
                new String[] {"docker", "ps", "-a", "--format", "{{.Names}}"}, WS_NAMED_ANY));
        existing.addAll(listedWorkspaceIds(
                new String[] {"docker", "volume", "ls", "--format", "{{.Name}}"},
                WORKSPACE_VOLUME_NAME));
        existing.removeAll(preExistingWorkspaceIds);
        for (Long id : existing) {
            Workspace pending = Workspace.registerPending(
                    WorkspaceId.of(Long.toString(id)), EnvKind.DEV);
            pending.complete(WorkspaceProvision.of(handle(WorkspaceId.of(Long.toString(id)))));
            workspaceRepository.save(pending);
            protectiveRowIds.add(id);
        }
    }

    /** 在册休眠锚：行经状态机落库（READY→休眠意图），无物理容器——休眠保卷形态。 */
    private WorkspaceId registerHibernatedAnchor() {
        WorkspaceId id = WorkspaceId.generate();
        Workspace pending = Workspace.registerPending(id, EnvKind.DEV);
        pending.complete(WorkspaceProvision.of(handle(id)));
        pending.hibernate();
        workspaceRepository.save(pending);
        return id;
    }

    /** 回执里按资源名取逐项处置行（缺行即 fail——每项必有处置结论）。 */
    private static BackofficeResourceSweepResponse.SweepItem itemOf(
            BackofficeResourceSweepResponse response, String identifier) {
        return response.items().stream()
                .filter(item -> item.identifier().equals(identifier))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "清扫报告缺行：" + identifier + " — " + response.items()));
    }

    /** 独立 system df 计数（真值锚：本用例独立进程发起，非同值复算）。 */
    private static SystemDf independentSystemDf() {
        String out = dockerCapture(new String[] {"docker", "system", "df", "--format", "{{json .}}"});
        SystemDf df = new SystemDf();
        for (String line : out.lines().map(String::trim).filter(l -> !l.isEmpty()).toList()) {
            if (line.contains("\"Images\"")) {
                df.imageCount = longField(line, "TotalCount");
                df.imageSizePositive = !sizeFieldIsZero(line);
            } else if (line.contains("\"Containers\"")) {
                df.containerCount = longField(line, "TotalCount");
            } else if (line.contains("\"Local Volumes\"")) {
                df.volumeCount = longField(line, "TotalCount");
            } else if (line.contains("\"Build Cache\"")) {
                df.buildCacheSizePositive = !sizeFieldIsZero(line);
            }
        }
        return df;
    }

    /** system df 行的最小解析（独立于生产解析器——只取计数与「体量非零」布尔）。 */
    private static long longField(String jsonLine, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"?(\\d+)").matcher(jsonLine);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static boolean sizeFieldIsZero(String jsonLine) {
        Matcher matcher = Pattern.compile("\"Size\":\"([^\"]*)\"").matcher(jsonLine);
        return matcher.find() && matcher.group(1).matches("0B|0(\\.0+)?\\s*[kMGTPE]?B?");
    }

    /** system df 独立回执的最小投影。 */
    private static class SystemDf {
        long imageCount;
        long containerCount;
        long volumeCount;
        boolean imageSizePositive;
        boolean buildCacheSizePositive;
    }

    /** 按命名提取既有现场的工作区编号（②族纪律：先读尽 stdout 再 waitFor）。 */
    private static Set<Long> listedWorkspaceIds(String[] fullCommand, Pattern pattern) {
        String out = dockerCapture(fullCommand);
        Set<Long> ids = new HashSet<>();
        for (String line : out.lines().map(String::trim).toList()) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                ids.add(Long.parseLong(matcher.group(1)));
            }
        }
        return ids;
    }

    private static WorkspaceHandle handle(WorkspaceId id) {
        return WorkspaceHandle.dev(id, "ws-" + id.value(), "previewnet");
    }

    /** docker CLI 取 stdout（全命令形；②族纪律：读尽输出再 waitFor，非 0 即抛）。 */
    private static String dockerCapture(String[] fullCommand) {
        try {
            Process p = new ProcessBuilder(fullCommand).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException(
                        "docker 命令失败(" + code + "): " + String.join(" ", fullCommand)
                                + " — " + err.trim());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 命令被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("docker 命令未成: " + String.join(" ", fullCommand), e);
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
