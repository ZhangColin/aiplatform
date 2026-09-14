package com.aieducenter.aiplatform.base.workspace.domain.model;

/**
 * 封存包事实（#172，ADR-0016 卷瘦身快照）：整卷 tar（仅排除可重建缓存、数据库随包）
 * 落到平台存储后的观测事实——路径与大小。入库 {@code wsp_workspaces.archive_*}，
 * 深度唤醒按路径取包、项目删除按路径清理。
 *
 * @param path      封存包在平台存储的绝对路径（本地磁盘 v1；换对象存储仍是存储内寻址键）
 * @param sizeBytes 包大小（字节）
 */
public record SealPackage(String path, long sizeBytes) {
}
