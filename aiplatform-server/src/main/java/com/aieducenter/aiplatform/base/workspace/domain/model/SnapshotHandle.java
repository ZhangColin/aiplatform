package com.aieducenter.aiplatform.base.workspace.domain.model;

import java.net.URI;

/**
 * 查看会话（#92）在环境后端处的运行时句柄：一个「查看当时」快照容器的操作锚点
 * （exec / 销毁）+ 预览入口。与 {@link WorkspaceHandle} 不同——快照容器不是常驻
 * 工作区实例（ADR 0007：同一系统工作区的临时只读视图容器，不承载持久状态、查看
 * 结束即销毁），故用独立轻量句柄承载，不进库、无自愈面。
 *
 * <p>预览 URL（#141 网关化）：{@code {scheme}://snap-{viewId}.{previewBase}}——环境
 * 后端持有 scheme/base 两个维度（与主预览 exposePort 同构），编排层透传、零网关
 * 知识。不再有宿主端口映射（随机端口直连已随 #141 退役）。</p>
 *
 * @param containerName 快照容器名（exec / 销毁级联的锚点）
 * @param previewUrl    快照预览入口（网关子域路由，弹窗 iframe / 新窗口共用）
 */
public record SnapshotHandle(String containerName, URI previewUrl) {
}
