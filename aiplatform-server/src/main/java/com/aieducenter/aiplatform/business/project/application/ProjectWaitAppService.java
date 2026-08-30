package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.application.AgentStreamAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentTaskAppService;
import com.aieducenter.aiplatform.base.agentengine.application.AgentWaitAppService;
import com.aieducenter.aiplatform.base.agentengine.application.dto.command.WaitSettleCommand;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.SettleResult;
import com.aieducenter.aiplatform.base.agentengine.application.dto.response.WaitPointResponse;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitKind;
import com.aieducenter.aiplatform.base.agentengine.domain.enums.WaitOutcome;
import com.aieducenter.aiplatform.base.agentengine.domain.model.AgentEventTypes;
import com.aieducenter.aiplatform.business.project.application.dto.command.ProjectWaitSettleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectWaitResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 项目等待点桥接（片2b 端口的项目视角，A1 §1 口子①）：列等待点/答复（问答/
 * 权限）按项目寻址转底座等待点通道；答复成功后发射 {@code wait-settled}（编排层
 * 发射制：副作用真实落定后）。校验链（404/409/deny cap）全在底座，本层只做
 * 寻址映射与 SSE 桥接——「对话建项目」的问答与开发平台共用同一套 wait 语义
 * （A3 §5，零新增机制）。
 */
@Service
@Slf4j
public class ProjectWaitAppService {

    private final ProjectRepository projectRepository;
    private final AgentWaitAppService agentWaitAppService;
    private final AgentTaskAppService agentTaskAppService;
    private final AgentStreamAppService streamAppService;
    private final ProjectKnowledgeAppService knowledgeAppService;

    public ProjectWaitAppService(ProjectRepository projectRepository,
                                 AgentWaitAppService agentWaitAppService,
                                 AgentTaskAppService agentTaskAppService,
                                 AgentStreamAppService streamAppService,
                                 ProjectKnowledgeAppService knowledgeAppService) {
        this.projectRepository = projectRepository;
        this.agentWaitAppService = agentWaitAppService;
        this.agentTaskAppService = agentTaskAppService;
        this.streamAppService = streamAppService;
        this.knowledgeAppService = knowledgeAppService;
    }

    /**
     * 项目的待处理等待点（跨会话聚合，新→旧；「待我处理」列表的数据源之一）。
     */
    public List<ProjectWaitResponse> pendingWaits(Long projectId) {
        Project project = requireProject(projectId);
        return agentWaitAppService
                .pendingWaits(Long.toString(project.getWorkspaceId())).stream()
                .map(ProjectWaitAppService::toResponse)
                .toList();
    }

    /**
     * 答复等待点（问答答复 / 权限批准或拒绝）：转底座 settle（校验链
     * 404/409、引擎送达；deny cap 判定在结果回报），成功后发 SSE wait-settled。
     *
     * <p>deny cap 接续（票 #38）：底座回报达 cap 时经 {@link AgentTaskAppService}
     * 的 terminateRun（与 cancelRun 共用路径）执行 abort + 收口 + 平台终态帧——
     * 在本层 settle 帧之后调用，保住帧序 wait-settled × N → task-finish(cancelled)
     * 最后落地（前端 wait-settled 一律把 run 拉回 running）。</p>
     */
    public void settle(Long projectId, String waitId, ProjectWaitSettleCommand command) {
        Project project = requireProject(projectId);
        String workspaceId = Long.toString(project.getWorkspaceId());
        SettleResult result = agentWaitAppService.settle(workspaceId,
                waitId, new WaitSettleCommand(command.type(), command.answers(),
                        command.approve(), command.note()));

        // SSE（副作用落定后：settle 成功才发）；关闭结果缺失的异常形态只记日志跳过
        WaitPointResponse settled = result.settled();
        if (settled.settleOutcome() == null) {
            log.warn("等待点 {} 答复后无关闭结果，跳过 SSE 发射", waitId);
            return;
        }
        emitWaitSettled(projectId, toResponse(settled));

        // A5 §1 QA 摄取（settle(Answer) 编排处即刻，失败降级不炸）：问答对的
        // 问题（body）与答复（answers）成纪要素材；权限答复不摄取
        if (settled.kind() == WaitKind.QUESTION
                && settled.settleOutcome() == WaitOutcome.ANSWERED) {
            knowledgeAppService.indexQa(projectId, waitId, settled.body(),
                    settled.summary(), command.answers());
        }

        if (result.denyCapped()) {
            agentTaskAppService.terminateRun(workspaceId, result.engine(),
                    settled.sessionId(), settled.runId(),
                    Map.of(AgentStreamAppService.PROJECT_FIELD, Long.toString(projectId)));
        }
    }

    // ---------- 内部 ----------

    private void emitWaitSettled(Long projectId, ProjectWaitResponse settled) {
        if (settled.runId() == null || settled.runId().isBlank()) {
            log.warn("等待点 {} 无关联运行，跳过 wait-settled 发射（agent 流 payload 必带 runId）",
                    settled.waitId());
            return;
        }
        streamAppService.publish(AgentEventTypes.WAIT_SETTLED, Map.of(
                AgentStreamAppService.PROJECT_FIELD, projectId.toString(),
                AgentStreamAppService.RUN_FIELD, settled.runId(),
                AgentEventTypes.WAIT_ID_FIELD, settled.waitId(),
                AgentEventTypes.WAIT_OUTCOME_FIELD,
                outcomeOf(settled.settleOutcome())));
    }

    /** 底座 WaitPointResponse → 项目视角投影（projectId 来自路径不重复携带；
     *  *Name 随附由 record 紧凑构造器从枚举派生）。 */
    private static ProjectWaitResponse toResponse(WaitPointResponse wait) {
        return new ProjectWaitResponse(wait.waitId(), wait.kind(), null,
                wait.status(), null, wait.summary(), wait.sessionId(),
                wait.runId(), wait.engineRef(), wait.body(), wait.settleOutcome(),
                null, wait.raisedAt(), wait.settledAt());
    }

    private static String outcomeOf(WaitOutcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT); // answered/approved/denied
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }
}
