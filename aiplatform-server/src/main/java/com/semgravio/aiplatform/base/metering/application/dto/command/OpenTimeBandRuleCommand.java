package com.semgravio.aiplatform.base.metering.application.dto.command;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 开行命令（#497 时段规则写口）：空通道首开的初始插入通道——种子数据经幂等
 * 签名脚本走本端点种入（脚本侧幂等＝通道已有任意行即不再开行）。一次调用原子
 * 落 N 行窗口行＝一个规则版本（DeepSeek 峰窗 01:00–04:00＋06:00–10:00 两窗口
 * 一单种入）。窗口项字段合法性由聚合守卫裁决（METER_013）；同通道生效区间
 * 重叠（同起点或跨区间）服务端校验 METER_008（对称单价表）。
 *
 * @param provider      模型提供方（与 UsageEvent.provider 同键＝匹配键全键）
 * @param windows       时段窗口清单（≥1 项；一项＝规则表一行，同生效区间）
 * @param effectiveFrom 生效起点（可空＝缺省即时；可回溯——种子口径覆盖存量事件；
 *                      含未来时点＝预发布；同通道重叠 METER_008）
 */
public record OpenTimeBandRuleCommand(
        String provider,
        @Schema(description = "时段窗口清单（≥1 项；每项＝规则表一行〔通道 × 生效区间 × "
                + "星期口径 × UTC 窗口〕，同单各窗口共享生效起点＝一个规则版本）")
        List<TimeWindowCommand> windows,
        Instant effectiveFrom
) {
}
