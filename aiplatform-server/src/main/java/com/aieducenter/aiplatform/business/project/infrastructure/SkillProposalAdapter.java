package com.aieducenter.aiplatform.business.project.infrastructure;

import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.skills.application.SkillDraftAppService;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftProposal;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftReceipt;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 自荐落库适配器（propose_skill 工具的业务效果半边，照 {@link PrdArtifactAdapter}
 * 先例——工具不触库、经适配器过业务）：按工作区寻址来源项目（血统锚），转调
 * skills 域用例落草稿。base.skills 不反依赖 business——项目解析归业务侧适配。
 * 来源项目查无（项目已删、容器残留 run 的边缘）不抛：回执拒因让智能体放弃
 * 本次写入（自荐无处归属）。
 */
@Component
public class SkillProposalAdapter {

    private final ProjectRepository projectRepository;
    private final SkillDraftAppService skillDrafts;

    public SkillProposalAdapter(ProjectRepository projectRepository,
            SkillDraftAppService skillDrafts) {
        this.projectRepository = projectRepository;
        this.skillDrafts = skillDrafts;
    }

    public SkillDraftReceipt propose(String workspaceId, String runId, SkillSlot slot,
            String name, String description, String content) {
        return projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                .map(project -> skillDrafts.propose(new SkillDraftProposal(
                        name, description, content, project.getId(), runId, slot)))
                .orElse(SkillDraftReceipt.rejected("来源项目不存在（工作区 " + workspaceId
                        + " 无对应项目）——自荐无处归属，放弃本次写入。"));
    }
}
