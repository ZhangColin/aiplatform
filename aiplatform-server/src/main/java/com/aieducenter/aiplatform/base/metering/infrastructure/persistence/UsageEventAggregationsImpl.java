package com.aieducenter.aiplatform.base.metering.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.hutool.core.collection.CollUtil;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cartisan.core.domain.BaseEnum;

import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.GlobalUsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UnpricedTierUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageSummary;
import com.aieducenter.aiplatform.base.metering.domain.repository.UsageEventAggregations;

/**
 * 读侧聚合实现（JdbcTemplate 原生 SQL）：分维度聚合要走
 * {@code jsonb_each_text} 展开、成本换算要走「五档展开 × 事件时点生效区间」匹配，
 * JPQL 表达不了。各查询经 {@link #whereOf} 共用时间窗过滤（半开区间
 * {@code [from, to)}，null 侧不限），组内同处一个只读事务（应用服务层），
 * 各组数字自洽（总量 = 各分模型之和 = 各分维度之和；cost = 窗口内已配价分量的
 * 币种分桶和）。
 *
 * <p>换算（A6 §2，查询侧现算不物化）：事件五档经 {@code CROSS JOIN LATERAL VALUES}
 * 展开后与单价表按 (provider, model, kind) + ts 落 {@code [effective_from,
 * effective_to)} 区间匹配，金额按币种分桶不折算；有 token 无生效单价行的
 * (provider, model, 档位) 进 unpriced（分量不进 cost，不伪装 0、不阻断查询）。</p>
 */
@Component
public class UsageEventAggregationsImpl implements UsageEventAggregations {

    /** 五档 SUM 清单（表别名恒为 e，分模型/分维度两查询共用）。 */
    private static final String TOKEN_SUMS =
            "SUM(e.input), SUM(e.output), SUM(e.cache_read), SUM(e.cache_write), SUM(e.reasoning)";

    /** 总量五档 COALESCE SUM（空表/空窗聚合出 0 而非 null，按 subject/全局两查询共用）。 */
    private static final String TOTAL_SUMS =
            "COALESCE(SUM(e.input), 0), COALESCE(SUM(e.output), 0), "
                    + "COALESCE(SUM(e.cache_read), 0), COALESCE(SUM(e.cache_write), 0), "
                    + "COALESCE(SUM(e.reasoning), 0)";

    /** 五档 ↔ token 列名（VALUES 展开素材，与 TokenUsage 五档同序）。 */
    private static final List<Map.Entry<TokenKind, String>> KIND_COLUMNS = List.of(
            Map.entry(TokenKind.INPUT, "input"),
            Map.entry(TokenKind.OUTPUT, "output"),
            Map.entry(TokenKind.CACHE_READ, "cache_read"),
            Map.entry(TokenKind.CACHE_WRITE, "cache_write"),
            Map.entry(TokenKind.REASONING, "reasoning"));

    /**
     * 五档展开 VALUES 子句（表别名恒为 e）：(token_kind code, 该档 token 列)——
     * 成本换算与未配价标注共用的展开素材（kind 落库值单点取自 {@link TokenKind}）。
     */
    private static final String KIND_VALUES = KIND_COLUMNS.stream()
            .map(kindColumn -> "(" + kindColumn.getKey().getCode() + ", e." + kindColumn.getValue() + ")")
            .collect(Collectors.joining(", "));

    /** 事件时点生效区间匹配（单价表别名恒为 p；与事件 ts 落 [from, to) 半开同口径）。 */
    private static final String PRICE_MATCH =
            "p.provider = e.provider AND p.model = e.model AND p.token_kind = part.kind"
                    + " AND e.ts >= p.effective_from AND (p.effective_to IS NULL OR e.ts < p.effective_to)";

    /**
     * 分智能体聚合的维度键：dims 业务维度透传（写侧终态口径 projectId + agentKind
     * + sessionId，键名是底座协议词表的一部分——见 {@code UsageEvent}；非用户输入，
     * 内联常量不占位）。
     */
    private static final String DIM_KEY_AGENT_KIND = "agentKind";

    private final JdbcTemplate jdbcTemplate;

