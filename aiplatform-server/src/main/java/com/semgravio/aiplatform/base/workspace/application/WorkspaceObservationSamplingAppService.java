package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.DesiredState;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceObservationSampleRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 占用观测采样用例（#487 写面，治清单超时）：容器实态与卷用量由扫描轮末尾旁路
 * 采样落库（{@code wsp_observation_samples}，带采样时刻），观测读面
 * （{@link WorkspaceObservationAppService}）自此零 docker 调用。
 *
 * <p>节奏与旁路（票内定）：每轮全量、后台单线程、在途让路不排队——du 全卷是
 * 秒级重探查（旁路容器逐卷跑），数量级大的现场一轮可能超过扫描间隔，排队只会
 * 堆积；让路即「本轮不采，下一轮扫描再请求」，新鲜度由采样时刻如实标注。
 * 挂钩（{@link #requestRound()}）提交即返回：扫描轮主流程（休眠/封存收敛、
 * 无主收编时序）零等待零 docker 调用——采样纯旁路。</p>
 *
 * <p>口径：封存行不采卷（卷已删，起旁路容器白跑——读面按注册表意图容缺卷列）；
 * 首采落行、复采同行覆盖（每工作区一行）；单行失败不炸整轮（尽力而为，下轮再
 * 采）；注册表不可达整轮中止（宁漏勿误——同收编双闸口径，无从构成采样集绝不
 * 盲写）。写面刻意<b>不挂类级事务</b>：逐行独立落库（一行失败不回滚整轮——
 * 尽力而为口径的落库面），且后台线程内部直调 {@code sampleRound} 事务注解本就
 * 不生效；采样与收敛/收编零锁交错：只读 docker（inspect/du），采样行随注册行
 * FK 级联消亡，销毁竞争下行已删则本行落库失败、下轮自然无此行。</p>
 */
@Service
@Slf4j
public class WorkspaceObservationSamplingAppService implements DisposableBean {

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceObservationSampleRepository sampleRepository;

    /** 采样轮互斥（全局一轮）：在途让路不排队。 */
    private final AtomicBoolean roundInFlight = new AtomicBoolean(false);

    /** 采样执行器（生产 = 单线程 daemon；测试 = 直通/受控注入）。 */
    private final Executor sampleExecutor;

    /** 生产执行器生命周期（测试注入时为 null，destroy 不拥有）。 */
    private final ExecutorService ownedSampleExecutor;

    @Autowired
    public WorkspaceObservationSamplingAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            WorkspaceObservationSampleRepository sampleRepository) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.sampleRepository = sampleRepository;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonThreadFactory());
        this.sampleExecutor = pool;
        this.ownedSampleExecutor = pool;
    }

    /** 测试构造：注入受控执行器（直通/队列）以验收 fire-and-forget 与在途让路。 */
    WorkspaceObservationSamplingAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            WorkspaceObservationSampleRepository sampleRepository,
            Executor sampleExecutor) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.sampleRepository = sampleRepository;
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
     * 覆盖落库。包内可见：live 活体直驱（真 daemon 采样值与 du 实测一致性验收）。
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
}
