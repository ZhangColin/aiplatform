package com.aieducenter.aiplatform.base.metering.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeCostOverviewResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeProjectCostDetailResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeProjectCostResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.BackofficeUnpricedUsageResponse;
import com.aieducenter.aiplatform.base.metering.domain.model.GlobalUsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.model.SubjectCostSummary;
import com.aieducenter.aiplatform.base.metering.domain.model.UnpricedTierUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.port.AgentKindNames;
import com.aieducenter.aiplatform.base.metering.domain.repository.UsageEventAggregations;

/**
 * 后台平台成本读面（#161/#164 成本运营）：全局总览 + unpriced 全局警示 + 项目
 * 成本清单 + 单项目下钻。
 *
 * <p><b>口径</b>：纯平台 token 成本观测——与报价脱钩（平台付出金额，无建议售价
 * 推导）、按币种分桶直读不折算；采集无订单维度（subject = projectId），按订单做
 * 成本是伪粒度、已裁不做（订单→成本经项目）。成本换算同 bySubject 口径：事件
 * 时点生效单价现算不物化，历史成本不随改价漂移。subject 不透明（底座不解释
 * 存在性）：清单用量驱动（无用量项目不出现），下钻无用量＝全零空态非错误；
 * 行不带项目名等档案（base 不依赖 business，名称互查归 admin 侧按 id 自理）。</p>
 */
@Service
public class BackofficeCostAppService {

    /** 清单行序：全未配价排后 → 成本标量降序 → subject 升序稳定。 */
    private static final Comparator<SubjectCostSummary> ROW_ORDER = Comparator
            .comparing(SubjectCostSummary::allUnpriced)
            .thenComparing(BackofficeCostAppService::costScalarOf, Comparator.reverseOrder())
            .thenComparing(SubjectCostSummary::subject);

    private final UsageEventAggregations usageEventAggregations;
    private final AgentKindNames agentKindNames;

    public BackofficeCostAppService(UsageEventAggregations usageEventAggregations,
            AgentKindNames agentKindNames) {
        this.usageEventAggregations = usageEventAggregations;
        this.agentKindNames = agentKindNames;
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
        return new BackofficeCostOverviewResponse(summary.from(), summary.to(), summary.total(),
                summary.costByCurrencyCode(),
                summary.byModel().stream()
                        .map(model -> new BackofficeCostOverviewResponse.ModelUsage(
                                model.provider(), model.model(), model.tokens()))
                        .toList(),
                summary.byAgentKind().stream()
                        .map(kind -> new BackofficeCostOverviewResponse.AgentKindUsage(
                                kind.agentKind(), agentKindNames.displayNameOf(kind.agentKind()),
                                kind.tokens()))
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

    /**
     * 项目成本清单（#164，用量驱动）：窗口内有用量的项目按成本降序、全未配价
     * 排后（标注）、分页。总量与成本分桶两查同窗同事务串行（READ COMMITTED
     * 下各语句各自快照——并发上报的极端时序可致行内 total 与 cost 瞬时不自洽，
     * 监控读面可接受，同 {@link MeteringAppService#bySubject} 先例口径）。
     * 排序标量＝币种桶金额直加（单价表单币种时＝精确排序；混币种仅定序用，
     * 呈现仍分桶直读不折算）；同标量按 subject 升序稳定。分页钳制/换算全部
     * 来自框架 {@link Pagination}（1 基、缺省 1/20、上界 100 静默贴边），内存
     * 排序不动、切页直出 offset()/limit()，页码原样回显零手写算术。
     */
    @Transactional(readOnly = true)
    public PageResponse<BackofficeProjectCostResponse> projectCosts(Instant from, Instant to,
                                                                    Pagination pagination) {
        List<BackofficeProjectCostResponse> rows = usageEventAggregations
                .aggregateSubjectCosts(from, to).stream()
                .sorted(ROW_ORDER)
                .map(summary -> new BackofficeProjectCostResponse(summary.subject(),
                        summary.total(), summary.costByCurrencyCode(), summary.allUnpriced()))
                .toList();
        // offset() 出 long，先与 rows.size() 取 min 再收窄——min 结果被行数上界
        // 封顶，收窄无损；超尾页（含极端大页码 long 承载）落 rows.size()＝空页
        int fromIndex = (int) Math.min(pagination.offset(), rows.size());
        int toIndex = Math.min(fromIndex + pagination.limit(), rows.size());
        return new PageResponse<>(rows.subList(fromIndex, toIndex), rows.size(),
                pagination.page(), pagination.size());
    }

    /**
     * 单项目成本下钻（#164）：复用 bySubject 聚合（口径一致＝总量 + 事件时点
     * 生效价成本分桶 + 未配价标注 + 分模型 + 分智能体）。subject 无事件返回
     * 全零 total 与空结构（不透明口径，非错误）；byAgentKind 取 dims.agentKind
     * 原值（无维度事件不参与该分桶，总量/byModel 照含）。
     */
    @Transactional(readOnly = true)
    public BackofficeProjectCostDetailResponse projectCostDetail(String projectId,
                                                                 Instant from, Instant to) {
        UsageSummary summary = usageEventAggregations.aggregateBySubject(projectId, from, to);
        return new BackofficeProjectCostDetailResponse(projectId, summary.from(), summary.to(),
                summary.total(), summary.costByCurrencyCode(),
                summary.unpriced().stream()
                        .map(usage -> new BackofficeProjectCostDetailResponse.UnpricedTier(
                                usage.provider(), usage.model(), usage.tokenKind().getCode(),
                                usage.tokenKind().getName()))
                        .toList(),
                summary.byModel().stream()
                        .map(model -> new BackofficeProjectCostDetailResponse.ModelUsage(
                                model.provider(), model.model(), model.tokens()))
                        .toList(),
                summary.byDims().stream()
                        .filter(dim -> UsageEvent.DIM_KEY_AGENT_KIND.equals(dim.dimKey()))
                        .map(dim -> new BackofficeProjectCostDetailResponse.AgentKindUsage(
                                dim.dimValue(), agentKindNames.displayNameOf(dim.dimValue()),
                                dim.tokens()))
                        .toList());
    }

    /** 成本排序标量：币种桶金额直加（定序专用，不进响应呈现）。 */
    private static BigDecimal costScalarOf(SubjectCostSummary summary) {
        return summary.cost().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
