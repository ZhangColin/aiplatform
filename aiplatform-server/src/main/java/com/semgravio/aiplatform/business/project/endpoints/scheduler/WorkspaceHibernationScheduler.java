package com.semgravio.aiplatform.business.project.endpoints.scheduler;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceHibernationAppService;
import com.semgravio.aiplatform.base.workspace.application.WorkspaceScanFact;
import com.semgravio.aiplatform.business.project.application.CodingRunTrack;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 闲置休眠扫描组合根（#171，ADR-0016，#197 正名；全库首个 @Scheduled）：业务侧
 * 把「run 在途/已生成」的项目域事实随扫描递入 base.workspace 的休眠扫描（base
 * 不反向依赖 business）。休眠编排与总开关均归 base.workspace 自持（只依赖环境
 * 后端端口与 DB 状态）；本层留任业务侧是合法组合根（run 在途事实在业务侧，分区
 * 规则只禁反向依赖），不跨域读 base 的休眠开关——开关已内移休眠扫描入口
 * （{@link WorkspaceHibernationAppService#scanOnce} 自持）。快照查看会话清扫随
 * #628「查看当时」快照容器拆除退役。
 *
 * <p>节奏：默认每 5 分钟一轮（{@code app.workspace.hibernation-scan-interval}，
 * Duration 形如 {@code 5m}）；启动期对账一次（{@link ApplicationRunner}，首轮
 * scheduled 延迟一个间隔——启动扫描与定时轮不叠跑）。单实例部署不加锁，每轮
 * 全量幂等收敛。</p>
 */
@Component
@Slf4j
public class WorkspaceHibernationScheduler implements ApplicationRunner {

    private final WorkspaceHibernationAppService hibernationAppService;
    private final CodingRunTrack codingRunTrack;
    private final ProjectRepository projectRepository;

    public WorkspaceHibernationScheduler(WorkspaceHibernationAppService hibernationAppService,
            CodingRunTrack codingRunTrack,
            ProjectRepository projectRepository) {
        this.hibernationAppService = hibernationAppService;
        this.codingRunTrack = codingRunTrack;
        this.projectRepository = projectRepository;
    }

    /** 启动期对账一次（#171 AC）：与定时轮同一扫描——启动即收敛遗留漂移。 */
    @Override
    public void run(ApplicationArguments args) {
        scanRound();
    }

    /** 定时扫描轮：闲置休眠（业务事实随扫描递入 base）。 */
    @Scheduled(fixedDelayString = "${app.workspace.hibernation-scan-interval:5m}",
            initialDelayString = "${app.workspace.hibernation-scan-interval:5m}")
    public void scanRound() {
        LocalDateTime now = LocalDateTime.now();
        try {
            Map<Long, WorkspaceScanFact> facts = scanFacts();
            int hibernated = hibernationAppService.scanOnce(facts, facts.keySet(), now);
            if (hibernated > 0) {
                log.info("[hibernation] 扫描轮收口：休眠 {} 工作区", hibernated);
            }
        } catch (RuntimeException e) {
            // 尽力而为：本轮失败不炸定时器，下轮再收敛
            log.warn("[hibernation] 扫描轮未成（下轮再试）", e);
        }
    }

    /**
     * 项目域事实申报（{@link WorkspaceScanFact}）：全部项目逐行映射——run 在途
     * （恒活跃）与已生成（漂移收敛唤醒后拉应用）。归档与休眠正交（ADR-0016）：
     * 归档项目不筛，同一判定。键集＝消费方认领集（#488 在册失联判定的正本：
     * 项目行在的工作区即被认领，键集随扫描递入 base 分级处置）。
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
