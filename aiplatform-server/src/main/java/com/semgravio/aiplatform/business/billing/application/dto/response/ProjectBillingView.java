package com.semgravio.aiplatform.business.billing.application.dto.response;

import java.util.List;

/**
 * 项目账单读面（#534 计费④）：项目池现值＋项目计费态（两轨判定单读点投影）＋
 * 项目池流水（新先序）＋<b>收银台充值目标</b>——「只充当前扣减源池」的服务端
 * 推导：源未选或项目源＝充项目池（充值入账即选定项目源，ADR-0047 动作即选择）；
 * 账户源＝充账户池（前端引导转「我的账户」上下文）。非源池选项不呈现＋入账口
 * BILL_020 双保险（死钱防线）。
 *
 * @param pool                项目池（从未入账＝null）
 * @param sourceSelected      扣减源是否已选定
 * @param deductionSource     扣减源码位（1=账户余额 2=项目余额；未定 null）
 * @param deductionSourceName 扣减源中文名（账户余额/项目余额；未定 null）
 * @param paid                付费状态（翻转不可逆；行缺席＝试用）
 * @param checkoutTarget      收银台充值目标码位（1=我的账户 2=本项目——只充当前扣减
 *                            源池的服务端推导；码位与 {@code DeductionSource} 对齐：
 *                            充向哪个池＝哪个源）
 * @param checkoutTargetName  充值目标中文名（我的账户/本项目）
 * @param entries             项目池流水（新先序；无池＝空表）
 */
public record ProjectBillingView(PoolView pool, boolean sourceSelected, Integer deductionSource,
        String deductionSourceName, boolean paid, Integer checkoutTarget, String checkoutTargetName,
        List<BillingEntryView> entries) {
}