    public UsageEventAggregationsImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UsageSummary aggregateBySubject(String subject, Instant from, Instant to) {
        List<Object> args = CollUtil.newArrayList();
        String where = whereOf(args, subject, from, to);
        TokenUsage total = totalOf(where, args.toArray());
        // 平台成本：五档展开 × 时点生效单价，币种分桶不折算（A6 §2）
        Map<Currency, BigDecimal> cost = costBuckets(from, to, subject);
        // 未配价标注：有 token 但事件时点无生效单价行的 (provider, model, 档位)
        List<Object> unpricedArgs = CollUtil.newArrayList();
        String unpricedWhere = whereOf(unpricedArgs, subject, from, to, "part.tokens > 0",
                "NOT EXISTS (SELECT 1 FROM met_price_entries p WHERE " + PRICE_MATCH + ")");
        List<UsageSummary.UnpricedUsage> unpriced = jdbcTemplate.query(
                "SELECT e.provider, e.model, part.kind"
                        + " FROM met_usage_events e"
                        + " CROSS JOIN LATERAL (VALUES " + KIND_VALUES + ") AS part(kind, tokens)"
                        + unpricedWhere
                        + " GROUP BY e.provider, e.model, part.kind"
                        + " ORDER BY e.provider, e.model, part.kind",
                unpricedArgs.toArray(), (rs, rowNum) -> new UsageSummary.UnpricedUsage(
                        rs.getString(1), rs.getString(2),
                        BaseEnum.requireByCode(TokenKind.class, rs.getInt(3))));
        List<UsageSummary.ModelUsage> byModel = byModelOf(where, args.toArray());
        // jsonb_each_text 展开事件 dims：每个 (key, value) 各成一桶；无维度行不参与
        List<UsageSummary.DimUsage> byDims = jdbcTemplate.query(
                "SELECT kv.dim_key, kv.dim_value, " + TOKEN_SUMS
                        + " FROM met_usage_events e CROSS JOIN LATERAL jsonb_each_text(e.dims)"
                        + " AS kv(dim_key, dim_value)" + where
                        + " GROUP BY kv.dim_key, kv.dim_value ORDER BY kv.dim_key, kv.dim_value",
                args.toArray(), (rs, rowNum) -> new UsageSummary.DimUsage(
                        rs.getString(1), rs.getString(2), readTokens(rs, 3)));
        return new UsageSummary(subject, from, to, total, cost, unpriced, byModel, byDims);
    }

    @Override
    public GlobalUsageSummary aggregateGlobal(Instant from, Instant to) {
        // 全局口径（跨项目无 subject 过滤）：total/byModel 共用同一 where/args，
        // cost/分智能体条件不同各自建
        List<Object> args = CollUtil.newArrayList();
        String where = whereOf(args, null, from, to);
        TokenUsage total = totalOf(where, args.toArray());
        Map<Currency, BigDecimal> cost = costBuckets(from, to, null);
        List<UsageSummary.ModelUsage> byModel = byModelOf(where, args.toArray());
        // 分智能体：dims 只取 agentKind 键（写侧终态口径），无维度的事件不参与
        List<Object> dimArgs = CollUtil.newArrayList();
        List<GlobalUsageSummary.AgentKindUsage> byAgentKind = jdbcTemplate.query(
                "SELECT kv.dim_value, " + TOKEN_SUMS
                        + " FROM met_usage_events e CROSS JOIN LATERAL jsonb_each_text(e.dims)"
                        + " AS kv(dim_key, dim_value)"
                        + whereOf(dimArgs, null, from, to, "kv.dim_key = '" + DIM_KEY_AGENT_KIND + "'")
                        + " GROUP BY kv.dim_value ORDER BY kv.dim_value",
                dimArgs.toArray(), (rs, rowNum) -> new GlobalUsageSummary.AgentKindUsage(
                        rs.getString(1), readTokens(rs, 2)));
        return new GlobalUsageSummary(from, to, total, cost, byModel, byAgentKind);
    }

