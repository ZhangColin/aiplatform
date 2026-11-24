package com.semgravio.aiplatform.business.project.application.dto.response;

/**
 * 定稿响应（#291 定稿机制——稿卡显式动作的 REST 回执）：定稿锚（成版已退役，#605）。
 *
 * @param runId       定稿锚（与定稿收尾卡同锚）
 * @param versionHash 恒为 null（Run-Id 成版提交随审查与收口组退役 #605；字段留至
 *                    设计线整条退役清理）
 */
public record DesignFinalizedResponse(String runId, String versionHash) {
}
