package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.workspace.application.event.PreviewReady;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * PreviewReady 域事件 → preview-ready 通知转发（#183，#180 C 片）：唤醒/应用拉起在
 * REST 请求线程外完成，就绪前端无从得知——补上 PreviewReady 事件的首个监听
 * （AFTER_COMMIT：就绪事实已随事务落定），workspaceId 反查项目后转经通知族 SSE 发出，
 * 负载与 REST 预览成功路径同形（projectId + url，前端既有处理零改动）。REST 预览与
 * 生成收口路径仍各自显式发射（同形幂等，多一次到达无害）。
 *
 * <p>呈现面尽力而为（{@code AgentEventBridge} 护栏同款）：孤儿工作区（无项目记录）
 * 或发射失败只记日志不上抛——监听在发布方线程内于提交后执行，异常会反噬
 * {@code exposePreview} 调用方（REST 成功路径会被拖成 5xx）；SSE 是「让 UI 活」的
 * 面，不承担正确性。</p>
 */
@Component
@Slf4j
public class PreviewReadyRelay {

    private final ProjectRepository projectRepository;
    private final EventsAppService eventsAppService;

    public PreviewReadyRelay(ProjectRepository projectRepository, EventsAppService eventsAppService) {
        this.projectRepository = projectRepository;
        this.eventsAppService = eventsAppService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPreviewReady(PreviewReady event) {
        try {
            projectRepository.findByWorkspaceId(Long.parseLong(event.workspaceId().value()))
                    .ifPresentOrElse(project -> publishFor(project, event.url()),
                            () -> log.debug("[preview] 工作区 {} 无项目记录，preview-ready 不转发",
                                    event.workspaceId().value()));
        } catch (RuntimeException e) {
            log.warn("[preview] 工作区 {} preview-ready 转发失败（呈现面，不反噬发布方）：{}",
                    event.workspaceId().value(), e.getMessage());
        }
    }

    private void publishFor(Project project, URI url) {
        eventsAppService.publishNotification(ProjectEventTypes.PREVIEW_READY, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                ProjectEventTypes.URL_FIELD, url.toString()));
    }
}
