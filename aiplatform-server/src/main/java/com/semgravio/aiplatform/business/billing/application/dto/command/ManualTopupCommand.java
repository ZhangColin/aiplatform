package com.semgravio.aiplatform.business.billing.application.dto.command;

import java.time.Instant;

/**
 * 后台手动充值录入命令（#535 计费⑤，对公转账——仿凭证核验留痕）：实到金额/
 * 到账时间/依据三字段必填（缺失即拒 BILL_021）；核验字段随动作明细落痕。
 *
 * @param accountId         入账账户（与 projectId 恰选其一）
 * @param projectId         入账项目（与 accountId 恰选其一）
 * @param receivedAmountFen 实到金额（分，正整数——BILL_002）
 * @param receivedAt        实到时间（ISO-8601 Instant，必填——BILL_021）
 * @param evidence          依据（必填——银行回单号/转账备注等，BILL_021）
 * @param note              备注（可空——缺省落「对公转账手动充值」）
 */
public record ManualTopupCommand(Long accountId, Long projectId, Long receivedAmountFen,
        Instant receivedAt, String evidence, String note) {
}
