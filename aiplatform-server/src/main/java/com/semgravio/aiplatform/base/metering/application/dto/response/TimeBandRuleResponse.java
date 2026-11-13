package com.semgravio.aiplatform.base.metering.application.dto.response;

import java.time.Instant;

import com.semgravio.aiplatform.base.metering.domain.aggregate.TimeBandRule;
import com.semgravio.aiplatform.base.metering.domain.model.WeekdayMasks;

/**
 * 后台时段规则行响应（#497，清单行/开行回执/停用回执共形；改窗回执的 closed/
 * opened 行元素亦本形）：通道 × 时段带 × 星期口径 × UTC 窗口 × 生效区间＋
 * 操作者。{@code effectiveTo} 为 null 即当前行；历史行含区间两端。
 *
 * @param id            规则行标识（TSID 十进制字符串）
 * @param provider      模型提供方（匹配键）
 * @param band          时段带 code（1=峰 2=谷）
 * @param bandName      时段带名（直读展示）
 * @param dayMask       星期口径 bitmask（ISO 周序 Mon=1…Sun=64）
 * @param dayMaskName   星期口径直读名（连续段压缩：31→「周一至周五」）
 * @param startMinute   UTC 窗口起点（含）
 * @param endMinute     UTC 窗口终点（不含）
 * @param effectiveFrom 生效起点（含）
 * @param effectiveTo   生效终点（不含；null = 当前行）
 * @param operatorId    操作者标识（该行最近管理动作——开行/改窗落新行、停用落被关行；
 *                      存量行/无头请求——含种子脚本种入行——为 null）
 * @param operatorName  操作者昵称（直读展示；同上落空口径）
 */
public record TimeBandRuleResponse(
        String id,
        String provider,
        Integer band,
        String bandName,
        Integer dayMask,
        String dayMaskName,
        Integer startMinute,
        Integer endMinute,
        Instant effectiveFrom,
        Instant effectiveTo,
        String operatorId,
        String operatorName
) {

    /** 规则行 → 后台响应。 */
    public static TimeBandRuleResponse of(TimeBandRule rule) {
        return new TimeBandRuleResponse(
                rule.getId().toString(),
                rule.getProvider(),
                rule.getBand().getCode(),
                rule.getBand().getName(),
                rule.getDayMask(),
                WeekdayMasks.nameOf(rule.getDayMask()),
                rule.getStartMinute(),
                rule.getEndMinute(),
                rule.getEffectiveFrom(),
                rule.getEffectiveTo(),
                rule.getOperatorId(),
                rule.getOperatorName());
    }
}
