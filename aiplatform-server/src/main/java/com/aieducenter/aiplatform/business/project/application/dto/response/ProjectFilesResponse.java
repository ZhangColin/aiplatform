package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.util.List;

/**
 * 项目文件树响应（#27 文件模式）：交付文件视图 = 工作区剔除非交付物（数据 /
 * 平台产物 / node_modules / .env，与源码包同源）后的只读文件清单。只列文件，
 * 目录节点由前端按路径段合成（空目录无交付物不值得占口径）。
 *
 * @param projectId 项目标识（TSID 十进制字符串）
 * @param files     文件条目（工作区相对路径 + 字节大小，按路径稳定排序；随生成/
 *                  修正后的工作区实时长出，无版本化）
 */
public record ProjectFilesResponse(
        String projectId,
        List<FileEntry> files
) {

    /** 单个文件条目。 */
    public record FileEntry(
            String path,
            long size
    ) {
    }
}
