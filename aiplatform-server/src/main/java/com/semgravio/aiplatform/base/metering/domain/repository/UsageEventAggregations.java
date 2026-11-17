package com.semgravio.aiplatform.base.metering.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.semgravio.aiplatform.base.metering.domain.model.GlobalUsageSummary;
import com.semgravio.aiplatform.base.metering.domain.model.PricedUsageLine;
import com.semgravio.aiplatform.base.metering.domain.model.SubjectCostSummary;
import com.semgravio.aiplatform.base.metering.domain.model.UnpricedTierUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageSummary;

/**
 * 用量事件读侧聚合接口（实现：infrastructure/persistence 的
 * {@code UsageEventAggregationsImpl}，jsonb 维度聚合需原生 SQL）。
 *
 * <p>与 {@link UsageEventRepository}（写面）分离：读侧是原生 SQL 聚合，不走
 * JPA 派生查询；接口留在 domain 由 infrastructure 实现（端口-适配器同构）。</p>
 */
public interface UsageEventAggregations {

    /**
     * 按 subject 聚合（总量 + 分模型 + 分维度）。时间窗半开区间 {@code [from, to)}，
     * 两侧 null = 该侧不限；subject 无事件返回全零 total 与空列表，不是错误。
     */
    UsageSummary aggregateBySubject(String subject, Instant from, Instant to);

    /**
     * 全平台聚合（#161 全局总览，无 subject 过滤——跨项目口径）：总量 + 平台成本
     * （币种分桶）+ 分模型 + 分智能体（dims.agentKind）＋成本观测切片桶（#536）。
     * 时间窗同半开区间约定；窗口内无事件返回全零 total 与空结构，不是错误。
     *
     * @param sliceAnchors 切片锚（subject → 锚时点，经
     *                     {@link com.semgravio.aiplatform.base.metering.domain.port.SubjectSliceAnchors}
     *                     供给）：事件 ts &lt; 锚＝获客成本桶、ts &gt;= 锚＝用户费用桶；
     *                     subject 缺席＝全量获客成本桶；空 Map＝全量落获客成本桶
     */
    GlobalUsageSummary aggregateGlobal(Instant from, Instant to, Map<String, Instant> sliceAnchors);

    /**
     * 未配价档位用量汇总（#161 unpriced 全局警示，用量驱动）：窗口内有 token
     * 用量且事件时点无生效单价行的 (provider, model, 档位) 按档位汇总——只计
     * 无价分量；已配价档位与无用量档位不出现。空窗返回空清单。
     */
    List<UnpricedTierUsage> aggregateUnpricedTiers(Instant from, Instant to);

    /**
     * 窗口内有用量的事件按 subject 分组的成本汇总（#164 项目成本清单）：每
     * subject 总量 + 平台成本（币种分桶）+ 全未配价标记（无任何已配价分量）。
     * 用量驱动——无用量 subject 不出现（空窗返回空清单）；subject 不透明，
     * 排序/分页归应用层。
     *
     * @param sliceAnchors 切片锚（subject → 锚时点，经
     *                     {@link com.semgravio.aiplatform.base.metering.domain.port.SubjectSliceAnchors}
     *                     供给）：事件 ts &lt; 锚＝获客成本桶、ts &gt;= 锚＝用户费用桶；
     *                     subject 缺席＝全量获客成本桶；空 Map＝全量落获客成本桶
     *                     （行内 byBucket 同口径）
     */
    List<SubjectCostSummary> aggregateSubjectCosts(Instant from, Instant to,
            Map<String, Instant> sliceAnchors);

    /**
     * 按 run 寻址的已配价线集（#531 计费段结算读面）：subject 名下、run_id ∈
     * runIds 的事件按 (provider, model, 档位, 时点生效单价, 币种) 归并——同键
     * 改价跨区间出多线（各按各时点单价）。只含已配价分量（未配价分量走
     * unpriced 观测面不进本线集）；runIds 空返回空清单。{@code from} 非 null 时
     * 只计 {@code ts >= from} 的事件（计费边界＝付费状态翻转时间点的机制面过滤
     * ——翻转前事件＝平台获客成本，不进用户账）；null 侧不限。
     */
    List<PricedUsageLine> aggregatePricedLinesByRuns(String subject, Collection<String> runIds,
            Instant from);
}
