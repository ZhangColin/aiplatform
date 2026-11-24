package com.semgravio.aiplatform.business.project.infrastructure;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.project.application.ProjectEventTypes;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * PRD 落盘业务登记（savePrd 回调侧）：置「PRD 已产出」状态位（prdProducedAt——
 * 每次写出刷新）＋发 document-updated 通知。<b>#542 首建派发门（ADR-0048/0049）
 * ——首产自动派发整段退役</b>：#462 的「首次产 PRD 即派首个构建 run」回调
 * （dispatchFirstDemoOnPrdProduced）删除，PRD 落盘只登记事实——首个构建的点火
 * 归用户（试用态岔路口「立即生成」/付费轨 PRD 确认/快道直派收口观测），全域无
 * 无条件自动路径；本适配器不再依赖生成编排（#462 为破环引入的 {@code @Lazy}
 * 回连随之消解）。
 *
 * <p>SSE 事务提交后发射（编排层发射制，ADR-0001）：置位短事务先行，事件随后；
 * 置位失败抛出——工具回失败结果，模型可再次保存重试（写文件幂等覆盖）。</p>
 */
@Component
@Slf4j
public class PrdArtifactAdapter {

    private final ProjectRepository projectRepository;
    private final EventsAppService eventsAppService;
    private final TransactionTemplate transactionTemplate;

    public PrdArtifactAdapter(ProjectRepository projectRepository,
            EventsAppService eventsAppService,
            TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.eventsAppService = eventsAppService;
        this.transactionTemplate = transactionTemplate;
    }

    public String workspacePath() {
        return ProjectArtifacts.PRD;
    }

    public void onWritten(String workspaceId) {
        Project project = transactionTemplate.execute(tx -> {
            Project loaded = projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                    .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
            loaded.markPrdProduced();
            return projectRepository.save(loaded);
        });
        eventsAppService.publishNotification(ProjectEventTypes.DOCUMENT_UPDATED,
                ProjectEventTypes.documentUpdatedPayload(project,
                        ProjectEventTypes.DOCUMENT_TYPE_PRD));
    }
}
