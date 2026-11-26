package com.semgravio.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 候选行更新命令（#559/T5，PUT——换指向/启停/移动，寻址＝队列序）。全部字段
 * 缺省＝<b>保持现值</b>（启停/移动不必重报指向——curl 运维人体工学；与通道面
 * apiKey/enabled 同「不可因缺省翻转」语义）。
 *
 * @param providerKey 换指向通道键（缺省＝保持；显式值须已有行——PRJ_071）
 * @param modelName   换指向实际模型名（缺省＝保持；与 providerKey 各自独立可换）
 * @param enabled     行级启停（缺省＝保持；显式 true/false 才翻转）
 * @param priority    移动目标队列序（缺省＝原地不动；显式 1..队列长＝移动——
 *                    区间候选顺移补位；越界 PRJ_090）
 */
public record ModelConfigCandidateUpdateCommand(
        @Schema(description = "换指向通道键（缺省＝保持现值）", example = "deepseek-2")
        String providerKey,
        @Schema(description = "换指向实际模型名（缺省＝保持现值）", example = "deepseek-flash")
        String modelName,
        @Schema(description = "行级启停（缺省＝保持现值；显式 true/false 才翻转——"
                + "false＝读面剔除不参队）")
        Boolean enabled,
        @Schema(description = "移动目标队列序（缺省＝原地不动；显式值＝移动，区间候选"
                + "顺移补位——排序即此）", example = "1")
        Integer priority) {
}
