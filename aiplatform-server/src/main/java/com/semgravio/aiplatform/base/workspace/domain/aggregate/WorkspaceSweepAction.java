package com.semgravio.aiplatform.base.workspace.domain.aggregate;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.data.jpa.domain.Auditable;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.base.workspace.domain.model.Operator;

/**
 * 手动清扫动作留痕行（{@code wsp_sweep_actions}，#489，ADR-0045「动刀类走 admin
 * 手动＋留痕」）：后台 resources/sweep 每次触发一行——谁（操作者两列，照
 * {@link WorkspaceAction} 先例：admin 侧标识，缺透传头落 NULL）、何时、动了什么
 * （逐项处置报告 JSON 全量留档——收编了什么/跳过什么/为什么，事后可审计）。
 * 只插入不写：重复清扫是重复行；库不可达轮也落行（{@code registryReachable}
 * ＝false——「发了清扫而没动刀」本身是要可审计的事实）。无 FK：清扫对象是
 * 无主资源（注册表外），不锚工作区。
 */
@Entity
@Table(name = "wsp_sweep_actions")
@Aggregate
@Getter
public class WorkspaceSweepAction extends Auditable
        implements AggregateRoot<WorkspaceSweepAction, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 操作者标识（admin 侧 TSID 十进制字符串；缺头落空 NULL——照 WorkspaceAction 口径）。 */
    @Column(name = "operator_id", length = 64)
    private String operatorId;

    /** 操作者昵称（直读展示；缺头落空 NULL）。 */
    @Column(name = "operator_name", length = 200)
    private String operatorName;

    /** 注册表可达否（false＝库不可达整体跳过轮——准绳无从构成，一刀未动）。 */
    @Column(name = "registry_reachable", nullable = false)
    private boolean registryReachable;

    /** 已收编容器数。 */
    @Column(name = "swept_containers", nullable = false)
    private int sweptContainers;

    /** 已收编卷数。 */
    @Column(name = "swept_volumes", nullable = false)
    private int sweptVolumes;

    /** 跳过数（在册/宽限窗/时刻解析不出——逐项明细在报告 JSON）。 */
    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    /** 删除未成数（下轮扫描再收）。 */
    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    /** 逐项处置报告 JSON（收编/跳过/未成逐项——kind/identifier/disposition/skipReason）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", columnDefinition = "jsonb")
    private String itemsJson;

    /** 清扫时刻（动作的语义时间，独立于审计 created_at）。 */
    @Column(name = "swept_at", nullable = false)
    private LocalDateTime sweptAt;

    protected WorkspaceSweepAction() {
    }

    private WorkspaceSweepAction(Operator operator, boolean registryReachable,
            int sweptContainers, int sweptVolumes, int skippedCount, int failedCount,
            String itemsJson, LocalDateTime sweptAt) {
        this.operatorId = operator != null ? operator.id() : null;
        this.operatorName = operator != null ? operator.name() : null;
        this.registryReachable = registryReachable;
        this.sweptContainers = sweptContainers;
        this.sweptVolumes = sweptVolumes;
        this.skippedCount = skippedCount;
        this.failedCount = failedCount;
        this.itemsJson = itemsJson;
        this.sweptAt = sweptAt;
    }

    /** 登记一次已执行的手动清扫（操作者可空——签名面缺透传头落空口径）。 */
    public static WorkspaceSweepAction record(Operator operator, boolean registryReachable,
            int sweptContainers, int sweptVolumes, int skippedCount, int failedCount,
            String itemsJson, LocalDateTime sweptAt) {
        return new WorkspaceSweepAction(operator, registryReachable, sweptContainers,
                sweptVolumes, skippedCount, failedCount, itemsJson, sweptAt);
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
