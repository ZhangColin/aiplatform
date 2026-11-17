package com.semgravio.aiplatform.business.project.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 五问额度读面（#538 访谈①，ADR-0048）：试用态访谈期随「第 N/5」进度 chip 呈现
 * 的服务端单源（前端不自算）。answered = 已答问答缺口数（同缺口重派不另计，计数
 * 归 {@code ConversationHistoryAppService.answeredGapCount}）；total 机制定 5
 * （数值属运营面，#527 出界）。非试用态访谈期（付费翻转 / PRD 已产出 / 已生成）
 * 读面整体缺席（null）——chip 不呈现。
 */
@Schema(description = "五问额度读面（试用态访谈期）：已答问答缺口数 / 总额度。付费态、"
        + "PRD 已产出或已生成（非访谈期）时整体缺席（data=null）——「第 N/5」进度 chip "
        + "不呈现。计数服务端单源，前端不自算")
public record InterviewQuotaResponse(

        @Schema(description = "已答问答缺口数（去重：同缺口重派不另计）", example = "3")
        int answered,

        @Schema(description = "提问额度总额（机制定 5，数值属运营面）", example = "5")
        int total) {
}
