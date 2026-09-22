package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "问答卡载荷（kind=3 question 携带，其余 kind 为 null）："
                + "question-raised 事件 payload 原样——{ runId, sessionId, engine, summary, "
                + "engineRef, data }；data.toolCalls=[{id,name,input}]（答复续跑重建所需的"
                + "待确认工具最小面）、data.questions=[{header,question,multiple,custom,"
                + "options[{label}]}]（问答卡投影，custom 恒 true 可自由输入）。"
                + "answered=false 即挂起待答，据此可重建可作答",
                example = "{\"runId\":\"r-01\",\"sessionId\":\"s-01\",\"engine\":\"agentscope\","
                        + "\"summary\":\"目标用户是谁？\",\"engineRef\":\"req-77\","
                        + "\"data\":{\"toolCalls\":[{\"id\":\"tc-1\",\"name\":\"ask_user\","
                        + "\"input\":{\"question\":\"目标用户是谁？\",\"header\":\"目标用户\"}}],"
                        + "\"questions\":[{\"header\":\"目标用户\",\"question\":\"目标用户是谁？\","
                        + "\"multiple\":false,\"custom\":true,"
                        + "\"options\":[{\"label\":\"个人开发者\"}]}]}}")
        Map<String, Object> question,
        @Schema(description = "收尾卡载荷（kind=5 closing 携带，其余 kind 为 null）：run-finish "
                + "收口扩载原样（#88 收尾卡服务端权威事实，版本详情锚定复用；schema 正本＝"
                + "docs/spec/SSE事件清单·收口扩载节）——{ summary, prdChanged, prdNote?, "
                + "systemChanged, systemNote?, files=[{path,added,removed}], durationMs, "
                + "selfTest?{total}, version?, durationBreakdown{llmMs,toolsMs,selfTestMs,"
                + "closingMs,attempts[]} }（可缺省键＝该轮无该事实；durationBreakdown 为"
                + "平台分析口径，呈现可忽略）",
                example = "{\"summary\":\"完成切片：用户能登录\",\"prdChanged\":false,"
                        + "\"systemChanged\":true,\"systemNote\":\"起服了登录页\","
                        + "\"files\":[{\"path\":\"/src/App.jsx\",\"added\":40,\"removed\":0}],"
                        + "\"durationMs\":183420,\"selfTest\":{\"total\":3},"
                        + "\"version\":\"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2\"}")
        Map<String, Object> closing,
        @Schema(description = "圈注附件数组（kind=1 user 可携带，其余 kind 为 null，#97）："
                + "元素＝{attachmentType:\"annotation\", annotation:{kind, anchor:{selector?,"
                + "text?,region?{x,y,width,height}}, note?}}——结构化定位＋标注类型"
                + "（kind=select 点选锚定 / circle 拖框圈区域 / comment 历史兼容），"
                + "据此重建圈注 chip（非截图）",
                example = "[{\"attachmentType\":\"annotation\",\"annotation\":{\"kind\":\"select\","
                        + "\"anchor\":{\"selector\":\"#login-btn\",\"text\":\"登录\"}}}]")
        List<Map<String, Object>> attachments,
        @Schema(description = "报价卡载荷（kind=7 quote 携带，其余 kind 为 null，#203）："
                + "仅事件＋订单引用——{ orderId（字符串，防 JS 精度丢失）, event（quoted=报价已出 / "
                + "repriced=报价已更新）}，不含金额（视镜非快照，ADR-0017——金额/备注/"
                + "状态渲染时取订单当前态）",
                example = "{\"orderId\":\"3897654321098765432\",\"event\":\"quoted\"}")
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
