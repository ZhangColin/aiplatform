package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.aieducenter.aiplatform.business.order.domain.aggregate.Contract;
import com.aieducenter.aiplatform.business.order.domain.enums.ContractStatus;

/**
 * 后台合同清单行（#336，admin 页面跨仓消费）：编号/状态/版本/金额＋订单锚。
 *
 * @param id           合同标识（TSID 十进制字符串）
 * @param contractNo   合同编号（SG- 风格）
 * @param orderId      所属订单标识
 * @param status       合同状态（code，六态见 {@link ContractStatus}）
 * @param statusName   状态名
 * @param version      合同版本（重录 +1）
 * @param amount       冻结金额（分）
 * @param currency     币种（v1 恒 CNY）
 * @param createdAt    创建时间（确认范围/重建时点）
 */
public record BackofficeContractSummaryResponse(
        String id,
        String contractNo,
        String orderId,
        ContractStatus status,
        String statusName,
        int version,
        Long amount,
        String currency,
        LocalDateTime createdAt
) {

    /** 聚合 → 清单行。 */
    public static BackofficeContractSummaryResponse of(Contract contract) {
        return new BackofficeContractSummaryResponse(
                contract.getId().toString(),
                contract.getContractNo(),
                contract.getOrderId().toString(),
                contract.getStatus(),
                contract.getStatus().getName(),
                contract.getVersion(),
                contract.getAmount(),
                contract.getCurrency(),
                contract.getCreatedAt());
    }
}
