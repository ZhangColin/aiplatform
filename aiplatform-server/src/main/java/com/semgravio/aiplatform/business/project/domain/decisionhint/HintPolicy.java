package com.semgravio.aiplatform.business.project.domain.decisionhint;

import java.util.Set;

/**
 * 决策时注入策略（#437 判定缝参数）：运营配置面三件的生效形——失败止损阈值
 * （同类错误连续 N 次触发）、堆叠上限（单轮最多注入几条不同微指令）、禁用集
 * （库条目的运营关停）。库值优先、缺省回落代码缺省（{@code defaults}）。
 *
 * @param errorStreakThreshold 失败止损阈值（≥2 的整数）
 * @param stackCap             堆叠上限（≥1 的整数；研究口径 ≤3——再堆收益递减转负）
 * @param disabledKinds        禁用集（空＝全启用）
 */
public record HintPolicy(int errorStreakThreshold, int stackCap, Set<DecisionHintKind> disabledKinds) {

    /** 代码缺省：阈值 3、上限 3、全启用（#437 首批落地缺省；运营面 null 回落此形）。 */
    public static HintPolicy defaults() {
        return new HintPolicy(3, 3, Set.of());
    }
}
