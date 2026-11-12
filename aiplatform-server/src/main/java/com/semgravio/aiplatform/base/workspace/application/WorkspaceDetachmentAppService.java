package com.semgravio.aiplatform.base.workspace.application;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceLostMark;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.ProvisioningStatus;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;
import com.semgravio.aiplatform.base.workspace.domain.port.SealPackageStore;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceLostMarkRepository;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 在册失联分级处置（#488，ADR-0045）：扫描轮末尾对「在册但项目行已删」的工作区
 * 行分级——物理资源仍在 → 只标记在册失联（{@code wsp_lost_marks}）待人工裁决
 * （机器不删可能是用户数据的卷，ADR-0016）；物理已空 → 删行做注册表卫生（空壳
 * 行噪音不淹失联清单）。
 */
@Service
@Slf4j
public class WorkspaceDetachmentAppService {

    /**
     * 失联判定新建宽限窗（双闸之一）：工作区先于项目行落库（一句话建项目两步落库），
     * 窗内行不判失联——防创建窗口误标。10 分钟口径与无主收编宽限
     * （{@code WorkspaceHibernationAppService#ORPHAN_SWEEP_GRACE}）同值：同是
     * 「资源/记录已建、对账面尚未落」的窗口保护，语义各自成文。
     */
    static final Duration DETACH_GRACE = Duration.ofMinutes(10);

    private final EnvironmentBackend environmentBackend;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceLostMarkRepository lostMarkRepository;
    private final SealPackageStore sealPackageStore;
    private final TransactionTemplate transactionTemplate;
    private final WorkspaceConvergenceAppService convergence;

    public WorkspaceDetachmentAppService(EnvironmentBackend environmentBackend,
            WorkspaceRepository workspaceRepository,
            WorkspaceLostMarkRepository lostMarkRepository,
            SealPackageStore sealPackageStore,
            TransactionTemplate transactionTemplate,
            WorkspaceConvergenceAppService convergence) {
        this.environmentBackend = environmentBackend;
        this.workspaceRepository = workspaceRepository;
        this.lostMarkRepository = lostMarkRepository;
        this.sealPackageStore = sealPackageStore;
        this.transactionTemplate = transactionTemplate;
        this.convergence = convergence;
    }

    /**
     * 分级处置一轮（休眠扫描轮末尾接线，#486 收编之后——在册行不进无主收编面，
     * 本面承接其分级处置）。尽力而为：单条失败记日志不炸整轮（下轮再试）。
     *
     * <p>判定与双闸（ADR-0045）：「在册失联」＝注册行在而消费方认领集不含（当下
     * 认领正本＝项目行——项目行已删即失联；base 不反向依赖 business，认领集由
     * 组合方随扫描递入，照 {@code WorkspaceScanFact} 先例）。闸一＝新建宽限窗
     * （{@link #DETACH_GRACE}——项目行可能尚未落库的创建窗口内不判失联）；闸二＝
     * 物理全空才删行（探查 UNKNOWN 让路只标记——探查失败≠容器不在，#176 口径）。
     * 分级红线：物理资源仍在（容器或卷任一在场）只标记 {@code wsp_lost_marks} 待
     * 人工裁决（裁决出口＝#490 一键回收），机器不删可能是用户数据的卷
     * （ADR-0016）。</p>
     *
     * <p>已知边界：fork 短命工作区（#351，切片并行缺省关）无项目行——长切片超窗
     * 后会被暂态标记（物理资源在，绝不删），fork 销毁删行时标记随 FK 级联消亡，
     * 不丢数据、自愈归零。</p>
     *
     * @param registered 本轮注册表全量行（调用方扫描所得）
     * @param claimedIds 消费方认领集（组合方递入：项目行在的工作区编号）
     * @param now        本轮时刻
     */
    public void reconcileDetached(Collection<Workspace> registered, Set<Long> claimedIds,
            LocalDateTime now) {
        for (Workspace workspace : registered) {
            try {
                reconcileOne(workspace, claimedIds, now);
            } catch (RuntimeException e) {
                log.warn("[workspace] {} 在册失联处置未成（下轮再试）",
                        workspace.workspaceId().value(), e);
            }
        }
    }

