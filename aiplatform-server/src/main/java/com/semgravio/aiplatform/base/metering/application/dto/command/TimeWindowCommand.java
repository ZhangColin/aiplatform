package com.semgravio.aiplatform.base.metering.application.dto.command;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.base.metering.domain.enums.Band;

/**
 * 时段窗口命令项（#497 开行/改窗命令的清单元素）：一条 UTC 时段窗口＝时段规则
 * 表一行。一次命令带 N 个窗口项＝一个规则版本（同生效区间原子落 N 行）。
 * 字段合法性由聚合守卫裁决（METER_013），不在命令层重复校验——同
 * {@code OpenPriceEntryCommand} 形制。数值字段用包装型：缺字段→null→守卫
 * 出干净 400，而非拆箱 NPE 出 500。
 *
 * @param band        时段带（契约 Integer code：1=峰 2=谷，Band 房规）
 * @param dayMask     星期口径 bitmask（ISO 周序 Mon=1 Tue=2 … Sun=64，1..127 非零）
 * @param startMinute UTC 窗口起点（含），0..1439
 * @param endMinute   UTC 窗口终点（不含），1..1440；start &lt; end（跨午夜窗口
 *                    拆两行——表示法定案见 #497 票内评论）
 */
public record TimeWindowCommand(
        Band band,
        @Schema(description = "星期口径 bitmask：ISO 周序 Mon=1 Tue=2 Wed=4 Thu=8 "
                + "Fri=16 Sat=32 Sun=64，合法域 1..127（如工作日＝31）", example = "31")
        Integer dayMask,
        @Schema(description = "UTC 窗口起点分钟（含），0..1439（如 01:00＝60）", example = "60")
        Integer startMinute,
        @Schema(description = "UTC 窗口终点分钟（不含），1..1440（如 04:00＝240）；"
                + "须大于起点——跨午夜窗口拆两行（22:00–02:00＝22:00–24:00＋00:00–02:00）",
                example = "240")
        Integer endMinute
) {
}
