package com.aieducenter.aiplatform.business.order.domain.model;

/**
 * 付款节点录入项（#337 后台节点录入/开工门设定的单条规格）：名称＋绝对额
 * （分）＋开工门标志。序号不在录入面——按列表序服务端派生（录入序即展示序，
 * 附言码随序定格）；占比是展示换算（前台以节点金额/计划总额现算），不落库。
 * 字段合法性由聚合守卫裁决（ORD_039），不在本模型重复校验。
 *
 * @param name        节点名称（必填，如「首期开工款」）
 * @param amount      金额（分，正数——绝对额录入）
 * @param kickoffGate 开工门标志（后台录入时定；开工款≠默认全款——通常首节点、
 *                    不默认全款勾选，#338 开工判定按本标志取节点集合）
 */
public record PaymentNodeSpec(
        String name,
        Long amount,
        boolean kickoffGate
) {
}
