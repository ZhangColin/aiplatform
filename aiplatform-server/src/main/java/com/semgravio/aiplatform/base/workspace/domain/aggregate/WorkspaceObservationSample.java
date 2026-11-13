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

import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;

/**
 * 工作区占用观测采样行（{@code wsp_observation_samples}，#487）：容器实态与卷用量
 * 的扫描轮采样快照——每工作区一行、采样时刻（{@code sampledAt}）即新鲜度标注，
 * 观测读面（清单/详情）纯读库零 docker 调用。是「采样的观测」而非实态正本：
 * 实态判定（唤醒/休眠收敛）仍以现场探查为准，本表只喂养后台观测显示。
 *
 * <p>写口唯一：{@code WorkspaceObservationSamplingAppService}（扫描轮末尾旁路采样，
 * 在途让路不排队）；行随工作区记录级联消亡（FK ON DELETE CASCADE，同
 * {@code MiddlewareResource} 先例——销毁删注册行即清采样行）。封存态不采卷
 * （卷已删，du 无意义），卷列容缺 NULL。</p>
 */
@Entity
@Table(name = "wsp_observation_samples")
@Aggregate
@Getter
public class WorkspaceObservationSample extends Auditable
        implements AggregateRoot<WorkspaceObservationSample, Long> {

    /** 采样锚（即注册行主键——一工作区一行）。 */
    @Id
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** 容器实态快照（四态如实；探查失败 = UNKNOWN 诚实位）。 */
    @Column(name = "container_state", nullable = false)
    private ContainerState containerState;

    /** 卷用量快照（旁路容器 du 全卷字节；封存不采/探查失败容缺 NULL）。 */
    @Column(name = "volume_size_bytes")
    private Long volumeSizeBytes;

    /** 采样时刻（新鲜度标注的唯一正本——两帧同一轮采样）。 */
    @Column(name = "sampled_at", nullable = false)
    private LocalDateTime sampledAt;

    protected WorkspaceObservationSample() {
    }

    private WorkspaceObservationSample(WorkspaceId workspaceId, ContainerState containerState,
            Long volumeSizeBytes, LocalDateTime sampledAt) {
        this.workspaceId = workspaceId.id();
        this.resample(containerState, volumeSizeBytes, sampledAt);
    }

    /** 首采落行（此后同工作区复用本行覆盖采样）。 */
    public static WorkspaceObservationSample record(WorkspaceId workspaceId,
            ContainerState containerState, Long volumeSizeBytes, LocalDateTime sampledAt) {
        return new WorkspaceObservationSample(workspaceId, containerState,
                volumeSizeBytes, sampledAt);
    }

    /** 覆盖采样（一轮一帧：实态与卷用量同帧刷新，时刻一并拨新）。 */
    public WorkspaceObservationSample resample(ContainerState containerState,
            Long volumeSizeBytes, LocalDateTime sampledAt) {
        this.containerState = containerState;
        this.volumeSizeBytes = volumeSizeBytes;
        this.sampledAt = sampledAt;
        return this;
    }

    @Override
    public Long getId() {
        return workspaceId;
    }
}
