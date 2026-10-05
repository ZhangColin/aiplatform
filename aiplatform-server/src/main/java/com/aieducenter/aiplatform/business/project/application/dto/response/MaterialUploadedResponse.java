package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 物料上传响应（#286，ADR-0027 图片管道）：path 即随话发送的附件载荷引用
 * （工作区路径引用、不带字节——对偶 raw 直出取件）；name 为净化后的原始名
 * （附件 chip 呈现用）。
 *
 * @param path 工作区相对路径（materials/ 下落点，后续 attachments 载荷原样回传）
 * @param name 净化后的原始文件名（含扩展名）
 * @param size 落盘字节数（= 上传字节数，stat 回执核对后的事实）
 */
public record MaterialUploadedResponse(
        String path,
        String name,
        long size
) {
}
