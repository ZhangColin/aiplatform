package com.semgravio.aiplatform.business.billing.application.dto.response;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;

/**
 * 流水行读面（#534 计费④「我的账户」与项目账单共用）：append-only 账本的单行
 * 投影——金额带符号（入账正/扣减负/调整带符号，分）＋行后余额＋种类中文名
 * （枚举出口配 *Name，#186 先例——消费端零映射）。扣减行带 projectId（段归属），
 * 账户池的扣减行可据此在前端并联项目名。
 *
 * @param entryId       流水行 id（TSID 时间有序——倒序即新先）
 * @param kind          行种类码位（1=入账 2=扣减 3=调整）
 * @param kindName      行种类中文名（入账/扣减/调整）
 * @param projectId     段归属项目（扣减行必落；入账/调整行可空——字符串化防 JS 精度丢失）
 * @param amount        金额（分，带符号）
 * @param balanceAfter  行落定后池余额（分，可负）
 * @param note          备注（充值/调整留痕面，可空）
 * @param operatorName  后台动作操作者名（平台/自助动作落 null）
 * @param createdAt     落定时间（ISO-8601）
 */
public record BillingEntryView(String entryId, Integer kind, String kindName, String projectId,
        long amount, long balanceAfter, String note, String operatorName, LocalDateTime createdAt) {

    /** 流水行 → 读面（装配归应用层读口，控制器直出）。 */
    public static BillingEntryView of(BillingEntry entry) {
        Long projectId = entry.getProjectId();
        return new BillingEntryView(String.valueOf(entry.getId()), entry.getKind().getCode(),
                entry.getKind().getName(), projectId == null ? null : String.valueOf(projectId),
                entry.getAmount(), entry.getBalanceAfter(), entry.getNote(),
                entry.getOperatorName(), entry.getCreatedAt());
    }
}
