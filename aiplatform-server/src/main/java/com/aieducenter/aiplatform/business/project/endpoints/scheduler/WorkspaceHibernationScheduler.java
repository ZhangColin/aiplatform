package com.aieducenter.aiplatform.business.project.endpoints.scheduler;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceHibernationAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceProperties;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceScanFact;
import com.aieducenter.aiplatform.business.project.application.CodingRunTrack;
import com.aieducenter.aiplatform.business.project.application.VersionSnapshotAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 闲置休眠驱动器（#171，ADR-0016，全库首个 @Scheduled）：定时扫描闲置 DEV 沙箱
 * 休眠 + 顺带清扫闲置快照查看会话。组合方在本层——休眠编排归 base.workspace
 * （只依赖环境后端端口与 DB 状态），「run 在途/已生成」的项目域事实由本层随扫描
 * 递入（base 不反向依赖 business）。
 *
 * <p>节奏：默认每 5 分钟一轮（{@code app.workspace.hibernation-scan-interval}，
 * Duration 形如 {@code 5m}）；启动期对账一次（{@link ApplicationRunner}，首轮
 * scheduled 延迟一个间隔——启动扫描与定时轮不叠跑）。单实例部署不加锁，每轮
 * 全量幂等收敛；测试 profile 以 {@code app.workspace.hibernation-enabled=false}
 * 整轮关闭（集成测试直调扫描，定时器不扰测试库）。</p>
 */
@Component
@Slf4j
public class WorkspaceHibernationScheduler implements ApplicationRunner {

    private final WorkspaceHibernationAppService hibernationAppService;
    private final VersionSnapshotAppService snapshotAppService;
    private final CodingRunTrack codingRunTrack;
    private final ProjectRepository projectRepository;
    private final WorkspaceProperties workspaceProperties;

    public WorkspaceHibernationScheduler(WorkspaceHibernationAppService hibernationAppService,
            VersionSnapshotAppService snapshotAppService,
            CodingRunTrack codingRunTrack,
            ProjectRepository projectRepository,
            WorkspaceProperties workspaceProperties) {
        this.hibernationAppService = hibernationAppService;
        this.snapshotAppService = snapshotAppService;
        this.codingRunTrack = codingRunTrack;
        this.projectRepository = projectRepository;
        this.workspaceProperties = workspaceProperties;
    }

    /** 启动期对账一次（#171 AC）：与定时轮同一扫描——启动即收敛遗留漂移。 */
    @Override
    public void run(ApplicationArguments args) {
        scanRound();
    }

    /** 定时扫描轮：闲置休眠 + 闲置快照清扫（同一闲置阈值，同一轮顺带）。 */
    @Scheduled(fixedDelayString = "${app.workspace.hibernation-scan-interval:5m}",
            initialDelayString = "${app.workspace.hibernation-scan-interval:5m}")
    public void scanRound() {
        if (!workspaceProperties.isHibernationEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            int hibernated = hibernationAppService.scanOnce(scanFacts(), now);
            int swept = snapshotAppService.sweepIdleViews(now, workspaceProperties.getIdleThreshold());
            if (hibernated > 0 || swept > 0) {
                log.info("[hibernation] 扫描轮收口：休眠 {} 工作区、清扫 {} 快照（会话/孤儿）",
                        hibernated, swept);
            }
        } catch (RuntimeException e) {
            // 尽力而为：本轮失败不炸定时器，下轮再收敛
            log.warn("[hibernation] 扫描轮未成（下轮再试）", e);
        }
    }

    /**
     * 项目域事实申报（{@link WorkspaceScanFact}）：全部项目逐行映射——run 在途
     * （恒活跃）与已生成（漂移收敛唤醒后拉应用）。归档与休眠正交（ADR-0016）：
     * 归档项目不筛，同一判定。
     */
    private Map<Long, WorkspaceScanFact> scanFacts() {
        Map<Long, WorkspaceScanFact> facts = new HashMap<>();
        for (Project project : projectRepository.findAll()) {
            facts.put(project.getWorkspaceId(), new WorkspaceScanFact(
                    codingRunTrack.isInFlight(project.getId()),
                    project.getGeneratedAt() != null));
        }
        return facts;
    }
}
