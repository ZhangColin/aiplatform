package com.aieducenter.aiplatform.base.workspace.application;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.event.ApplicationEventPublisher;

import com.aieducenter.aiplatform.base.workspace.application.event.PreviewReady;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 向活收敛模块（#196，CONTEXT.md「收敛」词条）：向活四面（触碰/扫描/下载/后台
 * 动作）共用同一判定与执行内核——判定梯子（置备在途？封存态走深度唤醒？实态四态？
 * UNKNOWN 规则？）、唤醒内核（幂等重建）、进程内互斥、拨针口径全部内化在此；
 * 失败语义、等待策略、拨针口径是「面」的关联属性（{@link ConvergenceFace}），
 * 新入口只声明一个面即全套继承。
 *
 * <p>判定矩阵（期望态×实态×面，经 {@code WorkspaceConvergenceAppServiceTest}
 * 编排缝锁全）：</p>
 * <ul>
 *   <li>置备在途（忙）：TOUCH 入口拨针后让路；SCAN 让路；DOWNLOAD/ADMIN 等就绪
 *       （他人收敛中，{@link WorkspaceReadinessWaiter} 接管）——落定即收敛成果，
 *       ADMIN 拨针、DOWNLOAD 不拨。</li>
 *   <li>实态 RUNNING：TOUCH/SCAN/DOWNLOAD 无事；ADMIN 按需幂等拉应用，期望休眠
 *       残留则对齐意图翻运行＋拨针（对齐是收敛动作），意图/实态一致（本就健康）
 *       不拨。</li>
 *   <li>实态 ABSENT/STOPPED（有把握的不在，重建权只及于此）：四面都走唤醒内核
 *       重建；仅 ADMIN 在收敛落定后拨针。</li>
 *   <li>实态 UNKNOWN（#176 探查失败≠容器不在，盲重建的预清 rm -f 会杀可能健康
 *       容器上的在途 run）：TOUCH/SCAN 让路下轮；DOWNLOAD 跳过（容器若在打包
 *       自成，真死由打包如实失败）；ADMIN 如实抛 WSP_002（可重试）。</li>
 *   <li>期望封存：TOUCH/SCAN 探查有把握的不在才深度唤醒（解包回卷＋重建，分钟级）；
 *       ADMIN 不经探查直走深度唤醒；DOWNLOAD 不动（封存包直取归调用方
 *       {@code WorkspaceLifecycleAppService#contentPackageOf} 拦截在前）。深度
 *       唤醒未成（包不可读，内核保持封存态）：不拨针，ADMIN 如实 WSP_016。</li>
 * </ul>
 *
 * <p>「忙则让路→等就绪」四面统一：互斥在途/置备在途 = 忙——异步面让路不排队
 * （下次触碰/下轮扫描再试），同步面让路后等就绪。拨针口径：TOUCH 入口无条件拨
 * （用户来过即活跃，让路也不丢）；ADMIN 除「探查发现本就健康」外，收敛成功
 * 落定即拨（对齐/重建/深度唤醒/等他人收敛落定——补上「醒完秒睡」缺口：深度
 * 唤醒分钟级成本后闲置钟仍走旧值，下轮扫描立刻又被睡掉）；SCAN/DOWNLOAD 不拨
 * （平台内部收敛与「取完包该继续睡」不是活跃信号）。</p>
 *
 * <p>唤醒内核 {@link #wakeUp} 与互斥执行对包内可见（不对外——新增重活路径无法
 * 绕开独占执行）：后台 {@code WorkspaceActionAppService#forceRebuild} 留在界外
 * （rm 先行＋守卫链不是探查梯子）继续直驱内核；向死收敛（封存/卷残留，归
 * {@code WorkspaceHibernationAppService}）经互斥面提交。互斥是进程内语义；跨进程
 * 由 PROVISIONING 态与幂等重建兜底。</p>
 */
@Service
@Slf4j
public class WorkspaceConvergenceAppService implements DisposableBean {

    /** 收敛任务并发（单机小池：触碰探查秒级、重建分钟级，2 路并行够用）。 */
    private static final int CONVERGE_THREADS = 2;

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkspaceProvisionAppService provisioner;
    private final WorkspaceReadinessWaiter readinessWaiter;
    private final SealPackageStore sealPackageStore;

    /**
     * 重活互斥登记（#170 起，#172 扩为封存共用面）：同一工作区同时至多一个在途
     * 任务（实态探查/幂等重建/应用拉起/封存打包共用一面）——并发触碰只触发一次、
     * 封存与唤醒互斥。
     */
    private final Set<WorkspaceId> inFlight = ConcurrentHashMap.newKeySet();

    /** 收敛任务执行器（生产 = 固定 daemon 小池；测试 = 直通/受控注入）。 */
    private final Executor convergeExecutor;

    /** 生产执行器生命周期（测试注入时为 null，destroy 不拥有）。 */
    private final ExecutorService ownedConvergeExecutor;

    @Autowired
    public WorkspaceConvergenceAppService(EnvironmentBackend environmentBackend,
                                          WorkspaceRepository workspaceRepository,
                                          TransactionTemplate transactionTemplate,
                                          ApplicationEventPublisher eventPublisher,
                                          WorkspaceProvisionAppService provisioner,
                                          WorkspaceReadinessWaiter readinessWaiter,
                                          SealPackageStore sealPackageStore) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.provisioner = provisioner;
        this.readinessWaiter = readinessWaiter;
        this.sealPackageStore = sealPackageStore;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(CONVERGE_THREADS, CONVERGE_THREADS,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                daemonThreadFactory("workspace-converge"));
        this.convergeExecutor = pool;
        this.ownedConvergeExecutor = pool;
    }

    /** 测试构造：注入受控执行器（直通/队列）以验收互斥与异步编排。 */
    WorkspaceConvergenceAppService(EnvironmentBackend environmentBackend,
                                   WorkspaceRepository workspaceRepository,
                                   TransactionTemplate transactionTemplate,
                                   ApplicationEventPublisher eventPublisher,
                                   WorkspaceProvisionAppService provisioner,
                                   WorkspaceReadinessWaiter readinessWaiter,
                                   SealPackageStore sealPackageStore,
                                   Executor convergeExecutor) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.provisioner = provisioner;
        this.readinessWaiter = readinessWaiter;
        this.sealPackageStore = sealPackageStore;
        this.convergeExecutor = convergeExecutor;
        this.ownedConvergeExecutor = null;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void destroy() {
        if (ownedConvergeExecutor != null) {
            ownedConvergeExecutor.shutdownNow();
        }
    }

    // ---------- 公开接口（四面入口 + 应用拉起轻路径） ----------

    /**
     * 异步面收敛（TOUCH/SCAN）：提交互斥收敛任务即返回（请求线程零 docker 调用），
     * 在途让路不排队。TOUCH 入口先无条件拨 last-touch（闲置计时输入，用户来过即
     * 活跃——让路也不丢；休眠意图顺带拨回运行，封存态不翻）再探查；SCAN 不拨
     * （平台内部收敛不是活跃信号）。
     */
    public void convergeAsync(WorkspaceId id, ConvergenceFace face, boolean startAppOnWake) {
        switch (face) {
            case TOUCH -> {
                Workspace workspace = requirePresent(id);
                workspace.markTouched(LocalDateTime.now());
                transactionTemplate.executeWithoutResult(status ->
                        workspaceRepository.save(workspace));
                if (workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
                    return;   // 忙（首次置备/唤醒已在途）：让路，不重复提交探查
                }
                runExclusively(id, () -> convergeIfNeeded(id, startAppOnWake));
            }
            case SCAN -> runExclusively(id, () -> convergeIfNeeded(id, startAppOnWake));
            case DOWNLOAD, ADMIN -> throw new IllegalArgumentException(
                    face + " 面走 convergeBlocking（面×式是固定配对，同步面要结果）");
        }
    }

    /**
     * 同步面收敛（DOWNLOAD/ADMIN）：当前线程执行，忙则让路→等就绪。DOWNLOAD
     * 不拨针不拉应用（取完包该继续睡）；ADMIN 按需拉应用、仅收敛动作落定后拨针，
     * UNKNOWN 抛 WSP_002、深度唤醒未成抛 WSP_016（不伪成功）。
     */
    public void convergeBlocking(WorkspaceId id, ConvergenceFace face, boolean startAppOnWake) {
        switch (face) {
            case DOWNLOAD, ADMIN -> {
                // 合法配对，下方走梯子
            }
            case TOUCH, SCAN -> throw new IllegalArgumentException(
                    face + " 面走 convergeAsync（面×式是固定配对，异步面不等结果）");
        }
        Workspace workspace = requirePresent(id);
        if (workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
            // 忙（首次置备/他人收敛在途）：不排队，等就绪接管；落定即收敛成果——
            // ADMIN 拨针（醒完不秒睡），DOWNLOAD 不拨
            Workspace woken = readinessWaiter.awaitReady(workspace);
            if (woken.getDesiredState() == DesiredState.SEALED && face == ConvergenceFace.ADMIN) {
                // 等待间隙被并发封存（防御——封存入口均拒置备在途，正常不至）：
                // 未收敛成运行态，如实回错不拨针（与深度唤醒未成同口径）
                throw new ApplicationException(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE);
            }
            pinIfAdmin(face, woken);
            return;
        }
        if (workspace.getDesiredState() == DesiredState.SEALED) {
            if (face == ConvergenceFace.ADMIN) {
                // 封存态走深度唤醒（探查无意义——卷已删容器必不在）
                wakeAndPin(workspace, face, startAppOnWake);
            }
            // DOWNLOAD 不动封存态：封存包直取归调用方拦截在前，此行纯防御
            return;
        }
        // 判定穷举四态（switch 无 default——枚举加值此点编译期即炸，判定不静默漏分支）
        switch (environmentBackend.containerState(workspace.toHandle())) {
            case RUNNING -> {
                if (face == ConvergenceFace.ADMIN) {
                    healthyWake(workspace, startAppOnWake);
                }
                // DOWNLOAD：实态健康无事，调用方径直打包
            }
            case STOPPED, ABSENT -> wakeAndPin(workspace, face, startAppOnWake);
            case UNKNOWN -> {
                if (face == ConvergenceFace.ADMIN) {
                    throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
                }
                // DOWNLOAD 跳过：不盲重建，容器若在打包自成，真死由打包如实失败
            }
        }
    }

    /**
     * 应用拉起请求（#170 预览面收敛轻路径）：容器在而应用进程死的轻路径——预览探活失败
     * （WSP_012）时由项目预览编排对已生成项目调用。互斥异步拉起，成活以探活 +
     * PreviewReady 收口。与收敛任务共用互斥面。
     */
    public void requestAppStart(WorkspaceId id) {
        Workspace workspace = requirePresent(id);
        if (workspace.getStatus() != ProvisioningStatus.READY) {
            return;
        }
        runExclusively(id, () -> {
            try {
                environmentBackend.startApp(workspace.toHandle());
                probePreview(workspace);
            } catch (RuntimeException e) {
                log.warn("[workspace] {} 应用拉起未成（下次触碰/探活再试）", id.value(), e);
            }
        });
    }

    // ---------- 判定与执行内核（模块私有；内核与互斥面包内可见） ----------

    /** 异步面收敛任务（TOUCH/SCAN 共用）：实态探查 → 有把握的不在才唤醒重建（唤醒含应用拉起与预览事件收口）。 */
    private void convergeIfNeeded(WorkspaceId id, boolean startAppOnWake) {
        try {
            Workspace workspace = workspaceRepository.findById(id.id()).orElse(null);
            if (workspace == null || workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
                return;   // 等待间隙已删除/已在途（并发收敛中）：让路
            }
            ContainerState state = environmentBackend.containerState(workspace.toHandle());
            if (state == ContainerState.RUNNING) {
                return;   // 实态健康（意图/实态一致），无事可做
            }
            if (!state.confidentlyNotRunning()) {
                return;   // UNKNOWN（#176）：探查失败≠容器不在，不盲重建，下次触碰/下轮扫描收敛
            }
            wakeUp(workspace, startAppOnWake);
        } catch (RuntimeException e) {
            log.warn("[workspace] {} 异步收敛未成（尽力而为，下次触碰/下轮扫描再试）", id.value(), e);
        }
    }

    /**
     * 重建/深度唤醒分支（同步面共用收尾）：互斥在途则让路（他人任务收敛中——
     * 不排队），随后等就绪接管。收敛落定后按面拨针：ADMIN 醒完即活跃（重建/
     * 深度唤醒分钟级成本后闲置钟不应仍走旧值）；DOWNLOAD 不拨。深度唤醒未成
     * （封存包不可读，内核保持封存态待人工）：不拨针，ADMIN 如实 WSP_016。
     */
    private void wakeAndPin(Workspace workspace, ConvergenceFace face, boolean startAppOnWake) {
        WorkspaceId id = workspace.workspaceId();
        runExclusivelyBlocking(id, () -> wakeUp(workspace, startAppOnWake));
        Workspace woken = readinessWaiter.awaitReady(requirePresent(id));
        if (woken.getDesiredState() == DesiredState.SEALED) {
            // 深度唤醒未成（内核保持封存态）：不拨针；ADMIN 如实回错（DOWNLOAD
            // 不至此——封存拦截在调用方，防御让路后打包阶段如实失败）
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE);
        }
        pinIfAdmin(face, woken);
    }

    /** 拨针只属 ADMIN 面（DOWNLOAD 不拨）：收敛落定即活跃，醒完不秒睡。 */
    private void pinIfAdmin(ConvergenceFace face, Workspace woken) {
        if (face != ConvergenceFace.ADMIN) {
            return;
        }
        woken.markTouched(LocalDateTime.now());
        workspaceRepository.save(woken);
    }

    /**
     * 健康路径（实态在跑，ADMIN 面）：按需幂等拉应用；期望休眠而实态在跑（删失败
     * 残留/外部重建的漂移形）对齐意图翻运行——否则扫描器按休眠意图再删容器，唤醒
     * 被静默撤销。对齐是收敛动作：显式唤醒即活跃，拨针；意图/实态一致（本就健康）
     * 不动作不拨针。
     */
    private void healthyWake(Workspace workspace, boolean startAppOnWake) {
        if (startAppOnWake) {
            environmentBackend.startApp(workspace.toHandle());   // 幂等：已在服直回
        }
        if (workspace.getDesiredState() == DesiredState.HIBERNATED) {
            workspace.markTouched(LocalDateTime.now());
            workspaceRepository.save(workspace);
        }
    }

    /**
     * 唤醒内核（ADR-0016 醒 = 既有幂等重建路径）：rewake 落 PROVISIONING → 同步
     * 重置备（置备器同款重试上限，全败落 FAILED、可再触发）→ 已生成项目拉起
     * 8081 应用 → 探活发 PreviewReady。未生成工作区探活必败（WSP_012 预期口径），
     * 吞掉。
     *
     * <p>封存态走深度唤醒（#172）：先解包回卷（物理先行——失败则意图不动，保持
     * 封存态下次再试），后续与普通唤醒同一重建路径；应用拉起连带依赖重装
     * （解包排除了 node_modules），分钟级。</p>
     *
     * <p>包内可见（#196）：{@code WorkspaceActionAppService#forceRebuild} 留在界外
     * （rm 先行＋守卫链不是探查梯子），在同一进程内直接驱动本内核，不复制编排。</p>
     */
    void wakeUp(Workspace workspace, boolean startAppOnWake) {
        WorkspaceId id = workspace.workspaceId();
        if (workspace.getDesiredState() == DesiredState.SEALED) {
            if (!restoreSealedVolume(workspace)) {
                return;   // 包不可读：保持封存态（数据完整性优先，不以空卷顶替），待人工介入
            }
            log.info("[workspace] {} 封存态收敛，深度唤醒：解包回卷 + 幂等重建（分钟级）",
                    id.value());
        } else {
            log.info("[workspace] {} 容器缺失/被杀，唤醒：幂等重建（卷保留，数据不动）",
                    id.value());
        }
        Workspace rewoken = transactionTemplate.execute(status -> {
            // 事务内重取防复活：销毁竞争下记录已删则不迁移（孤儿资源归销毁级联+置备取消协调）
            Workspace fresh = workspaceRepository.findById(id.id()).orElse(null);
            return fresh == null ? null : workspaceRepository.save(fresh.rewake());
        });
        if (rewoken == null) {
            return;
        }
        provisioner.provisionForWake(id, rewoken.getKind());
        Workspace woken = workspaceRepository.findById(id.id()).orElse(null);
        if (woken == null || woken.getStatus() != ProvisioningStatus.READY) {
            return;   // 重试上限落 FAILED（可再触发）或记录已删
        }
        if (startAppOnWake) {
            environmentBackend.startApp(woken.toHandle());
        }
        try {
            probePreview(woken);
        } catch (RuntimeException e) {
            // 未生成工作区预期未起服（WSP_012）；已生成的应用问题由预览面收敛续试
            log.debug("[workspace] {} 唤醒后预览探活未过：{}", id.value(), e.getMessage());
        }
    }

    /**
     * 封存包解包回卷（#172 深度唤醒前半，物理先行）：重建卷并解包——成功后随后的
     * 幂等重建（createWorkspace）对既有卷幂等收敛，数据完整恢复。返回 false = 无可用包
     * （无路径 = 外部漂移的空包收敛形态，按空卷重建；包不可读 = 保持封存态待人工
     * 介入——「系统与数据完整恢复」是契约，静默换空卷等于掩埋数据丢失）；解包失败
     * 上抛（异步面吞掉记日志，意图不动下次再试）。
     */
    private boolean restoreSealedVolume(Workspace workspace) {
        String archivePath = workspace.getArchivePath();
        if (archivePath == null) {
            log.warn("[workspace] {} 封存态无封存包记录（卷已失的外部漂移），按空卷重建",
                    workspace.workspaceId().value());
            return true;
        }
        byte[] archive;
        try {
            archive = sealPackageStore.open(archivePath);
        } catch (RuntimeException e) {
            log.error("[workspace] {} 封存包不可读（{}），保持封存态待人工介入",
                    workspace.workspaceId().value(), archivePath, e);
            return false;
        }
        environmentBackend.restoreVolume(workspace.toHandle(), archive);
        return true;
    }

    /**
     * 唤醒后预览探活收口（模块内政——调用点 READY 已验，无公开 exposePreview 的
     * 待期/失败守卫分支）：探活 + PreviewReady（AFTER_COMMIT，前端 SSE 刷新锚）。
     * 发布走短事务——订阅方的事务性监听依赖一个真实提交的事务，这里预览无落库、
     * 事务体只含发布。
     */
    private void probePreview(Workspace workspace) {
        URI url = environmentBackend.exposePort(workspace.toHandle(),
                EnvironmentBackend.DEV_APP_CONTAINER_PORT);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishApplicationEvent(
                PreviewReady.of(workspace.workspaceId(), url)));
    }

    /**
     * 独占提交（异步式）：同一工作区同时至多一个在途重活（实态探查/幂等重建/
     * 应用拉起/封存打包/深度唤醒解包）。在途则让路不排队（调用方下轮扫描/下次
     * 触碰再试），finally 释放互斥——任务体自身异常语义归任务（收敛任务吞、封存记
     * 日志）。包内可见（#196 收窄，不对外）：向死收敛任务（同包
     * {@code WorkspaceHibernationAppService}）经此提交。
     */
    void runExclusively(WorkspaceId id, Runnable task) {
        if (inFlight.add(id)) {
            convergeExecutor.execute(() -> {
                try {
                    task.run();
                } finally {
                    inFlight.remove(id);
                }
            });
        }
    }

    /**
     * 独占执行（同步式）：同一张互斥面，但任务在<b>当前线程</b>内执行（后台动作/
     * 下载要同步等结果）。在途返回 false（不排队——调用方让路后等就绪/如实回忙），
     * 获锁执行返回 true，finally 释放——任务体异常原样上抛（语义归调用方）。
     */
    boolean runExclusivelyBlocking(WorkspaceId id, Runnable task) {
        if (!inFlight.add(id)) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            inFlight.remove(id);
        }
    }

    private Workspace requirePresent(WorkspaceId id) {
        return workspaceRepository.findById(id.id())
                .orElseThrow(() -> new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));
    }
}
