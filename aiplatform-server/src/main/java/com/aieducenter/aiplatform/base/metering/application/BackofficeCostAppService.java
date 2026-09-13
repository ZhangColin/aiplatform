package com.aieducenter.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeCostOverviewResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeUnpricedUsageResponse;
import com.aieducenter.aiplatform.base.metering.domain.model.GlobalUsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.model.UnpricedTierUsage;
import com.aieducenter.aiplatform.base.metering.domain.repository.UsageEventAggregations;

/**
 * 后台平台成本读面①（#161 成本运营）：全局总览 + unpriced 全局警示。
 *
 * <p><b>口径</b>：纯平台 token 成本观测——与报价脱钩（平台付出金额，无建议售价
 * 推导）、按币种分桶直读不折算；采集无订单维度（subject = projectId），按订单做
 * 成本是伪粒度、已裁不做（成本下钻按项目，归 #164）。成本换算同 bySubject 口径：
 * 事件时点生效单价现算不物化，历史成本不随改价漂移。</p>
 */
@Service
public class BackofficeCostAppService {

    private final UsageEventAggregations usageEventAggregations;

    public BackofficeCostAppService(UsageEventAggregations usageEventAggregations) {
        this.usageEventAggregations = usageEventAggregations;
    }

    /**
     * 全局总览（全平台跨项目）：时间窗半开区间 {@code [from, to)}（null 侧不限），
     * 总量 + 平台成本（币种分桶）+ 分模型 + 分智能体。只读事务快照：四条聚合 SQL
     * 落在同一一致性视图，并发上报不破坏 total = ΣbyModel、cost = 窗口内已配价
     * 分量和 的自洽（同 {@link MeteringAppService#bySubject} 口径）。
     */
    @Transactional(readOnly = true)
    public BackofficeCostOverviewResponse overview(Instant from, Instant to) {
        GlobalUsageSummary summary = usageEventAggregations.aggregateGlobal(from, to);
        // 币种码键化（Currency → ISO 码串），键序沿用读模型已文档化的币种码序
        Map<String, BigDecimal> cost = summary.cost().entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().getCurrencyCode(),
                        Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
        return new BackofficeCostOverviewResponse(summary.from(), summary.to(), summary.total(),
                cost,
                summary.byModel().stream()
                        .map(model -> new BackofficeCostOverviewResponse.ModelUsage(
                                model.provider(), model.model(), model.tokens()))
                        .toList(),
                summary.byAgentKind().stream()
                        .map(kind -> new BackofficeCostOverviewResponse.AgentKindUsage(
                                kind.agentKind(), kind.tokens()))
                        .toList());
    }

    /**
     * unpriced 全局警示（用量驱动）：窗口内有 token 用量且事件时点无生效价的
     * (provider, model, 档位) 按档位汇总 token——只计无价分量；已配价档位与
     * 无用量档位不出现（静态配价缺口不做：无用量＝无实际损失）。单条聚合 SQL
     * 自带一致性，不另起事务。
     */
    public BackofficeUnpricedUsageResponse unpriced(Instant from, Instant to) {
        List<UnpricedTierUsage> tiers = usageEventAggregations.aggregateUnpricedTiers(from, to);
        return new BackofficeUnpricedUsageResponse(from, to,
                tiers.stream()
                        .map(tier -> new BackofficeUnpricedUsageResponse.UnpricedTier(
                                tier.provider(), tier.model(), tier.tokenKind().getCode(),
                                tier.tokenKind().getName(), tier.tokens()))
                        .toList());
    }
}
