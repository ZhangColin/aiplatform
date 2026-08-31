package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 文本文件内容响应（#27 文件模式「点看」）：工作区相对路径 + 文本正文原样。
 * 仅可浏览路径（交付文件视图，非交付物/机密不在面内）且不超在线查看大小上限
 * （容器侧拦截，1 MiB）、正文无 NUL（非文本判定）——各拒绝口径以错误码表达
 * （PRJ_020/021/022/023），不在此载体表达。
 *
 * @param path    工作区相对路径（请求 path 原样回显）
 * @param content 文本正文（工作区文件原样）
 */
public record ProjectFileContentResponse(
        String path,
        String content
) {
}
