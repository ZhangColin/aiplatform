package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;

/**
 * 后台沙箱清单行（#173 观测面，/api/backoffice/workspaces）：运营第一次能看见
 * 每台沙箱睡没睡、封没封、占多少存储——期望态与 docker 实态两列分示（漂移一眼
 * 可见：期望运行而实态无容器即「实态已亡」漂移行）。行内嵌项目引用（跳转项目
 * 详情的锚；工作区先于项目存在、软引用容缺——无所属项目为 null）。
 *
 * @param workspaceId     工作区标识（TSID 十进制字符串）
 * @param containerName   容器名（与 docker ps 对账锚点）
 * @param kind            环境类型（code）
 * @param kindName        环境类型名
 * @param status          置备状态（code）：在途置备/唤醒（PROVISIONING）与漂移的区分锚
 * @param statusName      置备状态名
 * @param desiredState    期望态（code）：1=运行 2=休眠 3=封存（意图，ADR-0016）
 * @param desiredStateName 期望态名
 * @param containerState  容器实态（code）：1=运行中 2=已停止 3=无容器 4=未知（探查失败）
 * @param containerStateName 实态名
 * @param lastTouchAt     最近触碰（闲置计时输入）
 * @param volumeSizeBytes 卷用量（字节；封存容缺/探查失败为 null）
 * @param sealedAt        封存时刻（未封存为 null）
 * @param archiveSizeBytes 封存包大小（字节；未封存为 null）
 * @param project         所属项目引用（无所属项目为 null）
 */
public record BackofficeWorkspaceSummaryResponse(
        String workspaceId,
        String containerName,
        EnvKind kind,
        String kindName,
        ProvisioningStatus status,
        String statusName,
        DesiredState desiredState,
        String desiredStateName,
        ContainerState containerState,
        String containerStateName,
        LocalDateTime lastTouchAt,
        Long volumeSizeBytes,
        LocalDateTime sealedAt,
        Long archiveSizeBytes,
        ProjectRef project) {

    /**
     * 所属项目引用：认出项目即可（详情另取）——id＋名称＋归档位。
     */
    public record ProjectRef(
            String projectId,
            String name,
            Boolean archived) {
    }

    /** 观测事实＋项目引用 → 清单行。 */
    public static BackofficeWorkspaceSummaryResponse of(WorkspaceObservation observation,
            ProjectRef project) {
        return new BackofficeWorkspaceSummaryResponse(
                Long.toString(observation.workspaceId()),
                observation.containerName(),
                observation.kind(),
                observation.kind().getName(),
                observation.status(),
                observation.status().getName(),
                observation.desiredState(),
                observation.desiredState().getName(),
                observation.containerState(),
                observation.containerState().getName(),
                observation.lastTouchAt(),
                observation.volumeSizeBytes(),
                observation.sealedAt(),
                observation.archiveSizeBytes(),
                project);
    }
}
