package com.aieducenter.aiplatform.base.metering.application.dto.command;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 改价命令（#160 后台机机面）：单调用原子「关当前行＋开新行」——新行沿用被关
 * 行的匹配键（provider/model/tokenKind），单价与币种取本命令。字段合法性由
 * 聚合守卫裁决（METER_004/METER_010），不在命令层重复校验——同
 * {@code SubmitQuoteCommand} 形制。
 *
 * @param unitPrice     新每 token 单价（非负；0＝免费档）
 * @param currency      新币种（ISO 4217 代码，如 USD）
 * @param effectiveFrom 新行生效起点（可空＝缺省即时；含未来时点＝预发布，对齐
 *                      供应商凌晨调价；须不早于被关行起点，否则 METER_005）
 */
public record RepricePriceEntryCommand(
        @Schema(description = "新每 token 单价（入参侧为 number；非负，0＝免费档。"
                + "响应侧读回为十进制字符串——两侧类型有意不对称，照实各自呈现）",
                example = "0.00000132")
        BigDecimal unitPrice,
        String currency,
        Instant effectiveFrom
) {
}
