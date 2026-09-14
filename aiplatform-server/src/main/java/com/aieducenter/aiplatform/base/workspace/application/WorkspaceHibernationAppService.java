package com.aieducenter.aiplatform.base.workspace.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceHibernationPolicy;
import com.aieducenter.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.aieducenter.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 闲置休眠扫描（#171，ADR-0016）：定时把闲置（last-touch 逾阈值）的 DEV 沙箱
 * 休眠——删容器保卷、期望态置休眠；每轮以 docker 实态探查为准决策，漂移自动收敛。
 *
 * <p>分支次序（先判意图再探实态）：① 闲置判定过 → 休眠（删容器 + 意图落库，
 * {@link WorkspaceHibernationPolicy} 纯函数）；② 期望运行而容器实死（#168 型漂移）
 * → {@link WorkspaceLifecycleAppService#healDrift} 唤醒收敛——「DB 记 ready、实死
 * 两天」不再依赖用户触碰才有人管；③ 期望休眠而容器仍在（休眠删失败残留/外部
 * 重建）且已闲置 → 删容器向意图收敛。活跃（阈值内触碰或 run 在途）且容器健康
 * → 无事可做。</p>
 *
 * <p>编排只依赖 {@link EnvironmentBackend} 端口与 DB 状态（ADR-0016）：消费方
 * 事实（run 在途/已生成）由调用方随扫描递入（{@link WorkspaceScanFact}），
 * base 不反向依赖 business。单实例语义：无跨进程锁，每轮全量扫描幂等收敛。</p>
 */
@Service
@Slf4j
public class WorkspaceHibernationAppService {

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceLifecycleAppService lifecycle;
    private final WorkspaceProperties properties;

    public WorkspaceHibernationAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            WorkspaceLifecycleAppService lifecycle,
            WorkspaceProperties properties) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.lifecycle = lifecycle;
        this.properties = properties;
    }

    /**
     * 单轮扫描（定时器与启动对账直调；测试直调不等真实定时）：遍历全部工作区逐个
     * 收敛，返回动作数（观测/日志用）。单条异常不炸整轮（尽力而为，下轮再试）。
     */
    public int scanOnce(Map<Long, WorkspaceScanFact> facts, LocalDateTime now) {
        Duration threshold = properties.getIdleThreshold();
        int acted = 0;
        for (Workspace workspace : workspaceRepository.findAll()) {
            if (workspace.getKind() != EnvKind.DEV
                    || workspace.getStatus() == ProvisioningStatus.PROVISIONING) {
                continue;   // 非 DEV 不入休眠面（TEST/PROD 占位）；在途置备不扰
            }
            try {
                if (converge(workspace, facts.getOrDefault(workspace.getId(),
                        WorkspaceScanFact.ABSENT), now, threshold)) {
                    acted++;
                }
            } catch (RuntimeException e) {
                log.warn("[workspace] {} 休眠扫描单条未成（下轮再试）",
                        workspace.workspaceId().value(), e);
            }
        }
        return acted;
    }

    /** 单工作区收敛（三分支次序见类注释）；返回是否发生动作。 */
    private boolean converge(Workspace workspace, WorkspaceScanFact fact,
            LocalDateTime now, Duration threshold) {
        if (WorkspaceHibernationPolicy.shouldHibernate(workspace.getLastTouchAt(), now,
                fact.runInFlight(), workspace.getDesiredState(), threshold)) {
            hibernate(workspace);
            return true;
        }
        boolean running = environmentBackend.isContainerRunning(workspace.toHandle());
        if (!running && workspace.getDesiredState() == DesiredState.RUNNING) {
            log.info("[workspace] {} 期望运行而容器实死，漂移收敛唤醒",
                    workspace.workspaceId().value());
            lifecycle.healDrift(workspace.workspaceId(), fact.startAppOnWake());
            return true;
        }
        if (running && workspace.getDesiredState() == DesiredState.HIBERNATED
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
}
