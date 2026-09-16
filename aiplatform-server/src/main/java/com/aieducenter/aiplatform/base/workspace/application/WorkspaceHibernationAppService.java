package com.aieducenter.aiplatform.base.workspace.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHibernationPolicy;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 闲置休眠扫描（#171，ADR-0016）：定时把闲置（last-touch 逾阈值）的 DEV 沙箱
 * 休眠——删容器保卷、期望态置休眠；每轮以 docker 实态探查为准决策，漂移自动收敛。
 *
 * <p>分支次序（先判意图再探实态）：① 闲置判定过 → 休眠（删容器 + 意图落库，
 * {@link WorkspaceHibernationPolicy} 纯函数）；①′ 休眠满期（闲置逾「闲置阈值＋
 * 封存阈值」）→ 封存（#172：整卷打成封存包存平台存储后删卷、意图置封存——与
 * 唤醒/删除互斥，经收敛模块独占提交（{@link WorkspaceConvergenceAppService} 包内
 * 互斥面），不拖扫描轮节奏）；② 期望运行而容器实死（#168 型漂移）
 * → 收敛模块 SCAN 面异步收敛（{@link WorkspaceConvergenceAppService#convergeAsync}）——
 * 「DB 记 ready、实死两天」不再依赖用户触碰才有人管；③ 期望休眠而容器仍在
 * （休眠删失败残留/外部重建）且已闲置 → 删容器向意图收敛；③′ 期望封存而卷仍在
 * （封存删卷失败/删后外部漂移重建的残留）→ 删卷向意图收敛。活跃（阈值内触碰或
 * run 在途）且容器健康 → 无事可做；探查 UNKNOWN（daemon 抖动）两支皆让路、下轮
 * 再看（#176：探查失败≠容器不在，不盲动手）。</p>
 *
 * <p>封存动作序（数据安全定序）：打包落盘 → 意图+元数据落库（事务内重取防销毁
 * 竞争复活）→ 删卷（尽力而为，失败由 ③′ 下轮收敛）。任一步失败意图不翻，下轮
 * 重来；包落定后意图落库前崩溃 = 卷在包在，下轮重打包覆盖旧包，零数据损失窗口。
 * 重复封存覆盖旧包（确定性命名）。</p>
 *
 * <p>编排只依赖 {@link EnvironmentBackend}/{@link SealPackageStore} 端口与 DB 状态
 * （ADR-0016）：消费方事实（run 在途/已生成）由调用方随扫描递入
 * （{@link WorkspaceScanFact}），base 不反向依赖 business。单实例语义：无跨进程锁，
 * 每轮全量扫描幂等收敛。</p>
 */
@Service
@Slf4j
public class WorkspaceHibernationAppService {

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceConvergenceAppService convergence;
    private final SealPackageStore sealPackageStore;
    private final TransactionTemplate transactionTemplate;
    private final WorkspaceProperties properties;

    public WorkspaceHibernationAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            WorkspaceConvergenceAppService convergence,
            SealPackageStore sealPackageStore,
            TransactionTemplate transactionTemplate,
            WorkspaceProperties properties) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.convergence = convergence;
        this.sealPackageStore = sealPackageStore;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    /**
     * 单轮扫描（定时器与启动对账直调；测试直调不等真实定时）：遍历全部工作区逐个
     * 收敛，返回动作数（观测/日志用）。单条异常不炸整轮（尽力而为，下轮再试）。
     * 总开关（{@link WorkspaceProperties#isHibernationEnabled()}）由本入口自持
     * （#197）：关闭即静默返回——业务侧调度器不再跨域读此开关，只把消费方事实
     * 随扫描递入。
     */
    public int scanOnce(Map<Long, WorkspaceScanFact> facts, LocalDateTime now) {
        if (!properties.isHibernationEnabled()) {
            return 0;   // 总开关关闭（#197 内移）
        }
        Duration threshold = properties.getIdleThreshold();
        Duration sealThreshold = properties.getSealThreshold();
        int acted = 0;
        for (Workspace workspace : workspaceRepository.findAll()) {
            if (workspace.getKind() != EnvKind.DEV
                    || workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
                continue;   // 非 DEV 不入休眠面（TEST/PROD 占位）；在途置备/唤醒不扰
            }
            try {
                if (converge(workspace, facts.getOrDefault(workspace.getId(),
                        WorkspaceScanFact.ABSENT), now, threshold, sealThreshold)) {
                    acted++;
                }
            } catch (RuntimeException e) {
                log.warn("[workspace] {} 休眠扫描单条未成（下轮再试）",
                        workspace.workspaceId().value(), e);
            }
        }
        return acted;
    }

    /** 单工作区收敛（分支次序见类注释）；返回是否发生动作。 */
    private boolean converge(Workspace workspace, WorkspaceScanFact fact,
            LocalDateTime now, Duration threshold, Duration sealThreshold) {
        if (WorkspaceHibernationPolicy.shouldHibernate(workspace.getLastTouchAt(), now,
                fact.runInFlight(), workspace.getDesiredState(), threshold)) {
            hibernate(workspace);
            return true;
        }
        if (WorkspaceHibernationPolicy.shouldSeal(workspace.getLastTouchAt(), now,
                fact.runInFlight(), workspace.getDesiredState(), threshold, sealThreshold)) {
            log.info("[workspace] {} 休眠满期，封存：打包落盘 → 意图置封存 → 删卷",
                    workspace.workspaceId().value());
            convergence.runExclusively(workspace.workspaceId(),
                    () -> sealNow(workspace.workspaceId()));
            return true;
        }
        if (workspace.getDesiredState() == DesiredState.SEALED) {
            // 封存意图而卷仍在（删卷失败残留/外部漂移重建）：删卷向意图收敛。独占
            // 提交——与深度唤醒互斥（唤醒在途则本轮让路；任务内重取防与唤醒交错，
            // 否则可能删掉刚解包回卷的卷），外部重建的容器一并清（占卷容器让删卷
            // 永远失败）。不计数：收敛是静默卫生，acted 保持「休眠/封存动作」口径
            convergence.runExclusively(workspace.workspaceId(),
                    () -> convergeSealedResidue(workspace.workspaceId()));
            return false;
        }
        ContainerState state = environmentBackend.containerState(workspace.toHandle());
        if (state.confidentlyNotRunning() && workspace.getDesiredState() == DesiredState.RUNNING) {
            log.info("[workspace] {} 期望运行而容器实死，漂移收敛唤醒",
                    workspace.workspaceId().value());
            convergence.convergeAsync(workspace.workspaceId(), ConvergenceFace.SCAN,
                    fact.startAppOnWake());
            return true;
        }
        if (state == ContainerState.RUNNING && workspace.getDesiredState() == DesiredState.HIBERNATED
                && WorkspaceHibernationPolicy.isIdle(workspace.getLastTouchAt(), now, threshold)) {
            log.info("[workspace] {} 期望休眠而容器仍在（残留/外部重建），删容器收敛",
                    workspace.workspaceId().value());
            environmentBackend.hibernate(workspace.toHandle());   // 意图已对，只动物理面
            return true;
        }
        return false;
    }

    /** 休眠：先删容器（尽力而为），后落意图——删失败则意图不翻，下轮重试。 */
    private void hibernate(Workspace workspace) {
        environmentBackend.hibernate(workspace.toHandle());
        workspaceRepository.save(workspace.hibernate());
        log.info("[workspace] {} 闲置休眠：容器已删、卷保留、期望态置休眠",
                workspace.workspaceId().value());
    }

    /**
     * 封存卷残留收敛任务（独占面内执行）：重取防与深度唤醒交错（让路期间已唤醒/
     * 已删除则不动——否则可能删掉刚解包回卷的卷），外部重建的容器一并清（占卷
     * 容器让删卷永远失败），删卷向封存意图收敛。单条异常记日志，下轮再收敛。
     */
    private void convergeSealedResidue(WorkspaceId id) {
        try {
            Workspace workspace = workspaceRepository.findById(id.id()).orElse(null);
            if (workspace == null || workspace.getDesiredState() != DesiredState.SEALED) {
                return;
            }
            environmentBackend.hibernate(workspace.toHandle());
            if (environmentBackend.deleteVolume(workspace.toHandle())) {
                log.info("[workspace] {} 封存卷残留已清（删卷失败/外部漂移重建的收敛）",
                        id.value());
            }
        } catch (RuntimeException e) {
            log.warn("[workspace] {} 封存卷残留收敛未成（下轮再试）", id.value(), e);
        }
    }

    /**
     * 封存任务（独占面内执行，#172）：重取防让路期间状态已变 → 打包 → 落盘 →
     * 意图+元数据落库（销毁竞争下记录已删则清刚落的包）→ 删卷。单条异常记日志
     * （互斥由提交面释放），下轮扫描重试。管理端同序入口见
     * {@code WorkspaceActionAppService#seal}（失败语义不同有意各自成文）。
     */
    private void sealNow(WorkspaceId id) {
        try {
            Workspace workspace = workspaceRepository.findById(id.id()).orElse(null);
            if (workspace == null || workspace.getKind() != EnvKind.DEV
                    || workspace.getStatus() == ProvisioningStatus.PROVISIONING
                    || workspace.getDesiredState() != DesiredState.HIBERNATED) {
                return;   // 让路期间已删除/已唤醒/已在途：本轮放弃，状态归新事实
            }
            // 打包前置自愈（实态对齐意图）：休眠删容器若曾静默失败，此刻卷仍是活卷
            //（pg 在跑，一致性无保障）——先幂等删容器（不在 no-op）再打包，保证
            // packVolume「只在静默卷上调用」的前置（ADR-0016 以实态为准决策）
            environmentBackend.hibernate(workspace.toHandle());
            byte[] archive = environmentBackend.packVolume(workspace.toHandle());
            SealPackage sealed = archive != null
                    ? sealPackageStore.save(id, archive) : null;   // 卷不在 = 无包可记
            LocalDateTime sealedAt = LocalDateTime.now();
            Workspace recorded = transactionTemplate.execute(status -> {
                Workspace fresh = workspaceRepository.findById(id.id()).orElse(null);
                if (fresh == null) {
                    return null;   // 销毁竞争：记录已删——刚落的包随手清，不留孤儿
                }
                return workspaceRepository.save(fresh.seal(sealed, sealedAt));
            });
            if (recorded == null) {
                if (sealed != null) {
                    sealPackageStore.delete(sealed.path());
                }
                log.warn("[workspace] {} 封存落库时记录已删（销毁竞争），封存包已清",
                        id.value());
                return;
            }
            log.info("[workspace] {} 已封存：包 {}（{} 字节）",
                    id.value(), sealed != null ? sealed.path() : "（无——卷已失）",
                    sealed != null ? sealed.sizeBytes() : 0);
            if (!environmentBackend.deleteVolume(recorded.toHandle())) {
                // 删卷失败（占用等）：意图已封存，扫描 ③′ 分支下轮收敛
                log.info("[workspace] {} 封存后删卷未成（占用/残留），下轮扫描收敛",
                        id.value());
            }
        } catch (RuntimeException e) {
            log.warn("[workspace] {} 封存未成（下轮扫描再试）", id.value(), e);
        }
    }
}
