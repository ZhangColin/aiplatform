package com.semgravio.aiplatform.business.billing.application.dto.command;

import java.math.BigDecimal;

/**
 * 计费运营参数调整命令（#535 计费⑤，全局折算率——#531 落服务、端点随本票）：
 * 值须为正数（BILL_016）；调整只影响此后结算（扣减行快照不漂移）。
 *
 * @param value 参数值（正数——BILL_016）
 */
public record AdjustParamCommand(BigDecimal value) {
}
