package com.semgravio.aiplatform.business.project.application.dto.command;

import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;

/**
 * 「继续讨论」引导流命令（#544 访谈③，翻转门①——岔路口三选卡付费项回传）：
 * 随卡一次选定扣减源（「由我的账户付费」＝1 账户余额／「由本项目付费」＝2 项目
 * 余额——第一笔付费选择永远最先选）；空＝不改动既有选定——后台已充项目池的免选
 * 直翻转（销售演示动线）与到账后重判 settle（源池回正即翻转、无需重走选择）。
 * 枚举类型直收（BaseEnum 契约线上 Integer code 双向，非法值框架统一 400）。
 *
 * @param source 扣减源选定（可空——空＝不改动既有选定〔源未定且空＝BILL_010〕）
 */
public record ContinueDiscussionCommand(DeductionSource source) {
}
