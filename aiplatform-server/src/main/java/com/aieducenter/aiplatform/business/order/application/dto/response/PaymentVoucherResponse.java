package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentVoucher;

/**
 * 转账凭证响应（#337 用户面付款面板 + 后台核验承接共用同构）：元数据行——
 * 上传仅代表已提交（核验结论在留痕行），字节不在读面（后台点看走内容端点）。
 *
 * @param id          凭证标识（TSID 十进制字符串）
 * @param nodeId      所属付款节点
 * @param fileName    净化后原始文件名（回执单/水单人读）
 * @param contentType MIME（application/pdf | image/png | image/jpeg）
 * @param byteSize    字节数
 * @param createdAt   上传时间
 */
public record PaymentVoucherResponse(
        String id,
        String nodeId,
        String fileName,
        String contentType,
        Long byteSize,
        LocalDateTime createdAt
) {

    /** 凭证行 → 响应。 */
    public static PaymentVoucherResponse of(PaymentVoucher voucher) {
        return new PaymentVoucherResponse(
                voucher.getId().toString(),
                voucher.getNodeId().toString(),
                voucher.getFileName(),
                voucher.getContentType(),
                voucher.getByteSize(),
                voucher.getCreatedAt());
    }
}
