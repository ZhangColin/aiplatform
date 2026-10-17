package com.aieducenter.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.business.order.domain.entity.PaymentNode;

/**
 * 付款节点响应（#337 用户面付款面板 + 后台核验承接共用同构）：序号/名称/金额
 * （绝对额分＋占比整数百分比——展示换算现算不落库）/五态/附言码/开工门标志
 * ＋渠道接口位（类型枚举＋流水号可空）＋累计已核到账与「还需支付」推导
 * （{@code remainingAmount}＝未到账节点的 amount − settledAmount）＋本人凭证
 * 清单（追加保留）。
 *
 * @param id            节点标识（TSID 十进制字符串）
 * @param seq           序号（1 起——录入序即展示序）
 * @param name          名称（如「首期开工款」）
 * @param amount        金额（分，绝对额录入）
 * @param percentage    占比（整数百分比，展示换算——计划未录入节点时无此面）
 * @param status        节点状态（code）：1=待支付 2=支付处理中 3=支付失败或关闭
 *                      4=线下转账待核验 5=已到账
 * @param statusName    状态名
 * @param remarkCode    附言码（{@code <合同号>-K<序>}，转账回执关联位）
 * @param kickoffGate   开工门标志（#338 开工判定按本标志取节点集合）
 * @param channelType   渠道类型（code）：1=线下转账 2=扫码（占位）
 * @param channelTypeName 渠道类型名
 * @param channelRefNo  渠道流水号（可空；核验录入时落值）
 * @param settledAmount 累计已核到账（分；不足留痕后仍累积）
 * @param remainingAmount 还需支付（分；前台「还需支付金额与对应节点」的推导面）
 * @param receivedAt    转已到账时点（实到时间；未到账 null）
 * @param vouchers      凭证清单（上传序；上传仅代表已提交）
 */
public record PaymentNodeResponse(
        String id,
        int seq,
        String name,
        Long amount,
        Integer percentage,
        Integer status,
        String statusName,
        String remarkCode,
        boolean kickoffGate,
        Integer channelType,
        String channelTypeName,
        String channelRefNo,
        Long settledAmount,
        Long remainingAmount,
        LocalDateTime receivedAt,
        List<PaymentVoucherResponse> vouchers
) {

    /** 节点＋占比＋凭证 → 响应。 */
    public static PaymentNodeResponse of(PaymentNode node, Integer percentage,
            List<PaymentVoucherResponse> vouchers) {
        return new PaymentNodeResponse(
                node.getId().toString(),
                node.getSeq(),
                node.getName(),
                node.getAmount(),
                percentage,
                node.getStatus().getCode(),
                node.getStatus().getName(),
                node.getRemarkCode(),
                node.isKickoffGate(),
                node.getChannelType().getCode(),
                node.getChannelType().getName(),
                node.getChannelRefNo(),
                node.getSettledAmount(),
                node.remainingAmount(),
                node.getReceivedAt(),
                vouchers);
    }
}
