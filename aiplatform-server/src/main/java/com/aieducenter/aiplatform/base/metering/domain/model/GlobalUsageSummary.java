package com.aieducenter.aiplatform.base.metering.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全平台聚合的用量读模型（#161 平台成本读面①：全局总览）——{@link UsageSummary}
 * （按 subject）的同族口径去掉归属维：总量 + 平台成本（币种分桶）+ 分模型 +
 * 分智能体。
 *
 * <p>{@code cost} = 平台成本（token × 事件时点生效单价的机械乘法，无加价/售价），
 * <b>按币种分桶不折算</b>（键 = ISO 4217 币种）；无生效单价的分量不进 cost
 * （不伪装 0、不阻断聚合）——全局未配价观测是用量驱动的独立端点
 * （{@link UnpricedTierUsage}），不在本模型内。{@code byAgentKind} = 事件 dims
 * 中 agentKind 维各值各自聚合（写侧终态口径 main/executor，底座不解释取值），
 * 无维度的事件不参与该分桶（总量/byModel 照含）。窗口内无事件返回全零 total
 * 与空结构，不是错误。</p>
 */
public record GlobalUsageSummary(
        Instant from,
        Instant to,
        TokenUsage total,
        Map<Currency, BigDecimal> cost,
        List<UsageSummary.ModelUsage> byModel,
        List<AgentKindUsage> byAgentKind) {

    public GlobalUsageSummary {
        // 保序拷贝：cost 键序 = 聚合 SQL 的币种码序（ORDER BY currency）
        cost = cost == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byAgentKind = byAgentKind == null ? List.of() : List.copyOf(byAgentKind);
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

    /**
     * 分智能体聚合项（agentKind = dims 透传原值；展示名归业务读侧映射，底座不解释）。
     */
    public record AgentKindUsage(String agentKind, TokenUsage tokens) {
    }
}
