package com.semgravio.aiplatform.business.billing.application.dto.response;

import java.util.List;

/**
 * 「我的账户」读面（#534 计费④）：账户池现值＋账户池流水（append-only，新先
 * 序）——双池余额的另一池（项目余额）经项目账单读面逐项目呈现（前端拿既有
 * 项目清单并联，billing 不跨 BC  join 项目名）；账户池扣减行带 projectId，
 * 前端可并联项目名呈现「某项目消耗」。
 *
 * @param accountPool 我的账户池（从未充值＝null——呈现零态归前端，不造假池）
 * @param entries     账户池流水（新先序；无池＝空表）
 */
public record AccountBillingView(PoolView accountPool, List<BillingEntryView> entries) {
}
