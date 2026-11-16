package com.semgravio.aiplatform.business.billing.application.dto.command;

/**
 * 模拟收银台准入码校验命令（#534 计费④）：码错误/缺失被拦并如实引导
 * （BILL_019 message 含「联系平台索取」指引）——校验独立成端点，正确码才
 * 进入金额选择（前端分步闸；入账端点自身仍复校验，防绕过）。
 *
 * @param code 充值准入码（空白归一后比对配置面固定码）
 */
public record CheckoutAccessCodeCommand(String code) {
}
