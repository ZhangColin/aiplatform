package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 「查看当时」起会话响应（#92）：查看会话标识（关闭动作的寻址锚）+ 快照预览 URL
 * （当时系统可操作的入口）。预览 = 快照容器的独立端口映射，与主容器预览并立。
 */
public record VersionViewStartResponse(
        String viewId,
        String previewUrl) {
}
