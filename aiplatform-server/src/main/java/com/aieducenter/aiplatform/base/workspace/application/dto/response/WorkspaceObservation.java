package com.aieducenter.aiplatform.base.workspace.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.aieducenter.aiplatform.base.workspace.domain.enums.ProvisioningStatus;

/**
 * 工作区观测（#173 后台观测面的跨 BC 读模型）：记录全量字段＋docker 实态探查
 * （{@code containerState}/{@code volumeSizeBytes}）。base 只出沙箱事实——所属
 * 项目引用由组合方（business.project 后台面）拼装（base 不反向依赖 business，
 * 跨 BC 事实方向照 {@code WorkspaceScanFact} 先例：本 BC 出沙箱事实、消费方带
 * 业务事实）。
 *
 * <p>卷大小是探查值（旁路容器 du 全卷、含可重建缓存，字节）：未封存逐行探查、
 * 封存容缺 null（卷已删——封存信息以 {@code sealedAt}/{@code archiveSizeBytes}
 * 呈现）；探查失败亦 null（观测容缺不抛）。</p>
 *
 * @param workspaceId     工作区标识（数值形；对外 REST 由组合方转字符串口径）
 * @param kind            环境类型（v1 仅 DEV）
 * @param containerName   容器名（与 docker ps 对账锚点）
 * @param networkName     预览网络名
 * @param status          置备状态（PROVISIONING 在途与漂移的区分锚）
 * @param provisionError  置备失败原因（FAILED 态非 null）
 * @param desiredState    期望态（意图，ADR-0016）
 * @param containerState  容器实态（探查一瞥；UNKNOWN = 探查失败）
 * @param volumeSizeBytes 卷用量（字节；封存/探查失败为 null）
 * @param lastTouchAt     最近触碰（闲置计时输入）
 * @param sealedAt        封存时刻（未封存为 null）
 * @param archivePath     封存包寻址键（未封存为 null）
 * @param archiveSizeBytes 封存包大小（字节；未封存为 null）
 * @param resources       随供给中间件资源清单
 */
public record WorkspaceObservation(
        long workspaceId,
        EnvKind kind,
        String containerName,
        String networkName,
        ProvisioningStatus status,
        String provisionError,
        DesiredState desiredState,
        ContainerState containerState,
        Long volumeSizeBytes,
        LocalDateTime lastTouchAt,
        LocalDateTime sealedAt,
        String archivePath,
        Long archiveSizeBytes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<MiddlewareResourceObservation> resources) {

    /**
     * 中间件资源观测（连接串原文对后台排障有用——与用户面
     * {@code WorkspaceResponse.MiddlewareResourceResponse} 同形）。
     */
    public record MiddlewareResourceObservation(
            MiddlewareKind kind,
            String containerName,
            String internalUrl) {
    }

    /** 聚合＋探查两帧 → 观测读模型。 */
    public static WorkspaceObservation of(Workspace workspace, ContainerState containerState,
            Long volumeSizeBytes) {
        return new WorkspaceObservation(
                workspace.getId(),
                workspace.getKind(),
                workspace.getContainerName(),
                workspace.getNetworkName(),
                workspace.getStatus(),
                workspace.getProvisionError(),
                workspace.getDesiredState(),
                containerState,
                volumeSizeBytes,
                workspace.getLastTouchAt(),
                workspace.getSealedAt(),
                workspace.getArchivePath(),
                workspace.getArchiveSizeBytes(),
                workspace.getCreatedAt(),
                workspace.getUpdatedAt(),
                workspace.getResources().stream()
                        .map(resource -> new MiddlewareResourceObservation(
                                resource.getKind(), resource.getContainerName(),
                                resource.getInternalUrl()))
                        .toList());
    }
}
