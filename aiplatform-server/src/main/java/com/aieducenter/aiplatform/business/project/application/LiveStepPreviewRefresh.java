package com.aieducenter.aiplatform.business.project.application;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.base.eventhub.application.PlatformNotificationAppService;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 直播步骤边界驱动的预览刷新（#49 逐修改刷新）：刷新单元 = 一次完整修改——以编码
 * run 的 {@code live-step} 帧（每次模型调用≈一次完整修改）为刷新信号，收到即
 * 平台侧探活工作区应用端口（8081，与生成收口核验同判据 {@link GenerationAppService#CLOSING_PROBE}），
 * <b>探活通过才发射 {@code preview-updated} 通知</b>（前端节流重载预览）；未通过
 * 不发射——前端保持最后好状态，不闪断。生成与修正同一口径（挂在共用尝试环的
 * 流桥 sink 上，见 {@link CoderRunAttempts}）。
 *
 * <p>{@code live-step} 的 {@code step=1} 是起跑边界（尚无完整修改），不算刷新信号；
 * 末步完成由 run-finish 收口重挂兜底。探针不判内容只判可访问（HTTP 有应答即可），
 * 中间态报错的兜底归编码智能体自愈循环（CONTEXT.md「预览」）。</p>
 *
 * <p>探活在专职单线程上异步执行：sink 在流消费线程（reactor）上被调，docker exec
 * 是慢操作不能阻塞流（单线程兼得步骤序——探活按边界顺序出结果）；探活失败与
 * 发射失败都不是故障（debug 记日志）——刷新通知是「让 UI 活」的面，不承担正确性，
 * 更不能反向炸编码 run。</p>
 */
@Component
@Slf4j
class LiveStepPreviewRefresh implements DisposableBean {

    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final PlatformNotificationAppService notificationAppService;
    /** 探活专职单线程（daemon）：FIFO 保步骤序；容量无界——每步至多一探，节奏天然稀疏。 */
    private final ExecutorService probeWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "preview-step-probe");
        thread.setDaemon(true);
        return thread;
    });

    LiveStepPreviewRefresh(WorkspaceLifecycleAppService workspaceLifecycleAppService,
            PlatformNotificationAppService notificationAppService) {
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.notificationAppService = notificationAppService;
    }

    /**
     * 装饰编码 run 的流桥 sink：帧原样透传（发射照旧），直播步骤边界（完整修改
     * 落定）另触发异步「探活 → 通过才发刷新通知」。
     */
    Consumer<AgentEvent> decorate(Long projectId, Long workspaceId, Consumer<AgentEvent> sink) {
        return event -> {
            sink.accept(event);
            if (completedModification(event)) {
                probeWorker.execute(() -> probeAndNotify(projectId, workspaceId));
            }
        };
    }

    /** 完整修改落定判据：live-step 且 step≥2（第 N 帧到达 = 第 N-1 次模型调用已完整结束）。 */
    private static boolean completedModification(AgentEvent event) {
        if (!AgentEventTypes.LIVE_STEP.equals(event.type())) {
            return false;
        }
        return event.payload().get(AgentEventTypes.LIVE_STEP_FIELD) instanceof Number step
                && step.intValue() >= 2;
    }

    /** 探活 → 通过才发射（失败只记日志：待期是常态——应用可访问前每步都会未过）。 */
    private void probeAndNotify(Long projectId, Long workspaceId) {
        try {
            ExecResultResponse result = workspaceLifecycleAppService.exec(
                    Long.toString(workspaceId),
                    new WorkspaceExecCommand(GenerationAppService.CLOSING_PROBE));
            if (result.exitCode() != 0) {
                log.debug("[preview-refresh] 项目 {} 步骤边界探活未过（exitCode {}），不发射刷新通知",
                        projectId, result.exitCode());
                return;
            }
            notificationAppService.publish(ProjectEventTypes.PREVIEW_UPDATED, Map.of(
                    ProjectEventTypes.PROJECT_ID_FIELD, projectId.toString()));
        }
        catch (RuntimeException e) {
            log.debug("[preview-refresh] 项目 {} 步骤边界探活异常（不发射刷新通知）：{}",
                    projectId, e.toString());
        }
    }

    @Override
    public void destroy() {
        probeWorker.shutdownNow();
    }
}
