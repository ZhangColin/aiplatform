package com.semgravio.aiplatform.base.workspace.domain.aggregate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.base.workspace.domain.enums.ReclaimScope;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.ThreeCleanCheck;

/**
 * 一键回收动作留痕行（{@code wsp_reclaim_actions}，#490，ADR-0045「动刀类走
 * admin 手动＋留痕」）：后台 reclaim 每次触发一行——谁（操作者两列，照
 * {@link WorkspaceSweepAction} 先例：admin 侧标识，缺透传头落 NULL）、何时、
 * 回收了什么（范围＋项目/工作区两锚列）、结果如何（三清/四面事实全量）。
 * 只插入不写：重复回收是重复行；未成动作不落行（销毁失败上抛，照
 * {@code WorkspaceAction} 口径）。无 FK：回收对象行随动作本身消亡，锚列只作
 * 对账寻址（TSID 原文），不锚存活的行。
 */
@Entity
@Table(name = "wsp_reclaim_actions")
@Aggregate
@Getter
public class WorkspaceReclaimAction extends Auditable
        implements AggregateRoot<WorkspaceReclaimAction, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 回收范围（项目级/工作区级）。 */
    @Column(name = "scope", nullable = false)
    private ReclaimScope scope;

    /** 项目锚：项目级＝回收的项目；工作区级＝所属项目（无所属 NULL——失联/fork）。 */
    @Column(name = "project_id")
    private Long projectId;

    /** 工作区锚（三清对象，TSID 原文对账寻址）。 */
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** 操作者标识（admin 侧 TSID 十进制字符串；缺头落空 NULL）。 */
    @Column(name = "operator_id", length = 64)
    private String operatorId;

    /** 操作者昵称（直读展示；缺头落空 NULL）。 */
    @Column(name = "operator_name", length = 200)
    private String operatorName;

    /** 回收时有在飞 run 被级联停止（承 #477 停止面；无在飞 false）。 */
    @Column(name = "runs_stopped", nullable = false)
    private boolean runsStopped;

    /** 容器面清否（UNKNOWN 不算清——探查未成恒 false）。 */
    @Column(name = "container_clean", nullable = false)
    private boolean containerClean;

    /** 卷面清否。 */
    @Column(name = "volume_clean", nullable = false)
    private boolean volumeClean;

    /** 注册行面清否。 */
    @Column(name = "registry_row_clean", nullable = false)
    private boolean registryRowClean;

    /** 项目行面清否（工作区级 NULL＝不动项目面，非清非残留）。 */
    @Column(name = "project_row_clean")
    private Boolean projectRowClean;

    /** 三清探查成否（false＝销毁未全成/探查未成——各面状态未知不冒充清）。 */
    @Column(name = "probed", nullable = false)
    private boolean probed;

    /** 回收时刻（动作的语义时间，独立于审计 created_at）。 */
    @Column(name = "reclaimed_at", nullable = false)
    private LocalDateTime reclaimedAt;

    protected WorkspaceReclaimAction() {
    }

    private WorkspaceReclaimAction(ReclaimScope scope, Long projectId, Long workspaceId,
            boolean runsStopped, ThreeCleanCheck check, Boolean projectRowClean,
            Operator operator, LocalDateTime reclaimedAt) {
        this.scope = scope;
        this.projectId = projectId;
        this.workspaceId = workspaceId;
        this.runsStopped = runsStopped;
        this.containerClean = check.containerClean();
        this.volumeClean = check.volumeClean();
        this.registryRowClean = check.registryRowClean();
        this.projectRowClean = projectRowClean;
        this.probed = check.probed();
        this.operatorId = operator != null ? operator.id() : null;
        this.operatorName = operator != null ? operator.name() : null;
        this.reclaimedAt = reclaimedAt;
    }

    /** 工作区级回收留痕（项目锚可空——失联/fork 无所属；项目面不动＝NULL）。 */
    public static WorkspaceReclaimAction workspaceScope(Long workspaceId, Long projectId,
            boolean runsStopped, ThreeCleanCheck check, Operator operator,
            LocalDateTime reclaimedAt) {
        return new WorkspaceReclaimAction(ReclaimScope.WORKSPACE, projectId, workspaceId,
                runsStopped, check, null, operator, reclaimedAt);
    }

    /**
     * 项目级回收留痕（组合方〔business〕完成删除级联后事实递入落痕——跨 BC 事实
     * 递入，照 {@code WorkspaceScanFact} 先例；项目行面清否由组合方对账递入）。
     */
    public static WorkspaceReclaimAction projectScope(Long projectId, Long workspaceId,
            boolean runsStopped, ThreeCleanCheck check, boolean projectRowClean,
            Operator operator, LocalDateTime reclaimedAt) {
        return new WorkspaceReclaimAction(ReclaimScope.PROJECT, projectId, workspaceId,
                runsStopped, check, projectRowClean, operator, reclaimedAt);
    }

    /** 全清判据（一源）：scope 内受检面全清——工作区级三面、项目级另含项目行面。 */
    public boolean allClean() {
        if (!containerClean || !volumeClean || !registryRowClean) {
            return false;
        }
        return scope == ReclaimScope.PROJECT ? Boolean.TRUE.equals(projectRowClean) : true;
    }

    @Override
    public Long getId() {
        return id;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            this.id = TsidGenerator.newInstance().generate();
        }
    }
}
