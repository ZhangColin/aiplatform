package com.semgravio.aiplatform.business.order.application.dto.response;

import java.util.List;

import com.semgravio.aiplatform.business.order.domain.aggregate.Contract;
import com.semgravio.aiplatform.business.order.domain.aggregate.Order;

/**
 * 后台合同详情（#336 承接全量面）：{@link ContractResponse} 全字段＋PRD 冻结
 * 全文冗余拷贝（报价/录内容的依据面）＋动作留痕时间线（新 → 旧，append-only
 * 全量——操作人/时间/依据）。
 *
 * @param contract   合同全字段（含「需更新」派生标志）
 * @param prdSnapshot PRD 确认版全文冗余拷贝（合同自含；后台依据面）
 * @param transitions 动作留痕（新 → 旧）
 */
public record BackofficeContractDetailResponse(
        ContractResponse contract,
        String prdSnapshot,
        List<ContractTransitionResponse> transitions
) {

    /** 聚合＋订单现值 → 详情。 */
    public static BackofficeContractDetailResponse of(Contract contractAggregate, Order order) {
        return new BackofficeContractDetailResponse(
                ContractResponse.of(contractAggregate, order),
                contractAggregate.getPrdSnapshot(),
                contractAggregate.transitionHistoryNewestFirst().stream()
                        .map(ContractTransitionResponse::of)
                        .toList());
    }
}
