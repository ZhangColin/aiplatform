package com.semgravio.aiplatform.base.workspace.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.enums.DesiredState;
import com.semgravio.aiplatform.base.workspace.domain.enums.EnvKind;
import com.semgravio.aiplatform.base.workspace.domain.enums.MiddlewareKind;
import com.semgravio.aiplatform.base.workspace.domain.enums.ProvisioningStatus;

/**
 * 工作区观测（#173 后台观测面的跨 BC 读模型；#487 起实态两列为采样快照）：
 * 记录全量字段（注册表，意图事实）＋占用采样帧（{@code containerState}/
 * {@code volumeSizeBytes}，扫描轮旁路采样落库）。base 只出沙箱事实——所属项目
 * 引用由组合方（business.project 后台面）拼装（base 不反向依赖 business，跨 BC
 * 事实方向照 {@code WorkspaceScanFact} 先例：本 BC 出沙箱事实、消费方带业务事实）。
 *
 * <p>采样口径（#487，治清单超时）：实态与卷大小不再请求时现场探——读面零 docker
 * 调用、纯读库；{@code sampledAt} 是新鲜度标注（采样时刻，运营判断数据多新）。
 * 未采样（新工作区首个采样窗内）两列如实缺省 null——「没有帧」≠「探到 UNKNOWN」。
 * 卷大小是采样值（旁路容器 du 全卷、含可重建缓存，字节）：封存态按注册表意图
 * 容缺 null（卷已删；封存信息以 {@code sealedAt}/{@code archiveSizeBytes} 呈现）；
 * 采样时探查失败亦 null（观测容缺不抛）。</p>
 *
 * @param workspaceId     工作区标识（数值形；对外 REST 由组合方转字符串口径）
 * @param kind            环境类型（v1 仅 DEV）
 * @param containerName   容器名（与 docker ps 对账锚点）
 * @param networkName     预览网络名
 * @param status          置备状态（PROVISIONING 在途与漂移的区分锚）
 * @param provisionError  置备失败原因（FAILED 态非 null）
 * @param desiredState    期望态（意图，ADR-0016）
 * @param containerState  容器实态采样（未采样为 null；采样到探查失败 = UNKNOWN）
 * @param volumeSizeBytes 卷用量采样（字节；封存按意图容缺/未采样/采样失败为 null）
 * @param sampledAt       采样时刻（新鲜度标注；未采样为 null）
 * @param lostAt          在册失联判定时刻（#488：项目行已删而物理资源仍在——只标记
 *                        待人工裁决；未失联为 null）
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
        LocalDateTime sampledAt,
        LocalDateTime lostAt,
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

    /** 聚合＋采样帧＋失联标记 → 观测读模型（采样帧/标记可缺——未采样行两列缺省、未失联 null）。 */
    public static WorkspaceObservation of(Workspace workspace, ContainerState containerState,
            Long volumeSizeBytes, LocalDateTime sampledAt, LocalDateTime lostAt) {
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
                sampledAt,
                lostAt,
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
