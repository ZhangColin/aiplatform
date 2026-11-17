package com.semgravio.aiplatform.business.project.application.dto.command;

import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;

/**
 * 拦截卡确认扣减命令（#533 计费②门②）：扣减源未定的项目随卡完成首次选源——
 * 「本项目余额／我的账户」（第一笔付费选择永远最先选）；源已选定（后台已充项目
 * 池＝入账即选择）则免选直接确认。枚举类型直收（BaseEnum 契约线上 Integer code
 * 双向，非法值框架统一 400）。
 *
 * @param source 扣减源选定（可空——空＝不改动既有选定〔后台已充项目池的免选确认〕；
 *               源未定且空＝BILL_010 与翻转前置同语义）
 */
public record ConfirmInterceptCommand(DeductionSource source) {
}
