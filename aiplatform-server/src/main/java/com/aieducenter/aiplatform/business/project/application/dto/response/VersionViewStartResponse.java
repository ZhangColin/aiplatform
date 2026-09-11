package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 「查看当时」起会话响应（#92）：查看会话标识（关闭动作的寻址锚）+ 快照预览 URL
 * （当时系统可操作的入口）。预览走网关子域路由（#141：{@code snap-{viewId}.{base}}，
 * 与主容器预览同模式并立，快照不注入圈注——只逛不换）。
 */
public record VersionViewStartResponse(
        String viewId,
        String previewUrl) {
}
