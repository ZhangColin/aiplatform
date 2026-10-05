package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 单文件下载字节（#287 通用下载，ADR-0027 支付门）：下载端点的应用层产物——
 * <strong>非 REST 信封体</strong>（二进制流直出，先例＝源码包端点），content-type
 * 按扩展名给真实 MIME（图片）或 {@code application/octet-stream}（其余）、HTTP 头
 * 拼装（attachment 带走语义＋文件名）归 REST 层。
 *
 * @param content     文件原始字节（execBinary 字节通道取回，不经字符集解释）
 * @param contentType MIME（下载面不挑类型——文件区一切可浏览文件）
 */
public record ProjectFileDownloadResponse(byte[] content, String contentType) {
}
