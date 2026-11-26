package com.semgravio.aiplatform.business.project.application.dto.response;

import com.semgravio.aiplatform.business.billing.application.BalanceReminderAppService;

/**
 * 待决余额提醒卡读面（#537 计费③段间硬门，卡可回）：SSE 事件与 GET 读面同形的
 * 卡面——被拦输入引用＋当前负额显式素材＋充值入口指引。
 *
 * @param holdId            留存 id（卡锚；字符串化防 JS 精度丢失；run 场拦截无留存＝null）
 * @param lane              被拦车道（OPINION=意见 / INQUIRY=咨询；run 场拦截无留存＝null）
 * @param prompt            被拦输入原文（卡上引用呈现；run 场拦截无留存＝null）
 * @param sourcePoolBalance 源池余额快照（分；显负额素材——读面现取，卡显当前负额）
 * @param runId             余额提醒卡事件锚（挂 /api/events?runId=）
 */
public record BalanceReminderCardResponse(String holdId, String lane, String prompt,
        Long sourcePoolBalance, String runId) {

    /** 计费卡面 → 响应（装配归控制器——应用层记录直映，不二次发明字段）。 */
    public static BalanceReminderCardResponse of(BalanceReminderAppService.BalanceReminderCard card) {
        return new BalanceReminderCardResponse(card.holdId(), card.lane(), card.prompt(),
                card.sourcePoolBalance(), card.runId());
    }
}
