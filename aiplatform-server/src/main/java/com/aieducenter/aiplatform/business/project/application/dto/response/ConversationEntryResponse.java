package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

import com.aieducenter.aiplatform.business.project.domain.aggregate.ConversationEntry;

/**
 * 对话史条目读面（#89 水合载荷）：kind 为 Integer code（§3.6.1 边界枚举统一 code
 * ——{@code ConversationEntryKind} 1=user 2=agent 3=question 4=answer 5=closing
 * 6=guide），question / closing 为事件载荷 JSON 原样。
 */
public record ConversationEntryResponse(
        Long id,
        Integer kind,
        String runId,
        String text,
        Map<String, Object> question,
        Map<String, Object> closing,
        boolean answered,
        LocalDateTime at) {

    /** 实体 → 读面（kind 出 Integer code）。 */
    public static ConversationEntryResponse of(ConversationEntry entry) {
        return new ConversationEntryResponse(
                entry.getId(),
                entry.getKind().getCode(),
                entry.getRunId(),
                entry.getText(),
                entry.getQuestion(),
                entry.getClosing(),
                Boolean.TRUE.equals(entry.getAnswered()),
                entry.getCreatedAt());
    }
}
