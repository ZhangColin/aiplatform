package com.aieducenter.aiplatform.base.workspace.domain.model;

/**
 * 查看会话（#92）在环境后端处的运行时句柄：一个「查看当时」快照容器的操作锚点
 * （exec / 销毁）。与 {@link WorkspaceHandle} 不同——快照容器不是常驻工作区实例
 * （ADR 0007：同一系统工作区的临时只读视图容器，不承载持久状态、查看结束即
 * 销毁），故用独立轻量句柄承载 containerName + previewPort，不进库、无自愈面。
 *
 * @param containerName 快照容器名（exec / 销毁级联的锚点）
 * @param previewPort   快照预览的宿主端口（本地 = docker 端口映射）
 */
public record SnapshotHandle(String containerName, int previewPort) {
}
