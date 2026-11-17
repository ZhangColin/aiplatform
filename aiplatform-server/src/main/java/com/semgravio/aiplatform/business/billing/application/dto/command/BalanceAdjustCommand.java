package com.semgravio.aiplatform.business.billing.application.dto.command;

/**
 * 后台余额调整命令（#535 计费⑤）：平台故障退款返还与运营纠错同载体；目标池
 * 必须已存在（BILL_022）。
 *
 * @param accountId 调整账户池（与 projectId 恰选其一）
 * @param projectId 调整项目池（与 accountId 恰选其一）
 * @param delta     调整额（分，非零带符号——正＝补入/返还、负＝追回，BILL_003）
 * @param reason    原因（必填留痕——BILL_005）
 */
public record BalanceAdjustCommand(Long accountId, Long projectId, Long delta, String reason) {
}