    /**
     * 单行分级：认领在/窗内/在途置备 → 不动；物理全空 → 删行卫生；其余（资源在/
     * 探查不明）→ 标记失联。PROVISIONING 在途行不扰（与休眠面同口径）：置备中
     * 物理未建、项目行可能未落——误判「物理全空」删行后置备线程 complete() 再
     * 落库会复活行（不受「删行不复活」守卫约束的写入路径）。
     */
    private void reconcileOne(Workspace workspace, Set<Long> claimedIds, LocalDateTime now) {
        if (claimedIds.contains(workspace.getId())
                || workspace.getStatus() == ProvisioningStatus.PROVISIONING
                || workspace.getLastTouchAt().isAfter(now.minus(DETACH_GRACE))) {
            return;   // 认领在（项目行在）/在途置备不扰/新建窗口内（项目行可能尚未落库）
        }
        ContainerState container = environmentBackend.containerState(workspace.toHandle());
        boolean volumeInPlace = environmentBackend.volumeExists(workspace.toHandle());
        if (container == ContainerState.ABSENT && !volumeInPlace) {
            purgeHollowRow(workspace.workspaceId(), now);
            return;
        }
        markLost(workspace.workspaceId(), now);
    }

    /** 首标落行（幂等：已标跳过——不翻审计列、不覆盖首标时刻）。 */
    private void markLost(WorkspaceId id, LocalDateTime now) {
        if (lostMarkRepository.findById(id.id()).isPresent()) {
            return;
        }
        lostMarkRepository.save(WorkspaceLostMark.mark(id, now));
        log.warn("[workspace] {} 在册失联：项目行已删而物理资源仍在——只标记待人工裁决"
                + "（机器不删可能是用户数据的卷，ADR-0045）", id.value());
    }

    /**
     * 空壳行卫生删行（#488，ADR-0045）：物理全空（容器/卷俱无）——删注册行做卫生，
     * 标记行与采样行随 FK 级联消亡（#487 先例），失联清单不被空壳噪音淹没。删行
     * 收口进收敛互斥面（等待式，#485 同面）：与唤醒/收敛双向串行——判定（互斥面
     * 外探查）到删行之间若有收敛任务重建了资源（如后台手动唤醒失联行），任务内
     * 重探即见、不删行改标记；事务内重取防销毁/并发卫生删行竞态（行已删静默落空）。
     * 封存包随手清（行删后包即无锚点——不留无锚封存包占存储，尽力而为吞错）。不发
     * {@code WorkspaceDestroyed}：用户面销毁语义归 destroy 通道（受理即删时已发或
     * 吞错未达），本面是注册表卫生，不重复语义。
     */
    private void purgeHollowRow(WorkspaceId id, LocalDateTime now) {
        convergence.runExclusivelyAwaiting(id, () -> {
            Workspace fresh = workspaceRepository.findById(id.id()).orElse(null);
            if (fresh == null) {
                return;   // 竞态：行已删（销毁/并发卫生）——幂等落空
            }
            ContainerState container = environmentBackend.containerState(fresh.toHandle());
            boolean volumeInPlace = environmentBackend.volumeExists(fresh.toHandle());
            if (container != ContainerState.ABSENT || volumeInPlace) {
                // 判定后到删行之间物理又在了（唤醒竞态等）：不删行——改走标记待裁决
                markLost(id, now);
                return;
            }
            transactionTemplate.executeWithoutResult(status -> {
                Workspace current = workspaceRepository.findById(id.id()).orElse(null);
                if (current == null) {
                    return;   // 事务内重取：行已删静默落空（不复活）
                }
                if (current.getArchivePath() != null) {
                    try {
                        sealPackageStore.delete(current.getArchivePath());
                    } catch (RuntimeException e) {
                        log.warn("[workspace] {} 空壳行清封存包未成（{}，无锚包待磁盘对账）",
                                id.value(), current.getArchivePath(), e);
                    }
                }
                workspaceRepository.delete(current);
            });
            log.info("[workspace] {} 空壳行已卫生删除（项目行已删、物理全空——注册表卫生）",
                    id.value());
        });
    }
}
