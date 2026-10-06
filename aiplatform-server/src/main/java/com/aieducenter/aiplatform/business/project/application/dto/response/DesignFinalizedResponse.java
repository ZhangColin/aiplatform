package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 定稿响应（#291 定稿机制——稿卡显式动作的 REST 回执）：定稿锚与成版事实。
 *
 * @param runId       定稿锚（定稿收尾卡与成版 commit Run-Id trailer 同锚——版本
 *                    详情联接对话史 closing 条目）
 * @param versionHash 成版 commit hash（null＝本轮未成版——成版失败不反噬定稿，
 *                    排查走日志；收尾卡同口径缺 version 键）
 */
public record DesignFinalizedResponse(String runId, String versionHash) {
}
