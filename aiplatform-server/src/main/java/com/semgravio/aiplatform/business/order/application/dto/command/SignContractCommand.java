package com.semgravio.aiplatform.business.order.application.dto.command;

import com.semgravio.aiplatform.business.order.domain.enums.ContractParty;

/**
 * 合同签署标记命令（#336，电子签渠道真接前的后台承接口径）：落一方签署位＋
 * 时点。合法性由聚合守卫裁决（ORD_021/022/023/028），不在命令层重复校验。
 *
 * @param party 签署位（1=甲方〔客户方〕 2=乙方〔服务方〕）
 * @param basis 依据（必填，至多 1000 字）
 */
public record SignContractCommand(
        ContractParty party,
        String basis
) {
}