    @Override
    public List<UnpricedTierUsage> aggregateUnpricedTiers(Instant from, Instant to) {
        // 用量驱动：窗口内有用量且事件时点无生效价的档位汇总 token（只计无价分量）
        List<Object> args = CollUtil.newArrayList();
        return jdbcTemplate.query(
                "SELECT e.provider, e.model, part.kind, SUM(part.tokens)"
                        + " FROM met_usage_events e"
                        + " CROSS JOIN LATERAL (VALUES " + KIND_VALUES + ") AS part(kind, tokens)"
                        + whereOf(args, null, from, to, "part.tokens > 0",
                        "NOT EXISTS (SELECT 1 FROM met_price_entries p WHERE " + PRICE_MATCH + ")")
                        + " GROUP BY e.provider, e.model, part.kind"
                        + " ORDER BY e.provider, e.model, part.kind",
                args.toArray(), (rs, rowNum) -> new UnpricedTierUsage(
                        rs.getString(1), rs.getString(2),
                        BaseEnum.requireByCode(TokenKind.class, rs.getInt(3)), rs.getLong(4)));
    }

    /** 总量查询（按 subject/全局两口径共用，差异全在 where）。 */
    private TokenUsage totalOf(String where, Object[] args) {
        return jdbcTemplate.queryForObject(
                "SELECT " + TOTAL_SUMS + " FROM met_usage_events e" + where,
                args, (rs, rowNum) -> readTokens(rs, 1));
    }

    /** 分模型查询（按 subject/全局两口径共用，差异全在 where）。 */
    private List<UsageSummary.ModelUsage> byModelOf(String where, Object[] args) {
        return jdbcTemplate.query(
                "SELECT e.provider, e.model, " + TOKEN_SUMS
                        + " FROM met_usage_events e" + where
                        + " GROUP BY e.provider, e.model ORDER BY e.provider, e.model",
                args, (rs, rowNum) -> new UsageSummary.ModelUsage(
                        rs.getString(1), rs.getString(2), readTokens(rs, 3)));
    }

    /** 成本换算分桶（五档展开 × 时点生效价；subject 为 null 即全局口径）。 */
    private Map<Currency, BigDecimal> costBuckets(Instant from, Instant to, String subject) {
        List<Object> args = CollUtil.newArrayList();
        return jdbcTemplate.query(
                "SELECT p.currency, SUM(part.tokens * p.unit_price)"
                        + " FROM met_usage_events e"
                        + " CROSS JOIN LATERAL (VALUES " + KIND_VALUES + ") AS part(kind, tokens)"
                        + " JOIN met_price_entries p ON " + PRICE_MATCH
                        + whereOf(args, subject, from, to, "part.tokens > 0")
                        + " GROUP BY p.currency ORDER BY p.currency",
                args.toArray(), rs -> {
                    Map<Currency, BigDecimal> buckets = new LinkedHashMap<>();
                    while (rs.next()) {
                        buckets.put(Currency.getInstance(rs.getString(1)), rs.getBigDecimal(2));
                    }
                    return buckets;
                });
    }

    private static TokenUsage readTokens(ResultSet rs, int base) throws SQLException {
        return new TokenUsage(rs.getLong(base), rs.getLong(base + 1), rs.getLong(base + 2),
                rs.getLong(base + 3), rs.getLong(base + 4));
    }

    /**
     * 组装 WHERE 子句与参数：subject 非 null 时前置归属过滤（占位参数由本方法成对
     * 落入 args，调用方不经手）；conditions＝常量条件（无占位符）；窗口半开
     * {@code [from, to)}（null 侧不加）。全部为空时返回空串（无 WHERE）——
     * 占位符参数与子句内顺序恒一致。
     */
    private static String whereOf(List<Object> args, String subject, Instant from, Instant to,
                                  String... conditions) {
        List<String> predicates = CollUtil.newArrayList();
        if (subject != null) {
            predicates.add("e.subject = ?");
            args.add(subject);
        }
        for (String condition : conditions) {
            predicates.add(condition);
        }
        if (from != null) {
            predicates.add("e.ts >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            predicates.add("e.ts < ?");
            args.add(Timestamp.from(to));
        }
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }
}
