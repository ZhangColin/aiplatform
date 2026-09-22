package com.aieducenter.aiplatform.base.metering.application.dto.command;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 开行命令（#165 单价表写口唯一化）：空键首行的初始插入通道——种子数据经幂等
 * 签名脚本走本端点种入（脚本侧幂等：匹配键已有任意行即不再开行）。字段合法性
 * 由聚合守卫裁决（METER_004/METER_010），不在命令层重复校验——同
 * {@code RepricePriceEntryCommand} 形制。
 *
 * @param provider      模型提供方
 * @param model         模型
 * @param tokenKind     token 档位（契约 Integer code：1=input 2=output 3=cache_read
 *                      4=cache_write 5=reasoning，TokenKind 房规）
 * @param unitPrice     每 token 单价（非负；0＝免费档）
 * @param currency      币种（ISO 4217 代码，如 USD）
 * @param effectiveFrom 生效起点（可空＝缺省即时；可回溯——种子口径 2026-01-01
 *                      敞口覆盖存量事件；含未来时点＝预发布；同键重叠 METER_008）
 */
public record OpenPriceEntryCommand(
        String provider,
        String model,
        TokenKind tokenKind,
        @Schema(description = "每 token 单价（入参侧为 number；非负，0＝免费档。"
                + "响应侧读回为十进制字符串——两侧类型有意不对称，照实各自呈现）",
                example = "0.00000132")
        BigDecimal unitPrice,
        String currency,
        Instant effectiveFrom
) {
}
