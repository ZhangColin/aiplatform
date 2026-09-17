package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.event.ApplicationEventPublisher;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.workspace.application.event.PreviewReady;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PreviewReady 域事件 → preview-ready 通知补链（#183，#180 C 片）：唤醒/应用拉起
 * 完成在 REST 请求线程外，事件发布（exposePreview 短事务内）已由唤醒编排测试 verify
 * ——这里发布同款事件，验收「事件到达（经真 Spring 事件总线 + AFTER_COMMIT）→ 通知
 * 发射」的缺环，负载形状与 REST 预览成功路径一致（先例
 * {@code ProjectLifecycleAppServiceTest} 的 mock-verify 口径）；SSE 线格式不重复测
 * （{@code EventsControllerSseTest} 已覆盖）。
 */
@IntegrationTest
class PreviewReadyRelayTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EventsAppService eventsAppService;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_project_workspace_when_preview_ready_then_notification_same_shape_as_rest_path() {
        Long projectId = persistedProject("9600");

        // 与 exposePreview 同款发布形态：短事务内发域事件（AFTER_COMMIT 送达监听器）
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishApplicationEvent(
                PreviewReady.of(WorkspaceId.of("9600"),
                        URI.create("http://ws-9600.localhost"))));

        verify(eventsAppService).publishNotification(eq(ProjectEventTypes.PREVIEW_READY),
                argThat(payload -> projectId.toString().equals(payload.get("projectId"))
                        && "http://ws-9600.localhost".equals(payload.get("url"))));
    }

    @Test
    void given_orphan_workspace_when_preview_ready_then_no_notification() {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishApplicationEvent(
                PreviewReady.of(WorkspaceId.of("9699"), URI.create("http://ws-9699.localhost"))));

        verifyNoInteractions(eventsAppService);
    }

    @Test
    void given_publish_failure_when_preview_ready_then_swallowed_not_propagated() {
        persistedProject("9601");
        doThrow(new RuntimeException("hub down")).when(eventsAppService)
                .publishNotification(any(), any());

        // 呈现面尽力而为：监听器在发布方线程内执行，异常反噬会把 REST 成功路径拖成
        // 5xx——转发失败只记日志，事务正常收口
        assertThatCode(() -> transactionTemplate.executeWithoutResult(
                status -> eventPublisher.publishApplicationEvent(
                        PreviewReady.of(WorkspaceId.of("9601"), URI.create("http://ws-9601.localhost")))))
                .doesNotThrowAnyException();
    }

    // ---------- 测试数据 ----------

    private Long persistedProject(String workspaceId) {
        Project project = projectRepository.save(Project
                .create("转发对象", ProjectType.WEBSITE,
                        Long.parseLong(workspaceId), null));
        return project.getId();
    }
}
