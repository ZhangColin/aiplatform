package com.aieducenter.aiplatform.base.workspace.domain.model;

import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;

/**
 * 一个工作区在环境后端处的运行时句柄（CONTEXT.md「沙箱」能力面的操作锚点）：
 * exec / exposePort / destroyWorkspace 都以它寻址。单容器化（ADR 0001）后与
 * {@link WorkspaceLayout} 布局常量表共同构成 run 执行体/平台对沙箱的全部约定。
 *
 * <p>与库记录同形（{@code wsp_workspaces} 持久化后可随时重建），服务重启接回
 * = 从记录还原本句柄。预览网关化（#128）后不再有随机宿主端口——预览 URL 是
 * workspaceId 子域（{@link WorkspaceNaming#previewUrl}），句柄不承载端口；
 * networkName 即共享预览网络 {@link WorkspaceNaming#PREVIEW_NETWORK}。</p>
 */
public record WorkspaceHandle(
        WorkspaceId workspaceId,
        EnvKind kind,
        String containerName,
        String networkName) {

    public static WorkspaceHandle dev(WorkspaceId workspaceId, String containerName,
                                      String networkName) {
        return new WorkspaceHandle(workspaceId, EnvKind.DEV, containerName, networkName);
    }

    public static WorkspaceHandle runtime(WorkspaceId workspaceId, EnvKind kind,
                                          String containerName, String networkName) {
        return new WorkspaceHandle(workspaceId, kind, containerName, networkName);
    }
}
