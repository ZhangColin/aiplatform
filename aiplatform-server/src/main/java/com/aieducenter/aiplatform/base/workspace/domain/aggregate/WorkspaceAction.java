package com.aieducenter.aiplatform.base.workspace.domain.aggregate;

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

import com.aieducenter.aiplatform.base.workspace.domain.enums.WorkspaceActionKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.Operator;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 后台沙箱动作行（{@code wsp_workspace_actions}，#174）：管理员对单台沙箱干预
 * 动作的 append-only 留痕——谁（操作者两列，照订单/单价表先例）对哪台沙箱做了
 * 什么（四动作）何时（动作时刻）。只插入不写：重复动作是重复行（周期合法——
 * 休眠/唤醒可来回）；被拒动作不落行（拒绝以错误响应即时触达，留痕只记执行了的
 * 写口）。行随工作区记录级联消亡（同封存包口径：删除项目才是终点）。
 */
@Entity
@Table(name = "wsp_workspace_actions")
@Aggregate
@Getter
public class WorkspaceAction extends Auditable implements AggregateRoot<WorkspaceAction, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "action", nullable = false, updatable = false)
    private WorkspaceActionKind action;

    /** 操作者标识（admin 侧 TSID 十进制字符串；缺头落空 NULL，#155 口径）。 */
    @Column(name = "operator_id", length = 64)
    private String operatorId;

    /** 操作者昵称（直读展示；缺头落空 NULL）。 */
    @Column(name = "operator_name", length = 200)
    private String operatorName;

    /** 动作时刻（管理动作的语义时间，独立于审计 created_at）。 */
    @Column(name = "acted_at", nullable = false, updatable = false)
    private LocalDateTime actedAt;

    protected WorkspaceAction() {
    }

    private WorkspaceAction(WorkspaceId workspaceId, WorkspaceActionKind action,
            Operator operator, LocalDateTime actedAt) {
        this.workspaceId = workspaceId.id();
        this.action = action;
        this.operatorId = operator != null ? operator.id() : null;
        this.operatorName = operator != null ? operator.name() : null;
        this.actedAt = actedAt;
    }

    /**
     * 登记一条已执行的动作（操作者可空——签名面缺透传头落空口径）。
     */
    public static WorkspaceAction record(WorkspaceId workspaceId, WorkspaceActionKind action,
            Operator operator, LocalDateTime actedAt) {
        return new WorkspaceAction(workspaceId, action, operator, actedAt);
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
