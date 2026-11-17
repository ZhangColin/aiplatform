package com.semgravio.aiplatform.business.project.application.dto.response;

/**
 * 试用体验提示读面载荷（#539 访谈④）：{@code trial=true}＝试用态（提示显），
 * false＝已付费或未知工作区（提示熄/不出）。字段即语义全集——文案在注入脚本侧，
 * 端点只供判定（判定单源＝billing 两轨读点，ADR-0047）。
 *
 * @param trial 试用态判定
 */
public record TrialNoticeResponse(boolean trial) {
}
