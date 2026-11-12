package com.semgravio.aiplatform.base.workspace.domain.aggregate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 在册失联标记行（{@code wsp_lost_marks}，#488，ADR-0045）：注册表在册但项目行
 * 已删（销毁中途吞错的静止现场）且物理资源仍在的工作区——机器不删可能是用户
 * 数据的卷（ADR-0016），只标记暴露给后台清单、待人工裁决（裁决出口＝#490 一键
 * 回收）。是「对账判定结果」的观测面，不进注册表聚合（意图面不混对账状态，
 * 形状选型照 {@link WorkspaceObservationSample} #487 先例）。
 *
 * <p>写口唯一：{@code WorkspaceDetachmentAppService}（休眠扫描轮末尾分级处置，
 * 首标落行、已标跳过——幂等不翻审计列）；行随工作区记录级联消亡（FK ON DELETE
 * CASCADE）——空壳行卫生删除（{@code wsp_workspaces} 行删）即清标记，无需额外
 * 卫生通道。标记只增不清：项目删除不可逆，认领不会回来；误标防御＝新建宽限窗
 * ＋认领集申报（判定入口 javadoc）。</p>
 */
@Entity
@Table(name = "wsp_lost_marks")
@Aggregate
@Getter
public class WorkspaceLostMark extends Auditable
        implements AggregateRoot<WorkspaceLostMark, Long> {

    /** 失联锚（即注册行主键——一工作区一行）。 */
    @Id
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 失联判定时刻（后台清单呈现「失联多久」的锚）。 */
    @Column(name = "marked_at", nullable = false)
    private LocalDateTime markedAt;

    protected WorkspaceLostMark() {
    }

    private WorkspaceLostMark(WorkspaceId workspaceId, LocalDateTime markedAt) {
        this.workspaceId = workspaceId.id();
        this.markedAt = markedAt;
    }

    /** 首标落行（幂等跳过归写面编排——已标不重复落）。 */
    public static WorkspaceLostMark mark(WorkspaceId workspaceId, LocalDateTime markedAt) {
        return new WorkspaceLostMark(workspaceId, markedAt);
    }

    @Override
    public Long getId() {
        return workspaceId;
    }
}
