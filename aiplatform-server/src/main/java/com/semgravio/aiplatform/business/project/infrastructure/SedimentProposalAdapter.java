package com.semgravio.aiplatform.business.project.infrastructure;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.knowledge.application.SedimentDraftAppService;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftProposal;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftReceipt;
import com.semgravio.aiplatform.business.project.application.SedimentProposalFacts;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 沉淀自荐落库适配器（propose_sediment 工具的业务效果半边，照
 * {@link SkillProposalAdapter} 先例——工具不触库、经适配器过业务）：按工作区
 * 寻址来源项目（血统锚），转调 knowledge 域用例落采集层草稿；同时把「本轮提过
 * 沉淀」的工具调用事实登记进 {@link SedimentProposalFacts}（轮收口轻兜底盘问的
 * 判定依据——登记在每次调用即发生，受理与拒因同记账：模型试过就不再盘问）。
 * base.knowledge 不反依赖 business——项目解析归业务侧适配。来源项目查无（项目
 * 已删、容器残留 run 的边缘）不抛：回执拒因让智能体放弃本次写入（自荐无处归属），
 * 亦不记账。
 */
@Component
public class SedimentProposalAdapter {

    private final ProjectRepository projectRepository;
    private final SedimentDraftAppService sedimentDrafts;
    private final SedimentProposalFacts proposeFacts;

    public SedimentProposalAdapter(ProjectRepository projectRepository,
            SedimentDraftAppService sedimentDrafts, SedimentProposalFacts proposeFacts) {
        this.projectRepository = projectRepository;
        this.sedimentDrafts = sedimentDrafts;
        this.proposeFacts = proposeFacts;
    }

    public SedimentDraftReceipt propose(String workspaceId, String runId, String slotKey,
            String kind, String title, String summary, String content) {
        return projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                .map(project -> proposeFor(project, workspaceId, runId, slotKey,
                        kind, title, summary, content))
                .orElse(SedimentDraftReceipt.rejected("来源项目不存在（工作区 " + workspaceId
                        + " 无对应项目）——自荐无处归属，放弃本次写入。"));
    }

    private SedimentDraftReceipt proposeFor(Project project, String workspaceId, String runId,
            String slotKey, String kind, String title, String summary, String content) {
        // 调用事实先行登记：受理与拒因同记账（模型试过就不盘问——回执已给原因）
        proposeFacts.record(workspaceId, slotKey);
        return sedimentDrafts.propose(new SedimentDraftProposal(
                kind, title, summary, content, project.getId(), runId, slotKey));
    }
}
