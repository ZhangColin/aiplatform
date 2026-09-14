package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceObservation;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceObservation.MiddlewareResourceObservation;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;

/**
 * 后台沙箱详情（#173 观测面）：单沙箱全量字段＋所属项目引用——清单行的超集，
 * 另带置备失败原因（FAILED 态排障）、封存包寻址键、审计时间列与中间件资源清单
 * （连接串原文，容器内回环形态）。全量如实呈现，无脱敏字段（机机签名面）。
 *
 * @param workspaceId     工作区标识（TSID 十进制字符串）
 * @param containerName   容器名
 * @param networkName     预览网络名
 * @param kind            环境类型（code）
 * @param kindName        环境类型名
 * @param status          置备状态（code）
 * @param statusName      置备状态名
 * @param provisionError  置备失败原因（FAILED 态非 null，其余 null）
 * @param desiredState    期望态（code）
 * @param desiredStateName 期望态名
 * @param containerState  容器实态（code）
 * @param containerStateName 实态名
 * @param lastTouchAt     最近触碰
 * @param volumeSizeBytes 卷用量（字节；封存容缺/探查失败为 null）
 * @param sealedAt        封存时刻（未封存为 null）
 * @param archivePath     封存包寻址键（未封存为 null）
 * @param archiveSizeBytes 封存包大小（字节；未封存为 null）
 * @param createdAt       创建时间（审计列）
 * @param updatedAt       更新时间（审计列）
 * @param resources       随供给中间件资源清单
 * @param project         所属项目引用（无所属项目为 null）
 */
public record BackofficeWorkspaceDetailResponse(
        String workspaceId,
        String containerName,
        String networkName,
        EnvKind kind,
        String kindName,
        ProvisioningStatus status,
        String statusName,
        String provisionError,
        DesiredState desiredState,
        String desiredStateName,
        ContainerState containerState,
        String containerStateName,
        LocalDateTime lastTouchAt,
        Long volumeSizeBytes,
        LocalDateTime sealedAt,
        String archivePath,
        Long archiveSizeBytes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<MiddlewareResourceObservation> resources,
        BackofficeWorkspaceSummaryResponse.ProjectRef project) {

    /** 观测事实＋项目引用 → 详情。 */
    public static BackofficeWorkspaceDetailResponse of(WorkspaceObservation observation,
            BackofficeWorkspaceSummaryResponse.ProjectRef project) {
        return new BackofficeWorkspaceDetailResponse(
                Long.toString(observation.workspaceId()),
                observation.containerName(),
                observation.networkName(),
                observation.kind(),
                observation.kind().getName(),
                observation.status(),
                observation.status().getName(),
                observation.provisionError(),
                observation.desiredState(),
                observation.desiredState().getName(),
                observation.containerState(),
                observation.containerState().getName(),
                observation.lastTouchAt(),
                observation.volumeSizeBytes(),
                observation.sealedAt(),
                observation.archivePath(),
                observation.archiveSizeBytes(),
                observation.createdAt(),
                observation.updatedAt(),
                observation.resources(),
                project);
    }
}
