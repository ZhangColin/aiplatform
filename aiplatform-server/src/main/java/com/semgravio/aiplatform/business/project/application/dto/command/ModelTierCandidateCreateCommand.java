package com.semgravio.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 候选行创建命令（#559/T5，POST——追加/插位）。候选身份＝通道×实际模型名
 * （队列内唯一——同身份重复 409 PRJ_091：切换单元恰是故障物理单元，同单元
 * 重复无备胎价值）。
 *
 * @param providerKey 通道键（必填；须已有行——PRJ_071，FK 裸错的读面化）
 * @param modelName   实际模型名（必填；计量/单价行匹配腿）
 * @param priority    队列序（缺省＝追加队尾；显式 1..队尾+1＝插位——后续顺移；
 *                    越界 PRJ_090）
 * @param enabled     行级启停（缺省 true；false＝读面剔除不参队——显式下线单候选）
 */
public record ModelTierCandidateCreateCommand(
        @Schema(description = "通道键（一行＝endpoint×账号 key；须已在通道面建行）",
                example = "deepseek-2")
        String providerKey,
        @Schema(description = "实际模型名（计量/单价行匹配腿）", example = "deepseek-flash")
        String modelName,
        @Schema(description = "队列序（缺省＝追加队尾；显式值＝插位——既有候选顺移；"
                + "主备＝队列前两名）", example = "2")
        Integer priority,
        @Schema(description = "行级启停（缺省 true；false＝读面剔除不参队，装配跳过该候选）")
        Boolean enabled) {
}
