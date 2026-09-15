package com.aieducenter.aiplatform.base.workspace.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.aggregate.WorkspaceAction;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.enums.WorkspaceActionKind;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.Operator;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceActionRepository;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 后台沙箱动作用例（#174，观测面 {@link WorkspaceObservationAppService} 的写面
 * 对偶）：管理员对单台沙箱的四干预动作——唤醒（等就绪）/ 强制休眠（删容器保卷）/
 * 强制重建（rm＋幂等重建，#168 型「预览死了」的标准化处置）/ 封存（产物同自动
 * 封存）。每动作 append-only 留痕操作者（{@code wsp_workspace_actions}）；动作
 * 后的事实读面归观测用例（方法返回 void，应用层不暴露领域模型——编写规范 §4.1）。
 *
 * <p>守卫链（次序固定）：WSP_001 不存在 → WSP_007 非 DEV → WSP_015 run 在途
 * （唤醒除外——管理员操作资源面，不打断用户正在进行的生成，要处置先取消 run 或
 * 等收口）→ WSP_009 状态不允许（置备在途/封存态动作边界）→ WSP_017 收敛任务
 * 互斥在途。消费方事实（run 在途/已生成拉应用）由组合方递入（照
 * {@link WorkspaceScanFact} 先例：base 不反向依赖 business）。</p>
 *
 * <p>动作同步执行、同步等结果（机机调用可长等）：唤醒/重建经
 * {@link WorkspaceReadinessWaiter} 等到 READY；互斥面与触碰自愈/扫描封存共用
 * （{@link WorkspaceLifecycleAppService#runExclusivelyBlocking} 当前线程持锁），
 * 在途不排队——如实回忙。</p>
 */
@Service
@Slf4j
public class WorkspaceActionAppService {

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final TransactionTemplate transactionTemplate;
    private final WorkspaceLifecycleAppService lifecycle;
    private final SealPackageStore sealPackageStore;
    private final WorkspaceReadinessWaiter readinessWaiter;
    private final WorkspaceActionRepository actionRepository;

    public WorkspaceActionAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            TransactionTemplate transactionTemplate,
            WorkspaceLifecycleAppService lifecycle,
            SealPackageStore sealPackageStore,
            WorkspaceReadinessWaiter readinessWaiter,
            WorkspaceActionRepository actionRepository) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.transactionTemplate = transactionTemplate;
        this.lifecycle = lifecycle;
        this.sealPackageStore = sealPackageStore;
        this.readinessWaiter = readinessWaiter;
        this.actionRepository = actionRepository;
    }

    // ---------- 唤醒 ----------

    /**
     * 唤醒（#174，等就绪）：收敛到 READY＋（已生成）应用在服，同步等结果。健康
     * （容器在跑）即回；封存态走深度唤醒（解包回卷＋重建，分钟级）；容器缺失/
     * 被杀走幂等重建——内核与触碰自愈同一 {@code wakeUp}。探查 UNKNOWN（daemon
     * 抖动）拒以 WSP_002（#176：探查失败≠容器不在，盲重建的预清 rm -f 会杀可能
     * 健康容器上的在途 run；重试即恢复，异步触碰路径不至此——healIfNeeded 对
     * UNKNOWN 让路）。run 在途不受限（唤醒不动数据面）。深度唤醒包不可读时内核
     * 保持封存态（数据完整性优先），本层如实回 WSP_016 而非伪成功。
     */
    public void wake(String workspaceId, boolean startAppOnWake, Operator operator) {
        Workspace workspace = requireDev(workspaceId);
        WorkspaceId id = workspace.workspaceId();
        Workspace woken;
        if (workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
            woken = readinessWaiter.awaitReady(workspace);   // 首次置备/唤醒已在途：等收敛
        } else if (workspace.getDesiredState() == DesiredState.SEALED) {
            woken = wakeByRebuild(workspace, startAppOnWake);   // 封存态：深度唤醒（解包回卷＋重建）
        } else {
            // 判定穷举四态（switch 无 default——枚举加值此点编译期即炸，判定不静默漏分支）
            woken = switch (environmentBackend.containerState(workspace.toHandle())) {
                case RUNNING -> healthyWake(workspace, startAppOnWake);
                case STOPPED, ABSENT -> wakeByRebuild(workspace, startAppOnWake);
                case UNKNOWN -> throw new ApplicationException(
                        WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED);
            };
        }
        if (woken.getDesiredState() == DesiredState.SEALED) {
            // 深度唤醒未成（封存包不可读，内核保持封存态待人工）：如实回错
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_SEAL_PACKAGE_UNAVAILABLE);
        }
        journal(id, WorkspaceActionKind.WAKE, operator);
        log.info("[workspace] {} 后台唤醒：已就绪（拉应用={}）", id.value(), startAppOnWake);
    }

    /**
     * 强制休眠（#174）：立即删容器保卷、期望态置休眠——管理员即时止损口（占资源
     * 的活沙箱当场收敛）。已休眠＝幂等成功（补删残留容器，意图已对）；封存态拒
     * （卷已删，先唤醒）；置备在途拒。
     *
     * @throws ApplicationException WSP_001/007/015/009/017（守卫链见类注释）
     */
    public void forceHibernate(String workspaceId, boolean runInFlight, Operator operator) {
        Workspace workspace = requireDev(workspaceId);
        requireActionableState(workspace, runInFlight);
        WorkspaceId id = workspace.workspaceId();
        runGuarded(id, () -> {
            Workspace fresh = requirePresent(id);   // 重取：让路间隙状态已变则以新事实为准
            environmentBackend.hibernate(fresh.toHandle());   // 删容器保卷（幂等）
            if (fresh.getDesiredState() == DesiredState.RUNNING) {
                transactionTemplate.executeWithoutResult(status ->
                        workspaceRepository.save(fresh.hibernate()));   // 聚合自持状态不变量
            }
            // 已休眠：意图已对，只补了物理面
        });
        journal(id, WorkspaceActionKind.HIBERNATE, operator);
        log.info("[workspace] {} 后台强制休眠：容器已删、卷保留", id.value());
    }

    /**
     * 强制重建（#174）：rm＋幂等重建——#168 型「预览死了」事故的标准化处置，
     * 替代手工 docker 拉。「强制」的意义：在跑但坏了的容器也杀（卷保留、数据
     * 不动），随后走唤醒内核同一重建路径收敛回 READY。封存态拒（卷已删，数据
     * 只在包里——空卷重建＝掩埋数据丢失，先唤醒）；置备在途拒。
     *
     * @throws ApplicationException WSP_001/007/015/009/017；重建重试上限落 FAILED
     *         时 WSP_010（置备失败，可再触发）
     */
    public void forceRebuild(String workspaceId, boolean startAppOnWake,
            boolean runInFlight, Operator operator) {
        Workspace workspace = requireDev(workspaceId);
        requireActionableState(workspace, runInFlight);
        WorkspaceId id = workspace.workspaceId();
        runGuarded(id, () -> {
            Workspace fresh = requirePresent(id);   // 重取：让路间隙状态已变则以新事实为准
            environmentBackend.hibernate(fresh.toHandle());   // rm（幂等，卷保留）
            lifecycle.wakeUp(fresh, startAppOnWake);          // rewake→幂等重建→拉应用
        });
        readinessWaiter.awaitReady(requirePresent(id));
        journal(id, WorkspaceActionKind.REBUILD, operator);
        log.info("[workspace] {} 后台强制重建：容器已换新、卷保留", id.value());
    }

    /**
     * 封存（#174，产物同自动封存）：动作序照 {@code WorkspaceHibernationAppService}
     * 的 sealNow——打包前置自愈（幂等删容器）→ 打包 → 落盘 → 意图置封存＋元数据
     * → 删卷（失败由扫描 ③′ 收敛）。差异仅在入口：管理员即时发起（RUNNING 起点
     * 补一跳意图置休眠）、同步等结果、失败如实上抛（扫描器是记日志下轮重来）。
     * 两入口有意各自成文（失败语义不同不抽共享内核——封存序若添第三入口再收）。
     * 重复封存拒（走「唤醒→休眠→封存」周期）；置备在途拒。run 在途拒——封存是
     * 深回收，卷正被 run 读写时打包＝半程数据。
     *
     * @throws ApplicationException WSP_001/007/015/009/017
     */
    public void seal(String workspaceId, boolean runInFlight, Operator operator) {
        Workspace workspace = requireDev(workspaceId);
        requireActionableState(workspace, runInFlight);
        WorkspaceId id = workspace.workspaceId();
        runGuarded(id, () -> {
            Workspace fresh = requirePresent(id);   // 重取：让路间隙状态已变则以新事实为准
            if (fresh.getStatus() == ProvisioningStatus.PROVISIONING
                    || fresh.getDesiredState() == DesiredState.SEALED) {
                // 互斥面内复核（封存是长活，前置守卫后仍有触碰唤醒交错的窗口）
                throw new ApplicationException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
            }
            // 打包前置自愈 + RUNNING 起点意图跳：保证 packVolume「只在静默卷上调用」
            environmentBackend.hibernate(fresh.toHandle());
            Workspace dormant = fresh.getDesiredState() == DesiredState.RUNNING
                    ? transactionTemplate.execute(status -> workspaceRepository.save(fresh.hibernate()))
                    : fresh;
            byte[] archive = environmentBackend.packVolume(dormant.toHandle());
            SealPackage pkg = archive != null
                    ? sealPackageStore.save(id, archive) : null;   // 卷不在 = 无包可记
            LocalDateTime sealedAt = LocalDateTime.now();
            Workspace recorded = transactionTemplate.execute(status -> {
                Workspace current = workspaceRepository.findById(id.id()).orElse(null);
                if (current == null) {
                    return null;   // 销毁竞争：记录已删——刚落的包随手清，不留孤儿
                }
                return workspaceRepository.save(current.seal(pkg, sealedAt));
            });
            if (recorded == null) {
                if (pkg != null) {
                    sealPackageStore.delete(pkg.path());
                }
                log.warn("[workspace] {} 后台封存落库时记录已删（销毁竞争），封存包已清",
                        id.value());
                throw new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND);
            }
            log.info("[workspace] {} 后台封存：包 {}（{} 字节）", id.value(),
                    pkg != null ? pkg.path() : "（无——卷已失）",
                    pkg != null ? pkg.sizeBytes() : 0);
            if (!environmentBackend.deleteVolume(recorded.toHandle())) {
                // 删卷失败（占用等）：意图已封存，扫描 ③′ 分支下轮收敛
                log.info("[workspace] {} 后台封存后删卷未成（占用/残留），下轮扫描收敛",
                        id.value());
            }
        });
        journal(id, WorkspaceActionKind.SEAL, operator);
    }

    // ---------- 内部 ----------

    /**
     * 健康路径（实态在跑，意图/实态一致或休眠残留对齐）：按需幂等拉应用；期望休眠
     * 而实态在跑（删失败残留/外部重建的漂移形）对齐意图翻运行——否则扫描器按休眠
     * 意图再删容器，唤醒被静默撤销。显式唤醒即活跃，last-touch 一并拨动。
     */
    private Workspace healthyWake(Workspace workspace, boolean startAppOnWake) {
        if (startAppOnWake) {
            environmentBackend.startApp(workspace.toHandle());   // 幂等：已在服直回
        }
        if (workspace.getDesiredState() == DesiredState.HIBERNATED) {
            workspace.markTouched(LocalDateTime.now());
            return workspaceRepository.save(workspace);
        }
        return workspace;   // 实态健康（意图/实态一致）
    }

    /**
     * 唤醒内核驱动＋等就绪（容器缺失/被杀的幂等重建与封存深度唤醒共用的收尾）：
     * 互斥在途则让路（他人任务收敛中），随后等 READY。
     */
    private Workspace wakeByRebuild(Workspace workspace, boolean startAppOnWake) {
        WorkspaceId id = workspace.workspaceId();
        lifecycle.runExclusivelyBlocking(id,
                () -> lifecycle.wakeUp(workspace, startAppOnWake));
        return readinessWaiter.awaitReady(requirePresent(id));
    }

    /**
     * 守卫链前段（WSP_001/007）：存在性 → DEV。run 在途（WSP_015）与状态守卫
     * （WSP_009）归 {@link #requireActionableState}——唤醒两者皆不受限/另有口径。
     */
    private Workspace requireDev(String workspaceId) {
        Workspace workspace = requirePresent(parseId(workspaceId));
        if (workspace.getKind() != EnvKind.DEV) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_KIND_NOT_SUPPORTED);
        }
        return workspace;
    }

    /**
     * 重活共用守卫对（强制休眠/强制重建/封存）：run 在途拒（WSP_015）＋置备在途/
     * 封存态拒（WSP_009——封存态卷已删，任何换容器动作都会拿空卷顶替；先唤醒）。
     */
    private void requireActionableState(Workspace workspace, boolean runInFlight) {
        if (runInFlight) {
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_ACTION_RUN_IN_FLIGHT);
        }
        if (workspace.getStatus() == ProvisioningStatus.PROVISIONING
                || workspace.getDesiredState() == DesiredState.SEALED) {
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_STATE_INVALID);
        }
    }

    /** 互斥面内执行重活（在途 → WSP_017 如实回忙，不排队；任务异常原样上抛）。 */
    private void runGuarded(WorkspaceId id, Runnable task) {
        if (!lifecycle.runExclusivelyBlocking(id, task)) {
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_ACTION_BUSY);
        }
    }

    /** 动作行留痕（append-only；操作者缺头落空）。 */
    private void journal(WorkspaceId id, WorkspaceActionKind kind, Operator operator) {
        actionRepository.save(WorkspaceAction.record(id, kind, operator, LocalDateTime.now()));
    }

    private Workspace requirePresent(WorkspaceId id) {
        return workspaceRepository.findById(id.id())
                .orElseThrow(() -> new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND));
    }

    /** 寻址解析：非数值/非正数即不存在的标识，语义上同 404（与 lifecycle 同口径）。 */
    private WorkspaceId parseId(String workspaceId) {
        long id;
        try {
            id = Long.parseLong(workspaceId);
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
            id = 0;
        }
        if (id <= 0) {
            throw new ApplicationException(WorkspaceMessage.WORKSPACE_NOT_FOUND);
        }
        return new WorkspaceId(id);
    }
}
