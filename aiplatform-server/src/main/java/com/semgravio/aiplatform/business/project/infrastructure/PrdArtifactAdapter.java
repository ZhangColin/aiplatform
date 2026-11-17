package com.semgravio.aiplatform.business.project.infrastructure;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.project.application.InterviewQuotaAppService;
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
    private final InterviewQuotaAppService interviewQuota;

    public PrdArtifactAdapter(ProjectRepository projectRepository,
            EventsAppService eventsAppService,
            TransactionTemplate transactionTemplate,
            InterviewQuotaAppService interviewQuota) {
        this.projectRepository = projectRepository;
        this.eventsAppService = eventsAppService;
        this.transactionTemplate = transactionTemplate;
        this.interviewQuota = interviewQuota;
    }

    public String workspacePath() {
        return ProjectArtifacts.PRD;
    }

    /**
     * savePrd 提前收口准入缝（2026-11-17 用户裁决：五问＝对用户明示的承诺）：
     * 试用访谈期未答满五问且无用户收敛令（fastLane attest）→ 返回模型可见拒因；
     * 问满 / 付费态 / 非访谈期 → null 放行。工作区无项目（防御面）不拦。判定经
     * {@link InterviewQuotaAppService#earlyClosureBlockReason} 单点（chip/收口
     * 指令/本闸三读面同源）。读短事务——工具执行线程内同步调用。
     */
    public String earlyClosureBlockReason(String workspaceId, boolean convergenceCommanded) {
        Long projectId = transactionTemplate.execute(tx ->
                projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                        .map(Project::getId)
                        .orElse(null));
        if (projectId == null) {
            return null;
        }
        return interviewQuota.earlyClosureBlockReason(projectId, convergenceCommanded);
    }

    public void onWritten(String workspaceId) {
        Project project = transactionTemplate.execute(tx -> {
            Project loaded = projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                    .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
            loaded.markPrdProduced();
            return projectRepository.save(loaded);
        });
        eventsAppService.publishNotification(ProjectEventTypes.DOCUMENT_UPDATED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                ProjectEventTypes.DOCUMENT_TYPE_FIELD, ProjectEventTypes.DOCUMENT_TYPE_PRD,
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId()),
                        EventsAppService.OWNER_GUEST_FIELD,
                        EventsAppService.ownerPayload(project.getOwnerGuestId())));
    }
}
