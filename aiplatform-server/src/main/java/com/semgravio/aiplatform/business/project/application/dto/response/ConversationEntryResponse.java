package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.business.project.domain.aggregate.ConversationEntry;

/**
 * 对话史条目读面（#89 水合载荷）：kind 为 Integer code + kindName 中文名随行
 * （#186 平台房规：枚举出口配 *Name，消费端零映射；§3.6.1 边界枚举统一 code
 * ——{@code ConversationEntryKind} 1=user 2=agent 3=question 4=answer 5=closing
 * 6=guide 7=quote 8=run_failure），question / closing 为事件载荷 JSON 原样；attachments（#97
 * 圈注 B 档、#286 扩图片物料）= 用户发言随带的消息附件 JSON 数组（回显重建附件 chip 用）；
 * quote（#203 报价卡）= 事件 + 订单引用（不含金额——视镜语义，ADR-0017）；
 * failure（#418 失败留账）= run 失败终态失败面（与 run-failed SSE 扩载同载荷）。
 * #610 设计线整条退役：会话来源标识（sessionSource/sessionSourceName）拆除。
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
                + "options[{label}],recommended?}]（问答卡投影，custom 恒 true 可自由输入；"
                + "recommended 可缺省＝推荐答案 #459，命中 options 才携带）；"
                + "data.questionOrdinal＝「第 N 问」序号（平台注入，见顶层 questionOrdinal）。"
                + "answered=false 即挂起待答，据此可重建可作答",
                example = "{\"runId\":\"r-01\",\"sessionId\":\"s-01\",\"engine\":\"agentscope\","
                        + "\"summary\":\"目标用户是谁？\",\"engineRef\":\"req-77\","
                        + "\"data\":{\"toolCalls\":[{\"id\":\"tc-1\",\"name\":\"ask_user\","
                        + "\"input\":{\"question\":\"目标用户是谁？\",\"header\":\"目标用户\"}}],"
                        + "\"questions\":[{\"header\":\"目标用户\",\"question\":\"目标用户是谁？\","
                        + "\"multiple\":false,\"custom\":true,"
                        + "\"options\":[{\"label\":\"个人开发者\"}],"
                        + "\"questionOrdinal\":1}]}}")
        Map<String, Object> question,
        @Schema(description = "「第 N 问」序号（#538/#549 五问额度呈现面，ADR-0048 服务端"
                + "单源）：kind=3 question 携带——缺口首次出现序 1..k（同缺口重派复用旧号"
                + "不跳号，挂起卡也占号），读口按对话史重算（存量行自动有号）；其余 kind "
                + "为 null。前端仅试用访谈期（额度读面在场）渲染问答卡序号徽标")
        Integer questionOrdinal,
        @Schema(description = "收尾卡载荷（kind=5 closing 携带，其余 kind 为 null）：run-finish "
                + "收口扩载原样（#617 五字段收尾卡服务端权威事实；schema 正本＝"
                + "docs/spec/SSE事件清单·收口扩载节）——{ narrative?, "
                + "criteria{buildPassed,previewPassed}, files=[{path,added,removed}], "
                + "directories=[{path,fileCount,added,removed}]（#620 首建目录汇总，迭代缺省）, "
                + "durationMs, durationBreakdown{llmMs,toolsMs,closingMs,overhead{"
                + "configMs,evalMs,eventPipelineMs,gateSkipped,gateEvaluated,iterations},"
                + "attempts[]} }（可缺省键＝"
                + "该轮无该事实；durationBreakdown 为平台分析口径，呈现可忽略；"
                + "旧平台句式摘要 summary 与「需求文档／系统」判定键自 #617 退役、"
                + "存量旧行由前端读侧回落；version 锚随审查与收口组退役 #605；"
                + "modelTier 随模型路由退役 #606）",
                example = "{\"narrative\":\"完成首次生成：构建通过、预览可访问\","
                        + "\"criteria\":{\"buildPassed\":true,\"previewPassed\":true},"
                        + "\"files\":[{\"path\":\"/src/App.jsx\",\"added\":40,\"removed\":0}],"
                        + "\"durationMs\":183420}")
        Map<String, Object> closing,
        @Schema(description = "消息附件数组（kind=1 user 可携带，其余 kind 为 null；#97 圈注起立、"
                + "#286 扩图片物料）：两形态——{attachmentType:\"annotation\", annotation:{kind, "
                + "anchor:{selector?,text?,region?{x,y,width,height}}, note?}}（结构化定位＋"
                + "标注类型，kind=select 点选锚定 / circle 拖框圈区域 / comment 历史兼容，"
                + "据此重建圈注 chip，非截图）与 {attachmentType:\"image\", name, path}"
                + "（图片物料——path 为工作区路径引用，据此重建物料 chip、取件走 files/raw）",
                example = "[{\"attachmentType\":\"annotation\",\"annotation\":{\"kind\":\"select\","
                        + "\"anchor\":{\"selector\":\"#login-btn\",\"text\":\"登录\"}}},"
                        + "{\"attachmentType\":\"image\",\"name\":\"logo.png\","
                        + "\"path\":\"materials/3897654321098765432-logo.png\"}]")
        List<Map<String, Object>> attachments,
        @Schema(description = "报价卡载荷（kind=7 quote 携带，其余 kind 为 null，#203）："
                + "仅事件＋订单引用——{ orderId（字符串，防 JS 精度丢失）, event（quoted=报价已出 / "
                + "repriced=报价已更新）}，不含金额（视镜非快照，ADR-0017——金额/备注/"
                + "状态渲染时取订单当前态）",
                example = "{\"orderId\":\"3897654321098765432\",\"event\":\"quoted\"}")
        Map<String, Object> quote,
        @Schema(description = "失败面载荷（kind=8 run_failure 失败留账携带，其余 kind 为 null，"
                + "#418 失败片落库面）：与 run-failed SSE 扩载同载荷——{ stopped?, error?, "
                + "durationMs, attempts[{attempt,durationMs,modelId,llmMs,toolsMs}] }"
                + "（可缺省键＝该场无该事实；attempts 跨轮累计——回滚重跑轮"
                + "不清账。平台分析口径，前端不渲染（同 durationBreakdown）；"
                + "verifyMs 随验证机器退役 #605）",
                example = "{\"error\":\"模型响应超时\",\"durationMs\":183420,"
                        + "\"attempts\":[{\"attempt\":1,\"durationMs\":61200,"
                        + "\"modelId\":\"deepseek-v4-flash\",\"llmMs\":58000,\"toolsMs\":2000}]}")
        Map<String, Object> failure,
        boolean answered,
        LocalDateTime at) {

    /** 实体 → 读面（kind 出 Integer code + kindName 中文名；questionOrdinal 仅问答卡
     *  携带——「第 N 问」序号，读口重算）。 */
    public static ConversationEntryResponse of(ConversationEntry entry,
            Integer questionOrdinal) {
        return new ConversationEntryResponse(
                entry.getId(),
                entry.getKind().getCode(),
                entry.getKind().getName(),
                entry.getRunId(),
                entry.getText(),
                entry.getQuestion(),
                questionOrdinal,
                entry.getClosing(),
                entry.getAttachments(),
                entry.getQuote(),
                entry.getFailure(),
                Boolean.TRUE.equals(entry.getAnswered()),
                entry.getCreatedAt());
    }
}
