package com.aieducenter.aiplatform.base.workspace.application.dto.response;

/**
 * 项目文件包（#174 后台下载）：工作区内容的取走产物——封存态＝封存包（整卷
 * 口径：仅排可重建缓存，数据库与机密随包），未封存＝源码包（交付口径：排
 * node_modules/.env 等，同订单源码包导出）。两口径内容不同、同为 tar.gz；
 * {@code fromSealArchive} 供调用方区分命名/呈现（HTTP 头归 REST 层，本层只出
 * 字节与口径标记）。
 *
 * @param content         包字节流（tar.gz）
 * @param fromSealArchive true＝封存包（整卷口径）；false＝即时源码包（交付口径）
 */
public record WorkspaceContentPackage(byte[] content, boolean fromSealArchive) {
}
