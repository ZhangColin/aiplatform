package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.business.project.domain.aggregate.ConversationEntry;

/**
 * 对话史条目读面（#89 水合载荷）：kind 为 Integer code + kindName 中文名随行
 * （#186 平台房规：枚举出口配 *Name，消费端零映射；§3.6.1 边界枚举统一 code
 * ——{@code ConversationEntryKind} 1=user 2=agent 3=question 4=answer 5=closing
 * 6=guide 7=quote），question / closing 为事件载荷 JSON 原样；attachments（#97
 * 圈注 B 档）= 用户发言随带的圈注附件 JSON 数组（消息回显重建圈注 chip 用）；
 * quote（#203 报价卡）= 事件 + 订单引用（不含金额——视镜语义，ADR-0017）。
 */
public record ConversationEntryResponse(
        Long id,
        Integer kind,
        String kindName,
        String runId,
        String text,
        Map<String, Object> question,
        Map<String, Object> closing,
        List<Map<String, Object>> attachments,
        Map<String, Object> quote,
        boolean answered,
        LocalDateTime at) {

    /** 实体 → 读面（kind 出 Integer code + kindName 中文名）。 */
    public static ConversationEntryResponse of(ConversationEntry entry) {
        return new ConversationEntryResponse(
                entry.getId(),
                entry.getKind().getCode(),
                entry.getKind().getName(),
                entry.getRunId(),
                entry.getText(),
                entry.getQuestion(),
                entry.getClosing(),
                entry.getAttachments(),
                entry.getQuote(),
                Boolean.TRUE.equals(entry.getAnswered()),
                entry.getCreatedAt());
    }
}
