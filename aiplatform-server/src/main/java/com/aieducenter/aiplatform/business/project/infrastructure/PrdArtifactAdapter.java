package com.aieducenter.aiplatform.business.project.infrastructure;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectEventTypes;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * PRD 产物登记（savePrd 工具的业务效果半边）：路径正本 = {@link ProjectArtifacts#PRD}
 * （PRD 读端点同源）；落盘成功回调 = 按工作区寻址项目置「PRD 已产出」状态位
 * （成果区长出判据）+ 发 document-updated（前端失效为主消费）。
 *
 * <p>SSE 事务提交后发射（编排层发射制，ADR-0001）：置位短事务先行，事件随后；
 * 置位失败抛出——工具回失败结果，模型可再次保存重试（写文件幂等覆盖）。</p>
 */
@Component
public class PrdArtifactAdapter {

    private final ProjectRepository projectRepository;
    private final PlatformNotificationAppService notificationAppService;
    private final TransactionTemplate transactionTemplate;

    public PrdArtifactAdapter(ProjectRepository projectRepository,
            PlatformNotificationAppService notificationAppService,
            TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.notificationAppService = notificationAppService;
        this.transactionTemplate = transactionTemplate;
    }

    public String workspacePath() {
        return ProjectArtifacts.PRD;
    }

    public void onWritten(String workspaceId) {
        String projectId = transactionTemplate.execute(tx -> {
            Project project = projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                    .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
            project.markPrdProduced();
            projectRepository.save(project);
            return project.getId().toString();
        });
        notificationAppService.publish(ProjectEventTypes.DOCUMENT_UPDATED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, projectId,
                ProjectEventTypes.DOCUMENT_TYPE_FIELD, ProjectEventTypes.DOCUMENT_TYPE_PRD));
    }
}
