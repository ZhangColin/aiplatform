package com.semgravio.aiplatform.business.order.application.dto.command;

import java.util.List;

import com.semgravio.aiplatform.business.order.domain.model.PaymentNodeSpec;

/**
 * 付款节点录入命令（#337 后台机机面，「付款节点录入/开工门设定」）：整组
 * 替换——不收半组，每次全量提交；序号按列表序服务端派生（录入序即展示序），
 * 附言码随序自合同号定格。字段合法性由聚合守卫裁决（ORD_039/044），不在
 * 命令层重复校验。
 *
 * @param nodes 节点规格（≥1 条：名称/金额〔分〕/开工门标志）
 */
public record EnterPaymentNodesCommand(
        List<PaymentNodeSpec> nodes
) {
}
