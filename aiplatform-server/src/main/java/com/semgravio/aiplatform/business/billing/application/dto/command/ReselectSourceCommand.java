package com.semgravio.aiplatform.business.billing.application.dto.command;

/**
 * 后台扣减源调整命令（#535 计费⑤，用户选错池的救济通道）：覆盖既有选定＋
 * 操作者留痕；码位与 {@code DeductionSource} 对齐（1=账户余额 2=项目余额）。
 *
 * @param source    新扣减源（Integer code，必填——未知码如实拒绝）
 * @param accountId 选「账户余额」时的账户定格（必填——BILL_009）；项目源忽略
 */
public record ReselectSourceCommand(Integer source, Long accountId) {
}
