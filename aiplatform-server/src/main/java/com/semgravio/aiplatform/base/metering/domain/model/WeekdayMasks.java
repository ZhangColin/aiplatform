package com.semgravio.aiplatform.base.metering.domain.model;

/**
 * 星期口径直读名渲染（时段规则读面展示用）：ISO 周 bitmask → 「周一至周五」
 * 「周六、周日」式短名。连续段压缩为「周X至周Y」、单日为「周X」，多段以
 * 「、」连接；空 mask（非法输入，守卫已在写口拦）渲染为空串——本方法不做
 * 校验，只负责呈现（校验在聚合守卫，读面不对抗脏数据）。
 *
 * <p>渲染规则是契约面（清单/详情直读展示），变更须随读面用例一并评估。</p>
 */
public final class WeekdayMasks {

    /** ISO 周序短名（Mon..Sun，与 bitmask 位序 0..6 对齐）。 */
    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    private WeekdayMasks() {
    }

    /**
     * bitmask → 直读名（如 31 → 「周一至周五」、96 → 「周六至周日」、5 →
     * 「周一、周三」）；0 或越界位返回空串。
     */
    public static String nameOf(int dayMask) {
        StringBuilder result = new StringBuilder();
        int day = 0;
        while (day < 7) {
            if ((dayMask & (1 << day)) == 0) {
                day++;
                continue;
            }
            int runStart = day;
            while (day < 7 && (dayMask & (1 << day)) != 0) {
                day++;
            }
            if (result.length() > 0) {
                result.append('、');
            }
            if (day - runStart > 1) {
                result.append("周").append(DAY_NAMES[runStart]).append("至周")
                        .append(DAY_NAMES[day - 1]);
            } else {
                result.append("周").append(DAY_NAMES[runStart]);
            }
        }
        return result.toString();
    }
}
