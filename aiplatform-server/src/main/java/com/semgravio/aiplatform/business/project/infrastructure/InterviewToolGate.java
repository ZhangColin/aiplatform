package com.semgravio.aiplatform.business.project.infrastructure;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.semgravio.aiplatform.business.project.application.InterviewQuotaAppService;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 访谈工具侧额度闸（2026-11-17，ADR-0048「程序计数权威」的工具点对偶）：业务工具按
 * workspaceId 装配（{@code AgentWorkspace.ProjectReadOnly#workspaceId()}），闸判定在
 * application 层按 projectId——本组件是唯一的 workspaceId → projectId 短事务解析点，
 * 提问/产出两缝同归此处，与「第 N/5」chip、收口指令同读
 * {@link InterviewQuotaAppService} 单源：
 *
 * <ul>
 *   <li><b>提问侧</b>（ask_user 工具自检）：{@link #questionBlockReason}——五问答满后
 *       漂移提问在工具点拦下（PASSTHROUGH 回错误结果，不挂起、不出问答卡、不发
 *       question-raised），而非等轮落定后由收口纠偏链平台代答（旧形态：第 6 问先
 *       出卡给用户再被自问自答，实测项目 381073661124370156）；</li>
 *   <li><b>产出侧</b>（savePrd 工具执行体）：{@link #earlyClosureBlockReason}——未答满
 *       五问且无收敛令的提前产出被拒（#549 五问承诺闸，原 PrdArtifactAdapter 缝迁入）。</li>
 * </ul>
 *
 * <p>工作区无项目（防御面：本地工作区/置备竞态）不拦——两缝均返回 null 放行。
 * 读短事务：工具执行/权限自检线程内同步调用。</p>
 */
@Component
public class InterviewToolGate {

    private final ProjectRepository projectRepository;
    private final TransactionTemplate transactionTemplate;
    private final InterviewQuotaAppService interviewQuota;

    public InterviewToolGate(ProjectRepository projectRepository,
            TransactionTemplate transactionTemplate,
            InterviewQuotaAppService interviewQuota) {
        this.projectRepository = projectRepository;
        this.transactionTemplate = transactionTemplate;
        this.interviewQuota = interviewQuota;
    }

    /**
     * 提问侧闸（ask_user）：五问答满 → 模型可见拒因（不挂起）；未问满 / 付费态 /
     * 非访谈期 → null 放行。
     */
    public String questionBlockReason(String workspaceId) {
        Long projectId = resolveProjectId(workspaceId);
        return projectId == null ? null : interviewQuota.exhaustedBlockReason(projectId);
    }

    /**
     * 产出侧闸（savePrd）：未答满五问且无收敛令 → 模型可见拒因（不落盘不置位）；
     * 问满 / 收敛令 / 付费态 / 非访谈期 → null 放行。
     */
    public String earlyClosureBlockReason(String workspaceId, boolean convergenceCommanded) {
        Long projectId = resolveProjectId(workspaceId);
        return projectId == null ? null
                : interviewQuota.earlyClosureBlockReason(projectId, convergenceCommanded);
    }

    /** workspaceId → projectId（短事务只读解析；无项目返回 null——防御面不拦）。 */
    private Long resolveProjectId(String workspaceId) {
        return transactionTemplate.execute(tx ->
                projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                        .map(Project::getId)
                        .orElse(null));
    }
}
