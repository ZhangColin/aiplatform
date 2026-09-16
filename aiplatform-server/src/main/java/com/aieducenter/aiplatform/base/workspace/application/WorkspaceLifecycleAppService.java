package com.aieducenter.aiplatform.base.workspace.application;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.event.ApplicationEventPublisher;

import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceContentPackage;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ProvisionFailedWorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.application.event.PreviewReady;
import com.aieducenter.aiplatform.base.workspace.application.event.WorkspaceCreated;
import com.aieducenter.aiplatform.base.workspace.application.event.WorkspaceDestroyed;
import com.aieducenter.aiplatform.base.workspace.application.mapper.WorkspaceMapper;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.ExecResult;
import com.aieducenter.aiplatform.base.workspace.domain.model.SnapshotHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
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
 * <p>向活收敛（唤醒/自愈/拨针/互斥）自 #196 起归
 * {@link WorkspaceConvergenceAppService} 单源模块；本服务只在其边界上留一个下载面
 * 调用点（{@link #contentPackageOf} 的未封存唤醒分支）。</p>
 */
@Service
@Slf4j
public class WorkspaceLifecycleAppService {

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceProvisionAppService provisioner;
    private final WorkspaceReadinessWaiter readinessWaiter;
    private final WorkspaceProperties properties;
    private final SealPackageStore sealPackageStore;
    private final WorkspaceConvergenceAppService convergence;

    public WorkspaceLifecycleAppService(EnvironmentBackend environmentBackend,
                                        WorkspaceRepository workspaceRepository,
                                        TransactionTemplate transactionTemplate,
                                        ApplicationEventPublisher eventPublisher,
                                        WorkspaceMapper workspaceMapper,
                                        WorkspaceProvisionAppService provisioner,
                                        WorkspaceReadinessWaiter readinessWaiter,
                                        WorkspaceProperties properties,
                                        SealPackageStore sealPackageStore,
                                        WorkspaceConvergenceAppService convergence) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.workspaceMapper = workspaceMapper;
        this.provisioner = provisioner;
        this.readinessWaiter = readinessWaiter;
        this.properties = properties;
        this.sealPackageStore = sealPackageStore;
        this.convergence = convergence;
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
     * 项目文件包（#174 后台下载）：封存态直取封存包（卷已删，包是唯一事实——
     * 整卷口径，含数据/机密）；未封存即时导出源码包（交付口径，同
     * {@link #packSource} 内核——订单源码包流程不动）。未封存而容器缺失（休眠/
     * 漂移）先经收敛模块同步唤醒重建再打包（DOWNLOAD 面：不拨 last-touch、不拉
     * 应用——打包只要容器，取完闲置扫描自然收回）。封存态无包记录或包不可读抛
     * WSP_016（不静默换路径——包是封存数据的唯一载体）。
     */
    public WorkspaceContentPackage contentPackageOf(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.getDesiredState() == DesiredState.SEALED) {
            return new WorkspaceContentPackage(openSealArchive(workspace), true);
        }
        convergence.convergeBlocking(workspace.workspaceId(), ConvergenceFace.DOWNLOAD, false);
        return new WorkspaceContentPackage(packSource(workspaceId), false);
    }

    /** 取封存包字节：无记录/不可读统一 WSP_016（外层 IO 异常归一为域错误）。 */
    private byte[] openSealArchive(Workspace workspace) {
        String archivePath = workspace.getArchivePath();
        if (archivePath == null) {
            log.warn("[workspace] {} 封存态无封存包记录（外部漂移），下载拒（不以空产物顶替）",
                    workspace.workspaceId().value());
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE);
        }
        try {
            return sealPackageStore.open(archivePath);
        } catch (RuntimeException e) {
            log.error("[workspace] {} 封存包不可读（{}），下载拒",
                    workspace.workspaceId().value(), archivePath, e);
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE);
        }
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
     * 清扫保留集之外的快照容器（#171 运行期孤儿兜底，休眠扫描顺带）：保留集 =
     * 在用查看会话的容器名，集外快照容器扫清——孤儿清扫不再只在启动期跑。
     */
    public int sweepOrphanSnapshots(Collection<String> keepContainerNames) {
        return environmentBackend.sweepSnapshotContainersExcept(keepContainerNames);
    }

    /**
     * 销毁工作区：先取消在途后台置备（#64，置备中销毁不留孤儿——任务完成
     * createWorkspace 后见取消即回收刚落定资源），再物理级联清理（容器→网络→卷，
     * 后端尽力而为）＋封存包一并清理（#172：深度唤醒不删包——包是「最近一次封存」
     * 的事实与覆盖锚，删除项目才是它的终点），记录删除的事务内发 WorkspaceDestroyed
     * （AFTER_COMMIT）。物理清理失败不阻断记录删除——Docker 侧残留以真实状态为准，
     * 可重建句柄后重试销毁。删记录的事务内重取一次包路径兜底清包：封存提交若与本
     * 方法取记录交错（取到的旧副本尚无包路径），按旧副本删会漏包留磁盘孤儿。
     */
    public void destroy(String workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        provisioner.cancel(workspace.workspaceId());
        environmentBackend.destroyWorkspace(workspace.toHandle());
        if (workspace.getArchivePath() != null) {
            sealPackageStore.delete(workspace.getArchivePath());
        }
        transactionTemplate.executeWithoutResult(status -> {
            // 事务内重取：封存与销毁交错时以最新记录清包（尽力而为不抛，不阻断删除）
            workspaceRepository.findById(workspace.workspaceId().id())
                    .map(Workspace::getArchivePath)
                    .ifPresent(path -> sealPackageStore.delete(path));
            workspaceRepository.delete(workspace);
            eventPublisher.publishApplicationEvent(
                    WorkspaceDestroyed.of(workspace.workspaceId()));
        });
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
