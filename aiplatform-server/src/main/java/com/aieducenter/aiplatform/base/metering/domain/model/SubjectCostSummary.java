package com.aieducenter.aiplatform.base.metering.domain.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按 subject 的成本汇总读模型（#164 平台成本读面②：项目成本清单）——窗口内
 * 该 subject 的总量 + 平台成本（币种分桶）+ 全未配价标记。
 *
 * <p>口径同 {@link UsageSummary}（按 subject 聚合）去掉分模型/分维度展开：
 * {@code cost} = token × 事件时点生效单价的机械乘法，按币种分桶不折算（键 =
 * ISO 4217 币种）；无生效单价的分量不进 cost（不伪装 0）。{@code allUnpriced} =
 * 窗口内有用量但<b>无任何</b>已配价分量——成本标量缺失、清单排序排后并标注；
 * 部分配价部分无价的项目不置位（无价分量观行走下钻的 unpriced 清单）。</p>
 */
public record SubjectCostSummary(
        String subject,
        TokenUsage total,
        Map<Currency, BigDecimal> cost,
        boolean allUnpriced) {

    public SubjectCostSummary {
        // 保序拷贝：cost 键序 = 聚合 SQL 的币种码序（GROUP BY ... ORDER BY currency）
        cost = cost == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
    }

    /**
     * cost 的币种码键化视图（Currency → ISO 码串）：REST 响应拼装的共享单点
     * （呈现安全形态归读模型，消费侧不再各自键化）。键序＝ISO 币种码字典序
     * 显式排定（与聚合 SQL 的 ORDER BY currency 一致，且不依赖来源 map 序）。
     */
    public Map<String, BigDecimal> costByCurrencyCode() {
        Map<String, BigDecimal> codes = new LinkedHashMap<>();
        cost.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Currency::getCurrencyCode)))
                .forEach(entry -> codes.put(entry.getKey().getCurrencyCode(), entry.getValue()));
        return Collections.unmodifiableMap(codes);
    }
}
