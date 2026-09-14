package com.aieducenter.aiplatform.base.workspace.application;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
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

import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ProvisionFailedWorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.application.event.PreviewReady;
import com.aieducenter.aiplatform.base.workspace.application.event.WorkspaceCreated;
import com.aieducenter.aiplatform.base.workspace.application.event.WorkspaceDestroyed;
import com.aieducenter.aiplatform.base.workspace.application.mapper.WorkspaceMapper;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.SnapshotHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 工作区生命周期用例（B0 蓝图 §2 片1b）：创建 / 查询 / exec / 预览 / 销毁级联。
 *
 * <p>创建异步化（#58/#61）：工作区记录（PROVISIONING 态）与 WorkspaceCreated 事件
 * 收进同一短事务（{@link TransactionTemplate}）立即返回，docker 置备转后台
 * （{@link WorkspaceProvisionAppService}）并行收敛到 ready / failed——「能对话」与「环境就绪」
 * 解耦，点创建即对话。创建不在请求线程内同步调用 docker；事务提交后才提交后台置备，
 * 置备线程 {@code findById} 保证可见已提交记录。落库失败时未产生任何 docker 副作用，
 * 无需回收（副作用全部在提交后、后台线程内落定）。</p>
 *
 * <p>唤醒自愈（#170，ADR-0016）：项目域 API 触碰（{@link #touch}）异步探查容器实态，
 * 缺失/被杀（#168 型漂移；休眠后触碰同路径）则幂等重建至预览可用——重建归置备器
 * 同款收敛（重试上限→FAILED、可再触发），8081 应用拉起归 {@link EnvironmentBackend#startApp}
 * （平台职责），成活以探活 + PreviewReady 收口（前端 SSE 刷新锚）。</p>
 */
@Service
@Slf4j
public class WorkspaceLifecycleAppService implements DisposableBean {

    /** 唤醒/自愈任务并发（单机小池：触碰探查秒级、重建分钟级，2 路并行够用）。 */
    private static final int HEAL_THREADS = 2;

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceProvisionAppService provisioner;
    private final WorkspaceReadinessWaiter readinessWaiter;
    private final WorkspaceProperties properties;

    /**
     * 自愈互斥登记（#170）：同一工作区同时至多一个在途任务（实态探查/幂等重建/应用
     * 拉起共用一面）——并发触碰只触发一次。进程内语义；跨进程由 PROVISIONING 态
     * 与幂等重建兜底。
     */
    private final Set<WorkspaceId> healing = ConcurrentHashMap.newKeySet();

    /** 自愈任务执行器（生产 = 固定 daemon 小池；测试 = 直通/受控注入）。 */
    private final Executor healExecutor;

    /** 生产执行器生命周期（测试注入时为 null）。 */
    private final ExecutorService ownedHealExecutor;

    @Autowired
    public WorkspaceLifecycleAppService(EnvironmentBackend environmentBackend,
                                        WorkspaceRepository workspaceRepository,
                                        TransactionTemplate transactionTemplate,
                                        ApplicationEventPublisher eventPublisher,
                                        WorkspaceMapper workspaceMapper,
                                        WorkspaceProvisionAppService provisioner,
                                        WorkspaceReadinessWaiter readinessWaiter,
                                        WorkspaceProperties properties) {
        this(environmentBackend, workspaceRepository, transactionTemplate, eventPublisher,
                workspaceMapper, provisioner, readinessWaiter, properties,
                new ThreadPoolExecutor(HEAL_THREADS, HEAL_THREADS,
                        0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(),
                        daemonThreadFactory("workspace-heal")));
    }

    /** 测试构造：注入受控执行器（直通/队列）以验收互斥与异步编排。 */
    WorkspaceLifecycleAppService(EnvironmentBackend environmentBackend,
                                 WorkspaceRepository workspaceRepository,
                                 TransactionTemplate transactionTemplate,
                                 ApplicationEventPublisher eventPublisher,
                                 WorkspaceMapper workspaceMapper,
                                 WorkspaceProvisionAppService provisioner,
                                 WorkspaceReadinessWaiter readinessWaiter,
                                 WorkspaceProperties properties,
                                 Executor healExecutor) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.workspaceMapper = workspaceMapper;
        this.provisioner = provisioner;
        this.readinessWaiter = readinessWaiter;
        this.properties = properties;
        this.healExecutor = healExecutor;
        this.ownedHealExecutor = null;
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
        if (ownedHealExecutor != null) {
            ownedHealExecutor.shutdownNow();
        }
    }

    /**
     * 创建工作区：记录经置备状态机入口（registerPending，PROVISIONING、端口 0、
     * 确定性命名）落库并发布 WorkspaceCreated（AFTER_COMMIT）即返回；docker 副作用
     * 转后台 {@link WorkspaceProvisionAppService} 并行收敛——成功经 complete 回填端口 + 资源
     * 转 READY，失败级联回滚（#57）+ 自诊断转 FAILED。
     */
    public WorkspaceResponse create(CreateWorkspaceCommand command) {
        WorkspaceId workspaceId = WorkspaceId.generate();
        EnvKind kind = command.kindOrDefault();
        WorkspaceResponse response = transactionTemplate.execute(status -> {
            Workspace workspace = workspaceRepository.save(
                    Workspace.registerPending(workspaceId, kind));
            eventPublisher.publishApplicationEvent(WorkspaceCreated.of(workspaceId, kind));
            return workspaceMapper.convert(workspace);
        });
        // 事务提交后异步置备（此时记录已可见，置备线程可接回并落库收口）
        provisioner.provision(workspaceId, kind);
        return response;
    }

    /**
     * 查询工作区（重启接回的验证面：记录仍在，句柄可从记录重建）。
     */
    public WorkspaceResponse get(String workspaceId) {
        return workspaceMapper.convert(requireWorkspace(workspaceId));
    }

    /**
     * 重试置备（#63 失败呈现的手动入口）：FAILED → PROVISIONING 落库后重新提交后台
     * 置备（成功经 complete 回填端口 + 资源转 READY）。非 FAILED 态由聚合
     * {@link Workspace#retry()} 抛 WSP_009（PROVISIONING / READY 无需重试）。记录
     * 落库与后台置备解耦（同 create：事务提交后提交，置备线程可见已提交记录）。
     */
    public WorkspaceResponse retry(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        Workspace retried = transactionTemplate.execute(status ->
                workspaceRepository.save(workspace.retry()));
        provisioner.provision(retried.workspaceId(), retried.getKind());
        return workspaceMapper.convert(retried);
    }

    /**
     * 置备失败工作区清单（#63）：status=FAILED 的记录
     * 投影（workspaceId + 失败原因 + 失败时刻）。
     */
    public List<ProvisionFailedWorkspaceResponse> listProvisionFailed() {
        return workspaceRepository.findByStatus(ProvisioningStatus.FAILED).stream()
                .map(workspace -> new ProvisionFailedWorkspaceResponse(
                        workspace.workspaceId().value(),
                        workspace.getProvisionError(),
                        workspace.getUpdatedAt()))
                .toList();
    }

    /**
     * 取工作区运行时句柄（环境能力面的操作锚点；agentscope 内核等底座消费方的
     * 跨上下文出口——{@link WorkspaceHandle} 是 base 内部值对象，非对外 REST 契约）。
     * 不存在即 WSP_001（404）。
     */
    public WorkspaceHandle handleOf(String workspaceId) {
        return requireWorkspace(workspaceId).toHandle();
    }

    /**
     * 在工作区容器内执行命令取结果（exitCode 非 0 是命令失败，不是环境故障）。
     * 置备中的工作区隐式等待就绪（#62），FAILED/超时不静默悬挂而是报错。
     */
    public ExecResultResponse exec(String workspaceId, WorkspaceExecCommand command) {
        Workspace workspace = readinessWaiter.awaitReady(requireWorkspace(workspaceId));
        ExecResult result = environmentBackend.exec(workspace.toHandle(), command.command());
        return new ExecResultResponse(result.stdout(), result.stderr(), result.exitCode());
    }

    /**
     * 暴露预览并发 PreviewReady（AFTER_COMMIT）。发布走短事务——订阅方的事务性
     * 监听依赖一个真实提交的事务，这里预览无落库、事务体只含发布。探活通过才返回
     * （#45：应用可访问的判据）；置备/唤醒进行中立即抛 WSP_013 待期（#170「系统
     * 启动中」，前端轮询续探）——不再阻塞请求线程长等就绪，FAILED 保持 WSP_010。
     */
    public URI exposePreview(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
            // 首次置备或唤醒重建进行中：快速待期，等收敛归后台、前端轮询续探
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_STARTING);
        }
        if (workspace.getStatus() == ProvisioningStatus.FAILED) {
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_PROVISION_FAILED);
        }
        URI url = environmentBackend.exposePort(workspace.toHandle(),
                EnvironmentBackend.DEV_APP_CONTAINER_PORT);
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishApplicationEvent(
                PreviewReady.of(workspace.workspaceId(), url)));
        return url;
    }

    /**
     * 预览 URL（不探活，纯派生，#128 网关化）：workspaceId 子域——「答地址不等于答在线」
     * 的事实查询面（{@code ProjectFactsTool} 答「我后台的地址」）用，不触发探活与
     * PreviewReady 事件。URL 形状与 {@link #exposePreview} 返回同源（同一
     * {@link WorkspaceNaming#previewUrl} 纯函数）。
     */
    public URI previewUrl(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        return URI.create(WorkspaceNaming.previewUrl(workspace.workspaceId(),
                properties.getPreviewScheme(), properties.getPreviewBase()));
    }

    /**
     * 打包工作区源码为 tar.gz 字节流（排除 .env 机密与 node_modules；下载交付
     * 的文件名/HTTP 头归调用方，本层只出字节）。置备中隐式等待就绪（#62）后执行。
     */
    public byte[] packSource(String workspaceId) {
        Workspace workspace = readinessWaiter.awaitReady(requireWorkspace(workspaceId));
        return environmentBackend.packSource(workspace.toHandle());
    }

    /**
     * 起「查看当时」快照容器（#92）：置备中隐式等待就绪（#62）后交给环境后端按
     * ADR 0007 解路二起快照（同卷 :ro + 入口旁路 + 数据副本 + 检出当时代码起应用）。
     * 返回快照句柄（容器名 + 预览 URL——#141 网关子域，环境后端拼好），编排层据此
     * 透传入口与登记在途注册表。
     */
    public SnapshotHandle startSnapshot(String workspaceId, String viewId, String ref) {
        Workspace workspace = readinessWaiter.awaitReady(requireWorkspace(workspaceId));
        return environmentBackend.startSnapshot(workspace.toHandle(), viewId, ref);
    }

    /**
     * 销毁快照容器（#92）：{@code docker rm -f}（副本随容器可写层消失），幂等。
     */
    public void stopSnapshot(SnapshotHandle snapshot) {
        environmentBackend.stopSnapshot(snapshot);
    }

    /**
     * 清扫孤儿快照容器（#92 启动自愈）：平台重启后注册表丢账，在途查看会话即
     * 孤儿——启动期扫清全部 {@code ws-*-snap-*} 容器（「不留孤儿容器」兜底面）。
     */
    public void sweepSnapshotContainers() {
        environmentBackend.sweepSnapshotContainers();
    }

    /**
     * 销毁工作区：先取消在途后台置备（#64，置备中销毁不留孤儿——任务完成
     * createWorkspace 后见取消即回收刚落定资源），再物理级联清理（容器→网络→卷，
     * 后端尽力而为），记录删除的事务内发 WorkspaceDestroyed（AFTER_COMMIT）。物理
     * 清理失败不阻断记录删除——Docker 侧残留以真实状态为准，可重建句柄后重试销毁。
     */
    public void destroy(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        provisioner.cancel(workspace.workspaceId());
        environmentBackend.destroyWorkspace(workspace.toHandle());
        transactionTemplate.executeWithoutResult(status -> {
            workspaceRepository.delete(workspace);
            eventPublisher.publishApplicationEvent(
                    WorkspaceDestroyed.of(workspace.workspaceId()));
        });
    }

    // ---------- 唤醒自愈（#170，ADR-0016：触碰触发、实态探查、幂等重建） ----------

    /**
     * 项目域触碰（#170 唤醒触发面，ADR-0016「唤醒触发面 = 项目 API 自动唤醒」）：
     * 拨 last-touch（闲置计时输入，#171 消费）+ 异步探查容器实态——缺失/被杀
     * （#168 型漂移；后续休眠删容器后的唤醒同路径）则幂等重建至预览可用。
     * {@code startAppOnWake} = 已生成项目唤醒后拉起 8081 应用；从未跑过 run 的
     * 工作区不拉（恢复到未生成态）。只读检查 + 提交即返回（请求线程零 docker 调用），
     * 并发触碰经 {@code healing} 互斥只触发一次。
     */
    public void touch(String workspaceId, boolean startAppOnWake) {
        Workspace workspace = requireWorkspace(workspaceId);
        workspace.markTouched(LocalDateTime.now());
        transactionTemplate.executeWithoutResult(status -> workspaceRepository.save(workspace));
        if (workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
            return;   // 首次置备/唤醒已在途
        }
        WorkspaceId id = workspace.workspaceId();
        if (healing.add(id)) {
            healExecutor.execute(() -> healIfNeeded(id, startAppOnWake));
        }
    }

    /**
     * 应用拉起请求（#170 预览面自愈）：容器在而应用进程死的轻路径——预览探活失败
     * （WSP_012）时由项目预览编排对已生成项目调用。互斥异步拉起，成活以探活 +
     * PreviewReady 收口。与 {@link #touch} 的重建路径共用 {@code healing} 互斥。
     */
    public void requestAppStart(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.getStatus() != ProvisioningStatus.READY) {
            return;
        }
        WorkspaceId id = workspace.workspaceId();
        if (healing.add(id)) {
            healExecutor.execute(() -> {
                try {
                    environmentBackend.startApp(workspace.toHandle());
                    exposePreview(workspaceId);
                } catch (RuntimeException e) {
                    log.warn("[workspace] {} 应用拉起未成（下次触碰/探活再试）", id.value(), e);
                } finally {
                    healing.remove(id);
                }
            });
        }
    }

    /** 自愈任务：实态探查 → 不健康才唤醒重建（唤醒含应用拉起与预览事件收口）。 */
    private void healIfNeeded(WorkspaceId id, boolean startAppOnWake) {
        try {
            Workspace workspace = workspaceRepository.findById(id.id()).orElse(null);
            if (workspace == null || workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
                return;   // 等待间隙已删除/已在途（并发收敛中）
            }
            if (environmentBackend.isContainerRunning(workspace.toHandle())) {
                return;   // 实态健康（意图/实态一致），无事可做
            }
            wakeUp(workspace, startAppOnWake);
        } catch (RuntimeException e) {
            log.warn("[workspace] {} 触碰自愈未成（尽力而为，下次触碰再试）", id.value(), e);
        } finally {
            healing.remove(id);
        }
    }

    /**
     * 唤醒（ADR-0016 醒 = 既有幂等重建路径）：rewake 落 PROVISIONING → 同步重置备
     * （置备器同款重试上限，全败落 FAILED、可再触发）→ 已生成项目拉起 8081 应用
     * → 探活发 PreviewReady。未生成工作区探活必败（WSP_012 预期口径），吞掉。
     */
    private void wakeUp(Workspace workspace, boolean startAppOnWake) {
        WorkspaceId id = workspace.workspaceId();
        log.info("[workspace] {} 容器缺失/被杀，唤醒：幂等重建（卷保留，数据不动）",
                id.value());
        Workspace rewoken = transactionTemplate.execute(status -> {
            // 事务内重取防复活：销毁竞争下记录已删则不迁移（孤儿资源归销毁级联+置备取消协调）
            Workspace fresh = workspaceRepository.findById(id.id()).orElse(null);
            return fresh == null ? null : workspaceRepository.save(fresh.rewake());
        });
        if (rewoken == null) {
            return;
        }
        provisioner.provisionForWake(id, rewoken.getKind());
        Workspace healed = workspaceRepository.findById(id.id()).orElse(null);
        if (healed == null || healed.getStatus() != ProvisioningStatus.READY) {
            return;   // 重试上限落 FAILED（可再触发）或记录已删
        }
        if (startAppOnWake) {
            environmentBackend.startApp(healed.toHandle());
        }
        try {
            exposePreview(id.value());
        } catch (RuntimeException e) {
            // 未生成工作区预期未起服（WSP_012）；已生成的应用问题由预览面自愈续试
            log.debug("[workspace] {} 唤醒后预览探活未过：{}", id.value(), e.getMessage());
        }
    }

    private Workspace requireWorkspace(String workspaceId) {
        return workspaceRepository.findById(parseId(workspaceId))
                .orElseThrow(() -> new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));
    }

    private long parseId(String workspaceId) {
        try {
            long id = Long.parseLong(workspaceId);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
        }
        // 非数值/非正数即不存在的标识，语义上同 404
        throw new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND);
    }
}
