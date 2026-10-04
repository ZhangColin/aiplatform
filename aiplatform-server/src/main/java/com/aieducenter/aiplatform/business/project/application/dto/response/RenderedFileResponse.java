package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 位图出口渲染产物（#284）：容器内渲成落盘的 PNG 衍生引用——工作区相对路径 +
 * 字节大小（渲染命令的 stat 回执）。字节不回传（产物之家是工作区，取件走文件
 * 服务路由——ADR-0027 存储正本口径）；路径即调用方（出稿/下载/导出触发点）的
 * 接线引用。
 *
 * @param path      PNG 衍生的工作区相对路径（渲染入参原样回传）
 * @param sizeBytes PNG 字节大小（容器侧 stat 回执）
 */
public record RenderedFileResponse(String path, long sizeBytes) {
}
