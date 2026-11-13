package com.semgravio.aiplatform.base.workspace.application;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceReclaimAction;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.ThreeCleanCheck;
import com.semgravio.aiplatform.base.workspace.domain.repository.WorkspaceReclaimActionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 一键回收留痕面（#490，ADR-0045「一键回收」base 侧执行体）：销毁内核复用
 * {@link WorkspaceLifecycleAppService#destroyForReclaim}（#485 互斥＋三清校验，
 * 非另写一套），本面组「销毁事实 → append-only 留痕行」。在飞 run 级联停止
 * （承 #477 停止面）是 business 侧事实，由组合方
 * （{@code BackofficeReclaimAppService}）先行置停再把事实递入（照
 * {@code WorkspaceScanFact} 先例，base 不反向依赖 business）。
 *
 * <p>留痕口径（照 #489 {@code wsp_sweep_actions}／#174 {@code WorkspaceAction}
 * 先例）：<b>成功落痕、未成不留痕</b>——销毁失败上抛（500），不落行不定格；
 * 操作者两列缺透传头落空 NULL。项目级回收的删除级联（prj_* 行/对话史/知识）
 * 归组合方执行，完成后事实递入 {@link #recordProjectReclaim} 落痕。</p>
 */
@Service
@Slf4j
public class WorkspaceReclaimAppService {

    private final WorkspaceLifecycleAppService lifecycle;
    private final WorkspaceReclaimActionRepository actionRepository;

    public WorkspaceReclaimAppService(WorkspaceLifecycleAppService lifecycle,
            WorkspaceReclaimActionRepository actionRepository) {
        this.lifecycle = lifecycle;
        this.actionRepository = actionRepository;
    }

    /**
     * 工作区级一键回收（#490）：销毁（#485 内核，WSP_001 寻址守卫在其内）→
     * 三清事实交回＋留痕。单台工作区三清，项目行与其余资产不动（在册失联行的
     * 人工裁决出口——标记行随注册行 FK 级联消亡，#488）。
     *
     * @param workspaceId 工作区标识（TSID 十进制）
     * @param projectId   所属项目锚（组合方递入，可空——失联裁决/fork 无所属；
     *                    仅落痕审计，不动项目面）
     * @param runsStopped 组合方递入的停止事实（所属项目有在飞 run 被置停＝true；
     *                    无所属/无在飞 false——fork 无项目锚时在途切片任务随容器
     *                    消亡自错，不在此强停）
     * @param operator    操作者（透传头；缺头 null 落空口径）
     * @param now         回收时刻
     * @return 三清事实（回收报告的事实源——UNKNOWN 不算清）
     */
    public ThreeCleanCheck reclaimWorkspace(String workspaceId, Long projectId,
            boolean runsStopped, Operator operator, LocalDateTime now) {
        ThreeCleanCheck check = lifecycle.destroyForReclaim(workspaceId);
        // 留痕锚解析：销毁内核成功收口即已证 workspaceId 可解析（WSP_001 守卫在其内）
        actionRepository.save(WorkspaceReclaimAction.workspaceScope(
                Long.parseLong(workspaceId), projectId, runsStopped, check, operator, now));
        log.info("[workspace] {} 工作区级回收收口（所属项目={}、操作者={}、在飞停止={}）：三清全清={}",
                workspaceId, projectId != null ? projectId : "（无所属）",
                operator != null ? operator.id() : "（缺操作者头）",
                runsStopped, check.allClean());
        return check;
    }

    /**
     * 项目级回收留痕（#490）：组合方（business）完成「在飞停止 → 销毁 → 删除
     * 级联」后把四面事实递入落痕（append-only 行即「删后四面零残留与否」的审计
     * 事实；未成上抛路径不落行）。
     *
     * @param projectId      回收的项目
     * @param workspaceId    项目的主工作区（三清对象）
     * @param runsStopped    在飞 run 级联停止事实
     * @param check          三清事实（销毁内核交回；销毁未全成＝unprobed）
     * @param projectRowClean 删除级联后项目行已清否（四面之一）
     * @param operator       操作者（缺头 null 落空口径）
     * @param now            回收时刻
     */
    public void recordProjectReclaim(Long projectId, Long workspaceId, boolean runsStopped,
            ThreeCleanCheck check, boolean projectRowClean, Operator operator,
            LocalDateTime now) {
        WorkspaceReclaimAction trace = actionRepository.save(WorkspaceReclaimAction.projectScope(
                projectId, workspaceId, runsStopped, check, projectRowClean, operator, now));
        log.info("[workspace] 项目 {}（工作区 {}）项目级回收留痕：四面全清={}、在飞停止={}",
                projectId, workspaceId, trace.allClean(), runsStopped);
    }
}
