package com.semgravio.aiplatform.business.order.application.dto.response;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.business.order.domain.aggregate.Contract;
import com.semgravio.aiplatform.business.order.domain.aggregate.Order;
import com.semgravio.aiplatform.business.order.domain.enums.ContractStatus;

/**
 * 合同响应（#336 用户面合同预览卡 + 后台承接口共用同构）：预览字段集（双方
 * 主体/项目范围/付款计划——交付清单/验收方式/工期口径随冻结集、费用随冻结
 * 金额、合同版本即 {@code version}）＋六态＋双方签署位＋范围快照冻结面＋
 * 「需更新」派生标志。PRD 全文冗余拷贝在库不在读面（预览卡不渲染全文，
 * PRD 经版本读面取）。
 *
 * @param id               合同标识（TSID 十进制字符串）
 * @param contractNo       合同编号（SG- 风格，全局唯一）
 * @param orderId          所属订单标识
 * @param status           合同状态（code）：1=准备中 2=待核对与签署 3=签署进行中
 *                         4=签署完成 5=签署失败或过期 6=已撤回或作废
 * @param statusName       状态名
 * @param version          合同版本（草稿同号重录 +1；重建从 1 起）
 * @param needsUpdate      「需更新」派生标志（草稿态且冻结集与订单现值不一致——
 *                         改价/改范围后待后台重录重新冻结；非草稿态恒 false）
 * @param partyA           甲方主体（未录入 null）
 * @param partyB           乙方主体（未录入 null）
 * @param projectScope     项目范围（未录入 null）
 * @param paymentPlan      付款计划文本面（未录入 null；节点对象归付款计划域后续票）
 * @param scopeDeliverables 范围快照·交付清单（冻结副本）
 * @param scopeAcceptance  范围快照·验收方式（冻结副本）
 * @param scopeTimeline    范围快照·工期口径（冻结副本）
 * @param amount           冻结金额（分，随冻结价目行定格）
 * @param currency         币种（v1 恒 CNY）
 * @param prdVersionNo     PRD 确认版本号（#335 冻结集冗余）
 * @param demoVersionNo    Demo 确认版本号引用（#15 冻结集）
 * @param partyASigned     甲方签署位（未签/已签）
 * @param partyASignedAt   甲方落签时点（未签 null）
 * @param partyBSigned     乙方签署位
 * @param partyBSignedAt   乙方落签时点（未签 null）
 * @param createdAt        创建时间（确认范围/后台重建时点）
 */
public record ContractResponse(
        String id,
        String contractNo,
        String orderId,
        ContractStatus status,
        String statusName,
        int version,
        boolean needsUpdate,
        String partyA,
        String partyB,
        String projectScope,
        String paymentPlan,
        String scopeDeliverables,
        String scopeAcceptance,
        String scopeTimeline,
        Long amount,
        String currency,
        Integer prdVersionNo,
        Integer demoVersionNo,
        boolean partyASigned,
        LocalDateTime partyASignedAt,
        boolean partyBSigned,
        LocalDateTime partyBSignedAt,
        LocalDateTime createdAt
) {

    /** 聚合＋订单现值 → 响应（「需更新」对照订单现值现算，不落库）。 */
    public static ContractResponse of(Contract contract, Order order) {
        return new ContractResponse(
                contract.getId().toString(),
                contract.getContractNo(),
                contract.getOrderId().toString(),
                contract.getStatus(),
                contract.getStatus().getName(),
                contract.getVersion(),
                contract.needsUpdate(order),
                contract.getPartyA(),
                contract.getPartyB(),
                contract.getProjectScope(),
                contract.getPaymentPlan(),
                contract.getScopeDeliverables(),
                contract.getScopeAcceptance(),
                contract.getScopeTimeline(),
                contract.getAmount(),
                contract.getCurrency(),
                contract.getPrdVersionNo(),
                contract.getDemoVersionNo(),
                contract.isPartyASigned(),
                contract.getPartyASignedAt(),
                contract.isPartyBSigned(),
                contract.getPartyBSignedAt(),
                contract.getCreatedAt());
    }
}
