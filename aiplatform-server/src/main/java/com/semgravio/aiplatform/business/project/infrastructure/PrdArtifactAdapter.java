package com.semgravio.aiplatform.business.project.infrastructure;

import java.util.Map;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.project.application.GenerationAppService;
import com.semgravio.aiplatform.business.project.application.ProjectEventTypes;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.ProjectArtifacts;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * PRD 产物登记（savePrd 工具的业务效果半边）：路径正本 = {@link ProjectArtifacts#PRD}
 * （PRD 读端点同源）；落盘成功回调 = 按工作区寻址项目置「PRD 已产出」状态位
 * （成果区长出判据）+ 发 document-updated（前端失效为主消费）。
 *
 * <p><b>首 Demo 触发平移（#462 去确认门，ADR-0039）</b>：prdProducedAt <b>首次</b>
 * 置位（首次产 PRD）即触发首个构建 run 派发——原「确认此版 PRD 并生成 Demo」的
 * 人确认动作整段退役，触发平移到 savePrd 落盘回调侧（判定跟职责走：产出动作的发
 * 生地即派发触发点，收口观测 {@code MainAgentAppService} 再兜一轮）。派发
 * best-effort：失败只记日志不反噬 savePrd（落库已成立，收口观测/「继续生成」
 * 兜底）。注意 markPrdProduced 随每次 savePrd 刷新——「首次」判定取置位前的
 * 锚为空（pre-check），修订再存不重复触发。</p>
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
    private final GenerationAppService generationAppService;

    /**
     * @param generationAppService 首 Demo 派发口（{@link GenerationAppService#
     *        dispatchFirstDemoOnPrdProduced}）。{@code @Lazy} 破环：生成编排经
     *        ProfileToolkitSupplier→本适配器（SavePrdTool 注册链）回连本依赖，
     *        与 GenerationAppService 经 CoderRunAttempts→AgentClient 的链成环
     *        （#462 首 Demo 触发平移新增方向）——环只此一处、以懒代理收口，
     *        行为仍是进程内直调。
     */
    public PrdArtifactAdapter(ProjectRepository projectRepository,
            EventsAppService eventsAppService,
            TransactionTemplate transactionTemplate,
            @Lazy GenerationAppService generationAppService) {
        this.projectRepository = projectRepository;
        this.eventsAppService = eventsAppService;
        this.transactionTemplate = transactionTemplate;
        this.generationAppService = generationAppService;
    }

    public String workspacePath() {
        return ProjectArtifacts.PRD;
    }

    public void onWritten(String workspaceId) {
        boolean[] firstProduced = {false};
        Project project = transactionTemplate.execute(tx -> {
            Project loaded = projectRepository.findByWorkspaceId(Long.parseLong(workspaceId))
                    .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
            firstProduced[0] = loaded.getPrdProducedAt() == null;
            loaded.markPrdProduced();
            return projectRepository.save(loaded);
        });
        eventsAppService.publishNotification(ProjectEventTypes.DOCUMENT_UPDATED, Map.of(
                ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                ProjectEventTypes.DOCUMENT_TYPE_FIELD, ProjectEventTypes.DOCUMENT_TYPE_PRD,
                EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId()),
                        EventsAppService.OWNER_GUEST_FIELD,
                        EventsAppService.ownerPayload(project.getOwnerGuestId())));
        // 首 Demo 触发平移（#462）：首次产 PRD 即派首个构建 run（去确认门）——
        // 事务外 best-effort：派发失败不反噬 savePrd（收口观测/「继续生成」兜底）
        if (firstProduced[0]) {
            try {
                generationAppService.dispatchFirstDemoOnPrdProduced(project.getId());
            }
            catch (RuntimeException e) {
                log.warn("[prd] 项目 {} 首次产 PRD 但首个 Demo 派发失败"
                        + "（不反噬 savePrd，收口观测/继续生成兜底）：{}", project.getId(), e.toString());
            }
        }
    }
}
