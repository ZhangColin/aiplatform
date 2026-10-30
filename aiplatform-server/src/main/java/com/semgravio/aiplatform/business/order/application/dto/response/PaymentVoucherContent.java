package com.semgravio.aiplatform.business.order.application.dto.response;

/**
 * 凭证内容（#337 后台核验看证的点取载荷）：元数据＋字节同取，HTTP 形态（响应
 * 头/文件名）归 REST 层——本件不是 JSON 信封响应（二进制直出）。
 *
 * @param fileName    净化后原始文件名
 * @param contentType MIME（application/pdf | image/png | image/jpeg）
 * @param byteSize    字节数
 * @param content     文件字节
 */
public record PaymentVoucherContent(
        String fileName,
        String contentType,
        Long byteSize,
        byte[] content
) {
}
