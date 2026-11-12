package com.semgravio.aiplatform.business.project.application;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceReclaimAppService;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.ThreeCleanCheck;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeReclaimResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;
import com.semgravio.aiplatform.support.Tsid;

import lombok.extern.slf4j.Slf4j;

/**
 * 后台一键回收组合面（#490，ADR-0045「一键回收」——运维通道，机机签名，不经
 * 用户会话凭证：账户属主项目无凭证删不动的缺口就此覆盖，不再裸操改挂属主）：
 * <ul>
 * <li><b>项目级</b>：删除＋清残＋三清报告一次完成——复用 {@link ProjectLifecycleAppService}
 * 删除级联内核（受理即删：在飞 run 先级联停止〔承 #477〕→ 工作区销毁〔#485
 * 终态一致内核互斥＋三清校验，事实交回〕→ prj_* 级联清理），非另写一套；</li>
 * <li><b>工作区级</b>：单台工作区三清，项目其余部分不动——在册失联行
 * （#488 lostAt 标记）的人工裁决出口；业务侧事实（所属项目、在飞 run）在本层
 * 换算递入 base 回收面（照 {@code BackofficeWorkspaceAppService} 组合先例）。</li>
 * </ul>
 *
 * <p>append-only 留痕（{@code wsp_reclaim_actions}，照 #489 {@code wsp_sweep_actions}
 * 先例）：操作者＋四面/三面事实全量落档；成功落痕、未成不留痕（销毁/级联失败
 * 上抛 500——管理员重试是新一行动作）。</p>
 */
@Service
@Slf4j
public class BackofficeReclaimAppService {

    private final ProjectLifecycleAppService projectLifecycle;
    private final ProjectRepository projectRepository;
    private final CodingRunTrack codingRunTrack;
    private final WorkspaceReclaimAppService workspaceReclaim;

    public BackofficeReclaimAppService(ProjectLifecycleAppService projectLifecycle,
            ProjectRepository projectRepository, CodingRunTrack codingRunTrack,
            WorkspaceReclaimAppService workspaceReclaim) {
        this.projectLifecycle = projectLifecycle;
        this.projectRepository = projectRepository;
        this.codingRunTrack = codingRunTrack;
        this.workspaceReclaim = workspaceReclaim;
    }

    /**
     * 项目级一键回收（#490）：在飞停止 → 销毁三清 → 真删级联 → 四面报告＋留痕
     * （级联内核与用户面 DELETE 共用——受理即删语义不变，差异仅在回执与留痕）。
     * 无会话凭证属主项目照收（运维通道不查归属——签名面担保调用应用）。
     *
     * @throws ApplicationException PRJ_001 项目不存在（404）
     */
    public BackofficeReclaimResponse reclaimProject(Long projectId, Operator operator,
            LocalDateTime now) {
        ProjectLifecycleAppService.ReclaimFacts facts = projectLifecycle
                .deleteAndReport(projectId);
        workspaceReclaim.recordProjectReclaim(projectId, facts.workspaceId(),
                facts.runsStopped(), facts.threeClean(), facts.projectRowClean(),
                operator, now);
        log.info("[reclaim] 项目 {}（工作区 {}）项目级回收收口（操作者={}）：四面零残留={}",
                projectId, facts.workspaceId(),
                operator != null ? operator.id() : "（缺操作者头）",
                facts.threeClean().allClean() && facts.projectRowClean());
        return BackofficeReclaimResponse.projectOf(projectId, facts.workspaceId(),
                facts.runsStopped(), facts.threeClean(), facts.projectRowClean(),
                operator, now);
    }

    /**
     * 工作区级一键回收（#490）：单台工作区三清（#485 内核销毁＋三清事实交回＋
     * 留痕），项目行与其余资产不动。所属项目有在飞 run 先级联停止（承 #477——
     * 不留僵尸任务对着已删容器自救）；无所属（在册失联裁决 #488/fork）无停止面
     * 可挂，runsStopped 如实 false。
     *
     * @throws ApplicationException WSP_001 工作区不存在（含畸形 id，404——寻址
     *                              先解析后动手，销毁内核对「在册行不在」同码兜底）
     */
    public BackofficeReclaimResponse reclaimWorkspace(String workspaceId, Operator operator,
            LocalDateTime now) {
        // 寻址先解析（畸形 id 即刻 404，先于任何停止/销毁副作用——一次解析贯穿
        // 所属项目换算与回执，不再沿途重复 parse）
        long id = Tsid.resolve(workspaceId, WorkspaceMessage.WORKSPACE_NOT_FOUND);
        Optional<Project> project = projectRepository.findByWorkspaceId(id);
        boolean runsStopped = project
                .map(value -> codingRunTrack.requestStop(value.getId()))
                .orElse(false);
        ThreeCleanCheck check = workspaceReclaim.reclaimWorkspace(workspaceId,
                project.map(Project::getId).orElse(null), runsStopped, operator, now);
        log.info("[reclaim] 工作区 {} 工作区级回收收口（操作者={}、所属项目={}）：三面全清={}",
                workspaceId, operator != null ? operator.id() : "（缺操作者头）",
                project.map(value -> value.getId().toString()).orElse("（无所属）"),
                check.allClean());
        return BackofficeReclaimResponse.workspaceOf(id,
                project.map(Project::getId).orElse(null), runsStopped, check, operator, now);
    }
}
