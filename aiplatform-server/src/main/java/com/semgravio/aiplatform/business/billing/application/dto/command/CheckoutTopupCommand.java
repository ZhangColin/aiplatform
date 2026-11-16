package com.semgravio.aiplatform.business.billing.application.dto.command;

/**
 * 模拟收银台充值命令（#534 计费④）：准入码＋金额——目标池由端点承载隐式决定
 * （账户上下文＝我的账户池；项目上下文＝本项目池〔只充当前扣减源池，非源池
 * 服务端 BILL_020 拒绝——前端不呈现该选项〕）。
 *
 * @param accessCode 充值准入码（复校验——独立校验端点可被绕过，入账以本口为准）
 * @param amountFen  充值金额（分，正整数——BILL_002；预设档＋自定义的取值归前端）
 */
public record CheckoutTopupCommand(String accessCode, Long amountFen) {
}
