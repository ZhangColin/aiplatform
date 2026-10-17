package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentPlan;
import com.aieducenter.aiplatform.business.order.domain.aggregate.PaymentVoucher;
import com.aieducenter.aiplatform.business.order.domain.entity.PaymentNode;
import com.aieducenter.aiplatform.business.order.domain.entity.PaymentVerification;

/**
 * 后台付款计划详情（#337 核验承接全量面）：活跃计划（订单名下最新行）全量
 * （节点＋凭证＋核验留痕时间线）＋历史计划行摘要（旧计划随旧合同留痕——撤回
 * 重建/范围重冻后旧行定格可见）。
 *
 * @param plan    活跃计划（用户面同构响应）
 * @param trails  核验留痕时间线（新 → 旧，跨节点全量）
 * @param history 历史计划行摘要（新 → 旧，不含活跃行）
 */
public record BackofficePaymentPlanDetailResponse(
        PaymentPlanResponse plan,
        List<PaymentVerificationResponse> trails,
        List<HistoryPlanResponse> history
) {

    /** 历史计划行摘要（留痕面——节点细节不在本面，行身份与冻结集指向足矣）。 */
    public record HistoryPlanResponse(
            String id,
            String contractId,
            String contractNo,
            int nodeCount,
            Long totalAmount,
            Long totalSettled,
            LocalDateTime createdAt
    ) {

        /** 计划行 → 摘要。 */
        public static HistoryPlanResponse of(PaymentPlan planRow) {
            return new HistoryPlanResponse(
                    planRow.getId().toString(),
                    planRow.getContractId().toString(),
                    planRow.getContractNo(),
                    planRow.getNodes().size(),
                    planRow.totalAmount(),
                    planRow.totalSettled(),
                    planRow.getCreatedAt());
        }
    }

    /**
     * 计划＋凭证/留痕取数面 → 详情：留痕时间线按节点序聚合后倒排（新 → 旧），
     * 历史行＝同订单更早的计划行（新 → 旧）。
     */
    public static BackofficePaymentPlanDetailResponse of(PaymentPlan plan,
            Function<Long, List<PaymentVoucher>> vouchersByNode,
            List<PaymentPlan> allPlansOfOrder) {
        List<PaymentVerificationResponse> trails = plan.getNodes().stream()
                .flatMap(node -> node.verificationHistoryNewestFirst().stream()
                        .map(verification -> PaymentVerificationResponse.of(verification, node.getId())))
                .toList();
        List<HistoryPlanResponse> history = allPlansOfOrder.stream()
                .filter(row -> !row.getId().equals(plan.getId()))
                .sorted(Comparator.comparing(PaymentPlan::getId).reversed())
                .map(HistoryPlanResponse::of)
                .toList();
        return new BackofficePaymentPlanDetailResponse(
                PaymentPlanResponse.of(plan, vouchersByNode), trails, history);
    }
}
