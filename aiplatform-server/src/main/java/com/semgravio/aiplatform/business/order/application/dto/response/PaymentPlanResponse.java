package com.semgravio.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

import com.semgravio.aiplatform.business.order.domain.aggregate.PaymentPlan;
import com.semgravio.aiplatform.business.order.domain.aggregate.PaymentVoucher;
import com.semgravio.aiplatform.business.order.domain.entity.PaymentNode;

/**
 * 付款计划响应（#337 用户面付款面板 + 后台核验承接共用同构）：挂订单、随合同
 * 冻结同刻创建（合同号定格）；节点清单（录入序）＋总额/累计已核到账（占比与
 * 「本次应付/已付/未付」的换算输入）。活跃计划＝订单名下最新行；旧计划随旧
 * 合同留痕（不在本读面，后台详情带历史行摘要）。
 *
 * @param id          计划标识（TSID 十进制字符串）
 * @param orderId     所属订单
 * @param contractId  创建时点的合同
 * @param contractNo  合同号定格（附言码正身源）
 * @param totalAmount 计划总额（分＝节点金额合计；未录入节点为 0）
 * @param totalSettled 累计已核到账（分）
 * @param createdAt   创建时间（＝合同冻结同刻）
 * @param nodes       付款节点（录入序；空表＝待后台录入）
 */
public record PaymentPlanResponse(
        String id,
        String orderId,
        String contractId,
        String contractNo,
        Long totalAmount,
        Long totalSettled,
        LocalDateTime createdAt,
        List<PaymentNodeResponse> nodes
) {

    /**
     * 计划＋各节点凭证 → 响应：占比现算（节点金额×100÷计划总额，向下取整
     * ——总额为 0〔未录入〕时节点面为空、无占比可算）。
     */
    public static PaymentPlanResponse of(PaymentPlan plan,
            Function<Long, List<PaymentVoucher>> vouchersByNode) {
        long total = plan.totalAmount();
        List<PaymentNodeResponse> nodes = plan.getNodes().stream()
                .map(node -> PaymentNodeResponse.of(node,
                        total > 0 ? (int) (node.getAmount() * 100 / total) : null,
                        vouchersByNode.apply(node.getId()).stream()
                                .map(PaymentVoucherResponse::of)
                                .toList()))
                .toList();
        return new PaymentPlanResponse(
                plan.getId().toString(),
                plan.getOrderId().toString(),
                plan.getContractId().toString(),
                plan.getContractNo(),
                plan.totalAmount(),
                plan.totalSettled(),
                plan.getCreatedAt(),
                nodes);
    }
}
