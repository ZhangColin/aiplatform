package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 项目文件包（#174 后台下载）：项目工作区内容的取走产物（tar.gz 字节流＋口径
 * 标记）——非 REST 信封体（二进制流直出），文件名/HTTP 头归 REST 层按口径拼装
 * （源码包 {@code {projectId}-source.tar.gz} / 封存包 {@code {projectId}-archive.tar.gz}）。
 *
 * @param projectId       项目标识（TSID 十进制字符串）
 * @param content         包字节流
 * @param fromSealArchive true＝封存包（整卷口径：数据库与机密随包）；false＝即时
 *                        源码包（交付口径：同订单源码包导出）
 */
public record ProjectFilesPackage(String projectId, byte[] content, boolean fromSealArchive) {
}
