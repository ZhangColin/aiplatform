package com.semgravio.aiplatform.base.workspace.application;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.HostObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.DesiredState;
import com.semgravio.aiplatform.base.workspace.domain.model.EnvironmentUsage;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.repository.HostObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 占用观测采样用例（#487 写面，治清单超时；#489 追加主机帧）：容器实态与卷用量
 * 由扫描轮末尾旁路采样落库（{@code wsp_observation_samples}，带采样时刻），观测
 * 读面（{@link WorkspaceObservationAppService}）自此零 docker 调用；#489 起轮末
 * 追加<b>主机帧</b>（{@code wsp_host_observation} 全机一行：主机盘＋docker 四类
 * 用量＋无主计数），资源大盘（resources/overview）纯读库秒级返回。
 *
 * <p>节奏与旁路（票内定）：每轮全量、后台单线程、在途让路不排队——du 全卷是
 * 秒级重探查（旁路容器逐卷跑），数量级大的现场一轮可能超过扫描间隔，排队只会
 * 堆积；让路即「本轮不采，下一轮扫描再请求」，新鲜度由采样时刻如实标注。
 * 挂钩（{@link #requestRound()}）提交即返回：扫描轮主流程（休眠/封存收敛、
 * 无主收编时序）零等待零 docker 调用——采样纯旁路。</p>
 *
 * <p>口径：封存行不采卷（卷已删，起旁路容器白跑——读面按注册表意图容缺卷列）；
 * 首采落行、复采同行覆盖（每工作区一行；主机帧全机一行）；单行失败不炸整轮
 * （尽力而为，下轮再采）；注册表不可达整轮中止（宁漏勿误——同收编双闸口径，
 * 无从构成采样集绝不盲写；主机帧同门：无主计数以注册表求差）。主机帧 docker
 * 面不可达轮如实写 NULL（「零」与「缺测」在观测面上必须可分），主机盘列来自
 * JVM statfs 照采（无 docker 依赖）。写面刻意<b>不挂类级事务</b>：逐行独立落库
 * （一行失败不回滚整轮——尽力而为口径的落库面），且后台线程内部直调
 * {@code sampleRound} 事务注解本就不生效；采样与收敛/收编零锁交错：只读 docker
 * （inspect/du/system df 非 verbose），采样行随注册行 FK 级联消亡，销毁竞争下
 * 行已删则本行落库失败、下轮自然无此行。</p>
 */
@Service
@Slf4j
public class WorkspaceObservationSamplingAppService implements DisposableBean {

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceObservationSampleRepository sampleRepository;
    private final HostObservationSampleRepository hostRepository;

    /** 采样轮互斥（全局一轮）：在途让路不排队。 */
    private final AtomicBoolean roundInFlight = new AtomicBoolean(false);

    /** 采样执行器（生产 = 单线程 daemon；测试 = 直通/受控注入）。 */
    private final Executor sampleExecutor;

    /** 生产执行器生命周期（测试注入时为 null，destroy 不拥有）。 */
    private final ExecutorService ownedSampleExecutor;

    @Autowired
    public WorkspaceObservationSamplingAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            WorkspaceObservationSampleRepository sampleRepository,
            HostObservationSampleRepository hostRepository) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.sampleRepository = sampleRepository;
        this.hostRepository = hostRepository;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonThreadFactory());
        this.sampleExecutor = pool;
        this.ownedSampleExecutor = pool;
    }

    /** 测试构造：注入受控执行器（直通/队列）以验收 fire-and-forget 与在途让路。 */
    WorkspaceObservationSamplingAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            WorkspaceObservationSampleRepository sampleRepository,
            HostObservationSampleRepository hostRepository,
            Executor sampleExecutor) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.sampleRepository = sampleRepository;
        this.hostRepository = hostRepository;
        this.sampleExecutor = sampleExecutor;
        this.ownedSampleExecutor = null;
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "workspace-observation-sample-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void destroy() {
        if (ownedSampleExecutor != null) {
            ownedSampleExecutor.shutdownNow();
        }
    }

    /**
     * 采样轮请求（扫描轮末尾挂钩，#487）：提交即返回——采样在后台单线程旁路进行，
     * 不阻塞扫描轮主流程（休眠/封存收敛时序不变）。上一轮未落定（du 慢轮）则本
     * 轮让路不排队，下一轮扫描再请求。执行器拒收（关停期）只记日志——采样是观测
     * 面，不值得翻红任何调用方。
     */
    public void requestRound() {
        if (!roundInFlight.compareAndSet(false, true)) {
            return;   // 一轮仍在途：让路（不排队，防 du 慢轮堆积）
        }
        try {
            sampleExecutor.execute(() -> {
                try {
                    sampleRound();
                } finally {
                    roundInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            roundInFlight.set(false);   // 拒收即释放互斥位（否则一次拒绝永久哑掉采样）
            log.warn("[workspace] 占用采样轮提交未成（关停期，下轮扫描再试）", e);
        }
    }

    /**
     * 单轮采样（后台线程执行）：注册表全量 → 逐台探实态＋（未封存）du 卷 → 逐行
     * 覆盖落库 → 轮末主机帧（#489：全机一行覆盖——主机盘＋docker 用量＋无主计数）。
     * 包内可见：live 活体直驱（真 daemon 采样值与 du/df 实测一致性验收）。
     */
    void sampleRound() {
        List<Workspace> all;
        try {
            all = workspaceRepository.findAll();
        } catch (RuntimeException e) {
            log.warn("[workspace] 占用采样整轮中止：注册表不可达（宁漏勿误，下轮再试）", e);
            return;
        }
        Map<Long, WorkspaceObservationSample> existing = sampleRepository.findAllById(
                all.stream().map(Workspace::getId).toList())
                .stream().collect(Collectors.toMap(
                        WorkspaceObservationSample::getWorkspaceId, Function.identity()));
        int sampled = 0;
        for (Workspace workspace : all) {
            try {
                sampleOne(workspace, existing.get(workspace.getId()));
                sampled++;
            } catch (RuntimeException e) {
                log.warn("[workspace] {} 占用采样未成（下轮再试）",
                        workspace.workspaceId().value(), e);
            }
        }
        try {
            sampleHost(all);
        } catch (RuntimeException e) {
            log.warn("[workspace] 主机观测帧未成（下轮再采）", e);
        }
        if (sampled > 0) {
            log.info("[workspace] 占用采样轮收口：{}/{} 台已采样落库", sampled, all.size());
        }
    }

    /** 单台采样：实态四态一瞥＋未封存 du 全卷 → 首采落行/复采同行覆盖（同帧时刻）。 */
    private void sampleOne(Workspace workspace, WorkspaceObservationSample existing) {
        ContainerState state = environmentBackend.containerState(workspace.toHandle());
        Long volumeBytes = workspace.getDesiredState() == DesiredState.SEALED ? null
                : environmentBackend.volumeSizeBytes(workspace.toHandle());
        LocalDateTime sampledAt = LocalDateTime.now();
        WorkspaceObservationSample frame = existing != null
                ? existing.resample(state, volumeBytes, sampledAt)
                : WorkspaceObservationSample.record(
                        workspace.workspaceId(), state, volumeBytes, sampledAt);
        sampleRepository.save(frame);
    }

    /**
     * 主机帧（#489 资源面大盘采样）：全机一行覆盖——主机盘（JVM statfs，平台进程
     * 所在文件系统）＋docker 四类用量（system df 非 verbose）＋无主计数（docker
     * 工作区命名清单对注册表全量求差，与无主收编判定同一准绳）。docker 面不可达
     * 轮如实写 NULL（「零」与「缺测」必须可分）；主机盘列无 docker 依赖照采。
     * 尽力而为：本帧失败不炸采样轮（工作区行已落，主机帧下轮再采）。
     */
    private void sampleHost(List<Workspace> registered) {
        HostObservationSample frame = hostRepository
                .findById(HostObservationSample.SINGLETON_ID)
                .orElseGet(HostObservationSample::first);
        EnvironmentUsage usage = null;
        try {
            usage = environmentBackend.environmentUsage();
        } catch (RuntimeException e) {
            log.warn("[workspace] 主机观测 docker 面采样未成（docker 列如实缺测，下轮再采）", e);
        }
        Set<Long> registeredIds = registered.stream()
                .map(Workspace::getId).collect(Collectors.toSet());
        File anchor = new File(System.getProperty("user.dir"));
        LocalDateTime sampledAt = LocalDateTime.now();
        frame.resample(
                anchor.getAbsolutePath(),
                positiveOrNull(anchor.getTotalSpace()),
                positiveOrNull(anchor.getUsableSpace()),
                usage,
                usage != null ? orphanCount(usage.workspaceContainerIds(), registeredIds) : null,
                usage != null ? orphanCount(usage.workspaceVolumeIds(), registeredIds) : null,
                sampledAt);
        hostRepository.save(frame);
    }

    /** 无主计数＝docker 清单对注册表求差（注册表外的命名资源数）。 */
    private static long orphanCount(Set<Long> dockerIds, Set<Long> registeredIds) {
        return dockerIds.stream().filter(id -> !registeredIds.contains(id)).count();
    }

    /** statfs 回执 0/负＝测不出（容缺 NULL，不伪装成「零字节盘」）。 */
    private static Long positiveOrNull(long bytes) {
        return bytes > 0 ? bytes : null;
    }
}
