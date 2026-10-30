package com.semgravio.aiplatform.business.project.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型供应商通道更新命令（#367，PUT 全量语义）：请求体即通道行终态——baseUrl
 * 全量替换；apiKey 与 enabled 缺省＝<b>保持现值</b>（两肢同是「不可/不宜因缺省
 * 翻转」的终态字段：apiKey 读面只出掩码、缺省清空＝读一次即丢 key；enabled 是
 * 有意状态、缺省归一 true 会把停用通道在换 key 时意外复启——与 agent-configs
 * 开关「缺省归一 true」不同属有意：那边 null 有回落枚举缺省语义，这边行恒存在
 * 无回落概念）。
 *
 * <p><b>apiKey 特例（掩码回显的必然对偶）</b>：null/空白＝保持现值（round-trip
 * 安全），携现值掩码形（如 ****abcd）同判保持；换 key 才写全量新值。清除 key
 * 无写面（下线通道走 enabled=false，凭证随行留置不影响装配——停用即明确报错）。</p>
 *
 * @param baseUrl 通道 baseUrl（必填；终态全量替换）
 * @param apiKey  全量新值＝换 key；null/空白/现值掩码形＝保持现值
 * @param enabled 缺省＝保持现值；显式 true/false 才翻转
 */
public record ModelProviderUpdateCommand(
        @Schema(description = "通道 baseUrl（终态全量替换，必填）",
                example = "https://ark.cn-beijing.volces.com/api/v3")
        String baseUrl,
        @Schema(description = "apiKey：写全量新值＝换 key（写面唯一明文入口）；null/空白/"
                + "等于现值掩码形＝保持现值（读面掩码回显的 round-trip 安全语义）。"
                + "清除 key 无写面——下线通道走 enabled=false")
        String apiKey,
        @Schema(description = "启用态（缺省＝保持现值，显式 true/false 才翻转——停用通道"
                + "换 key/换地址不被意外复启；停用＝引用该通道的装配明确报错，不静默换道）")
        Boolean enabled) {
}
