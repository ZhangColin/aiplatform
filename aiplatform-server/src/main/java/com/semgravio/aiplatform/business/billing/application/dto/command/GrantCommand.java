package com.semgravio.aiplatform.business.billing.application.dto.command;

/**
 * 后台赠送命令（#535 计费⑤）：目标账户与项目<b>恰选其一</b>（显式传 accountId＝
 * 入账户池；显式传 projectId＝入项目池〔动作即选定该项目扣减源，销售演示动线〕——
 * 无双缺省——双缺/双给皆 400 BILL_001）。
 *
 * @param accountId 入账账户（与 projectId 恰选其一）
 * @param projectId 入账项目（与 accountId 恰选其一）
 * @param amountFen 赠送金额（分，正整数——BILL_002）
 * @param note      备注（可空——留痕面）
 */
public record GrantCommand(Long accountId, Long projectId, Long amountFen, String note) {
}
