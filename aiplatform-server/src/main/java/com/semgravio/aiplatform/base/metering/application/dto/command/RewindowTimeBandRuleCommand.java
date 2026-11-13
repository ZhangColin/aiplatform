package com.semgravio.aiplatform.base.metering.application.dto.command;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 改窗命令（#497 原子改窗——对称单价表原子改价）：单调用「关当前版本全部行＋
 * 开新版本」同事务；新窗口/星期口径取本命令，通道沿用被关版本。窗口项字段
 * 合法性由聚合守卫裁决（METER_013），不在命令层重复校验——同
 * {@code RepricePriceEntryCommand} 形制。
 *
 * @param windows       新时段窗口清单（≥1 项；整版本替换语义——清单即终态）
 * @param effectiveFrom 新版本生效起点（可空＝缺省即时；含未来时点＝预发布，对齐
 *                      供应商调窗口公告期；须不早于被关版本起点，否则 METER_014）
 */
public record RewindowTimeBandRuleCommand(
        @Schema(description = "新时段窗口清单（≥1 项；整版本替换语义——清单即终态，"
                + "被关版本的全部窗口不再延续）")
        List<TimeWindowCommand> windows,
        Instant effectiveFrom
) {
}
