package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 图片文件字节（#283 点看图片 inline 大图）：raw 直出端点的应用层产物——
 * <strong>非 REST 信封体</strong>（二进制流直出，先例＝源码包端点），content-type
 * 按扩展名给真实 MIME、HTTP 头拼装归 REST 层（inline 呈现语义）。
 *
 * @param content     图片原始字节（execBinary 字节通道取回，不经字符集解释）
 * @param contentType 真实 MIME（png/jpg/webp/gif/svg）
 */
public record ProjectFileRawResponse(byte[] content, String contentType) {
}
