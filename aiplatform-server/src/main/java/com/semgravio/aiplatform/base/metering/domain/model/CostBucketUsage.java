package com.semgravio.aiplatform.base.metering.domain.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;

import com.semgravio.aiplatform.base.metering.domain.enums.CostBucket;

/**
 * 成本观测切片桶读模型（#536，ADR-0047 成本观测切片分桶）：单桶的用量＋平台
 * 成本（币种分桶）——全局总览（byBucket 维）与项目成本清单（行内 byBucket）
 * 两读面共用的桶形态。
 *
 * <p>{@code cost} 口径与 {@link GlobalUsageSummary#cost()} 同源（token/计件档
 * × 事件时点生效单价的机械乘法、按币种分桶不折算；无生效单价分量不进桶——
 * unpriced 观测面互补不重叠）。两桶对同一事件全集互补不重叠：获客成本桶＝
 * 锚前分量、用户费用桶＝锚后分量；未翻转 subject 全量落获客成本桶。</p>
 */
public record CostBucketUsage(
        CostBucket bucket,
        TokenUsage tokens,
        Map<Currency, BigDecimal> cost) {

    public CostBucketUsage {
        // 保序拷贝：cost 键序 = 聚合 SQL 的币种码序（GROUP BY ... ORDER BY currency）
        cost = cost == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
    }

    /**
     * cost 的币种码键化视图（Currency → ISO 码串）：REST 响应拼装的共享单点
     * （呈现安全形态归读模型，消费侧不再各自键化）。键序＝ISO 币种码字典序
     * 显式排定（同 {@link GlobalUsageSummary#costByCurrencyCode()} 口径）。
     */
    public Map<String, BigDecimal> costByCurrencyCode() {
        Map<String, BigDecimal> codes = new LinkedHashMap<>();
        cost.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Currency::getCurrencyCode)))
                .forEach(entry -> codes.put(entry.getKey().getCurrencyCode(), entry.getValue()));
        return Collections.unmodifiableMap(codes);
    }
}
