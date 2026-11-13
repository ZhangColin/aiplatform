package com.semgravio.aiplatform.base.metering.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.hutool.core.collection.CollUtil;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.cartisan.core.domain.BaseEnum;

import com.semgravio.aiplatform.base.metering.domain.enums.Band;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;
import com.semgravio.aiplatform.base.metering.domain.model.GlobalUsageSummary;
import com.semgravio.aiplatform.base.metering.domain.model.SubjectCostSummary;
import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UnpricedTierUsage;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.model.UsageSummary;
import com.semgravio.aiplatform.base.metering.domain.repository.UsageEventAggregations;

/**
 * 读侧聚合实现（JdbcTemplate 原生 SQL）：分维度聚合要走
 * {@code jsonb_each_text} 展开、成本换算要走「五档展开 × 事件时点生效区间」匹配，
 * JPQL 表达不了。各查询经 {@link #whereOf} 共用时间窗过滤（半开区间
 * {@code [from, to)}，null 侧不限），组内同处一个只读事务（应用服务层），
 * 各组数字自洽（总量 = 各分模型之和 = 各分维度之和；cost = 窗口内已配价分量的
 * 币种分桶和）。
 *
 * <p>换算（A6 §2，查询侧现算不物化）：事件档位（token 五档＋按张，#288）经
 * {@code CROSS JOIN LATERAL VALUES} 展开后与单价表按 (provider, model, kind) + ts 落
 * {@code [effective_from, effective_to)} 区间匹配；时段带维（#499，ADR-0044 读时归段）
 * ＝事件 ts 经时段规则表归带（峰/谷，epoch 数学派生 UTC 星期/分钟、免会话时区坑）
 * 后按带选价——无规则行通道回落 NULL 带行（存量语义）、按张档不受带影响。金额按
 * 币种分桶不折算；有用量无生效单价行的 (provider, model, 档位, 带) 进 unpriced
 * （分量不进 cost，不伪装 0、不阻断查询）。</p>
 */
@Component
public class UsageEventAggregationsImpl implements UsageEventAggregations {

    /** 五档 SUM 清单（表别名恒为 e，分模型/分维度两查询共用）。token 总量不含
     *  按张列——张与 token 并列不混算（#288），张只进成本/未配价的档位展开。 */
    private static final String TOKEN_SUMS =
            "SUM(e.input), SUM(e.output), SUM(e.cache_read), SUM(e.cache_write), SUM(e.reasoning)";

    /** 总量五档 COALESCE SUM（空表/空窗聚合出 0 而非 null，按 subject/全局两查询共用）。 */
    private static final String TOTAL_SUMS =
            "COALESCE(SUM(e.input), 0), COALESCE(SUM(e.output), 0), "
                    + "COALESCE(SUM(e.cache_read), 0), COALESCE(SUM(e.cache_write), 0), "
                    + "COALESCE(SUM(e.reasoning), 0)";

    /** 档位 ↔ 用量列（VALUES 展开素材）：token 五档与 TokenUsage 五档同序，末位
     *  按张档（#288）对应 images 列——成本换算与未配价对六档同一公式。 */
    private static final List<Map.Entry<TokenKind, String>> KIND_COLUMNS = List.of(
            Map.entry(TokenKind.INPUT, "input"),
            Map.entry(TokenKind.OUTPUT, "output"),
            Map.entry(TokenKind.CACHE_READ, "cache_read"),
            Map.entry(TokenKind.CACHE_WRITE, "cache_write"),
            Map.entry(TokenKind.REASONING, "reasoning"),
            Map.entry(TokenKind.IMAGE, "images"));

    /**
     * 档位展开 VALUES 子句（表别名恒为 e）：(kind code, 该档用量列)——成本换算与
     * 未配价标注共用的展开素材（kind 落库值单点取自 {@link TokenKind}）。
     */
    private static final String KIND_VALUES = KIND_COLUMNS.stream()
            .map(kindColumn -> "(" + kindColumn.getKey().getCode() + ", e." + kindColumn.getValue() + ")")
            .collect(Collectors.joining(", "));

    /** 事件 ts 的 epoch 秒（UTC 绝对口径）：星期/分钟派生不走数据库会话时区。 */
    private static final String EVENT_EPOCH = "FLOOR(EXTRACT(EPOCH FROM e.ts))";

    /** 事件 ts 的 ISO 星期 bitmask 位（Mon=1…Sun=64，与规则 day_mask 同口径；
     *  epoch 日 0＝1970-01-01 周四，+3 对齐 ISO 周一余数起点）。 */
    private static final String EVENT_DOW_BIT =
            "(1 << (((FLOOR(" + EVENT_EPOCH + " / 86400) + 3)::int % 7)))";

    /** 事件 ts 的 UTC 日分钟（0..1439，与规则 start_minute/end_minute 同口径）。 */
    private static final String EVENT_MINUTE = "(FLOOR(" + EVENT_EPOCH + " / 60)::int % 1440)";

    /** 时段规则行在事件 ts 时点生效（别名恒为 r；[from, to) 半开与单价表同口径）。 */
    private static final String RULE_OPEN =
            "r.provider = e.provider AND r.effective_from <= e.ts"
                    + " AND (r.effective_to IS NULL OR e.ts < r.effective_to)";

    /** 事件 ts 命中规则窗口行：ts 星期 ∈ day_mask ∧ ts UTC 分钟 ∈ [start, end)。 */
    private static final String RULE_HIT =
            RULE_OPEN + " AND (r.day_mask & " + EVENT_DOW_BIT + ") <> 0"
                    + " AND " + EVENT_MINUTE + " >= r.start_minute"
                    + " AND " + EVENT_MINUTE + " < r.end_minute";

    /**
     * 事件 ts 归带（读时归段单点）：通道无生效规则行 → NULL（不看时段，存量语义）；
     * 命中窗口行 → 该行带（多行命中取 MIN(band) 确定性兜底）；有规则行但无窗口命中
     * → 补带——声明了峰窗 → 谷、只声明谷窗 → 峰（DeepSeek 峰窗显式声明、其余谷，
     * 谷不种行）。带码单点取自 {@link Band}。
     */
    private static final String EVENT_BAND =
            "CASE WHEN NOT EXISTS (SELECT 1 FROM met_time_band_rules r WHERE " + RULE_OPEN + ")"
                    + " THEN NULL"
                    + " ELSE COALESCE("
                    + "(SELECT MIN(r.band) FROM met_time_band_rules r WHERE " + RULE_HIT + "), "
                    + "CASE WHEN EXISTS (SELECT 1 FROM met_time_band_rules r WHERE " + RULE_OPEN
                    + " AND r.band = " + Band.PEAK.getCode() + ")"
                    + " THEN " + Band.OFF_PEAK.getCode()
                    + " ELSE " + Band.PEAK.getCode() + " END)"
                    + " END";

    /** 未配价警示的归带维：token 档按事件归带分组；按张档不受带影响（落空）。 */
    private static final String UNPRICED_BAND =
            "CASE WHEN part.kind = " + TokenKind.IMAGE.getCode() + " THEN NULL"
                    + " ELSE " + EVENT_BAND + " END";

    /**
     * 事件时点生效区间匹配（单价表别名恒为 p；与事件 ts 落 [from, to) 半开同口径）。
     * 时段带维（#499，ADR-0044 读时归段）：token 档按 {@link #EVENT_BAND} 归带匹配
     * 对应带单价行、无规则行通道回落 NULL 带行（存量语义）；按张档不受 band 影响
     * （只匹配 NULL 带行——#288 按张行不带维度）。
     */
    private static final String PRICE_MATCH =
            "p.provider = e.provider AND p.model = e.model AND p.token_kind = part.kind"
                    + " AND e.ts >= p.effective_from AND (p.effective_to IS NULL OR e.ts < p.effective_to)"
                    + " AND (part.kind = " + TokenKind.IMAGE.getCode() + " AND p.band IS NULL"
                    + " OR part.kind <> " + TokenKind.IMAGE.getCode() + " AND ("
                    + " (p.band IS NULL AND NOT EXISTS (SELECT 1 FROM met_time_band_rules r"
                    + " WHERE " + RULE_OPEN + "))"
                    + " OR p.band = " + EVENT_BAND + "))";

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
        // 未配价标注：有 token 但事件时点无生效单价行的 (provider, model, 档位, 带)
        // ——带维归因事件归带（按张档不受带影响落空），峰行有谷行无时谷段分量进此面
        List<Object> unpricedArgs = CollUtil.newArrayList();
        String unpricedWhere = whereOf(unpricedArgs, subject, from, to, "part.tokens > 0",
                "NOT EXISTS (SELECT 1 FROM met_price_entries p WHERE " + PRICE_MATCH + ")");
        List<UsageSummary.UnpricedUsage> unpriced = jdbcTemplate.query(
                "SELECT e.provider, e.model, part.kind, " + UNPRICED_BAND
                        + " FROM met_usage_events e"
                        + " CROSS JOIN LATERAL (VALUES " + KIND_VALUES + ") AS part(kind, tokens)"
                        + unpricedWhere
                        + " GROUP BY e.provider, e.model, part.kind, " + UNPRICED_BAND
                        + " ORDER BY e.provider, e.model, part.kind, " + UNPRICED_BAND,
                unpricedArgs.toArray(), (rs, rowNum) -> new UsageSummary.UnpricedUsage(
                        rs.getString(1), rs.getString(2),
                        BaseEnum.requireByCode(TokenKind.class, rs.getInt(3)),
                        readBand(rs, 4)));
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
        // 分模型金额按带拆（#499 峰谷拆分维度）：行键 (provider, model, 带) × 币种分桶，
        // 带 = 所配单价行 band（NULL＝不区分时段分量，按张行恒落此桶）
        List<GlobalUsageSummary.ModelBandCost> byModelCost = byModelCostOf(from, to);
        // 分智能体：dims 只取 agentKind 键（写侧终态口径），无维度的事件不参与
        List<Object> dimArgs = CollUtil.newArrayList();
        List<GlobalUsageSummary.AgentKindUsage> byAgentKind = jdbcTemplate.query(
                "SELECT kv.dim_value, " + TOKEN_SUMS
                        + " FROM met_usage_events e CROSS JOIN LATERAL jsonb_each_text(e.dims)"
                        + " AS kv(dim_key, dim_value)"
                        + whereOf(dimArgs, null, from, to,
                        "kv.dim_key = '" + UsageEvent.DIM_KEY_AGENT_KIND + "'")
                        + " GROUP BY kv.dim_value ORDER BY kv.dim_value",
                dimArgs.toArray(), (rs, rowNum) -> new GlobalUsageSummary.AgentKindUsage(
                        rs.getString(1), readTokens(rs, 2)));
        return new GlobalUsageSummary(from, to, total, cost, byModel, byAgentKind, byModelCost);
    }

    @Override
    public List<UnpricedTierUsage> aggregateUnpricedTiers(Instant from, Instant to) {
        // 用量驱动：窗口内有用量且事件时点无生效价的档位汇总 token（只计无价分量）
        List<Object> args = CollUtil.newArrayList();
        return jdbcTemplate.query(
                "SELECT e.provider, e.model, part.kind, " + UNPRICED_BAND + ", SUM(part.tokens)"
                        + " FROM met_usage_events e"
                        + " CROSS JOIN LATERAL (VALUES " + KIND_VALUES + ") AS part(kind, tokens)"
                        + whereOf(args, null, from, to, "part.tokens > 0",
                        "NOT EXISTS (SELECT 1 FROM met_price_entries p WHERE " + PRICE_MATCH + ")")
                        + " GROUP BY e.provider, e.model, part.kind, " + UNPRICED_BAND
                        + " ORDER BY e.provider, e.model, part.kind, " + UNPRICED_BAND,
                args.toArray(), (rs, rowNum) -> new UnpricedTierUsage(
                        rs.getString(1), rs.getString(2),
                        BaseEnum.requireByCode(TokenKind.class, rs.getInt(3)), readBand(rs, 4),
                        rs.getLong(5)));
    }

    @Override
    public List<SubjectCostSummary> aggregateSubjectCosts(Instant from, Instant to) {
        // 两查合并：① subject 总量（无 LATERAL 展开——五档列直 SUM，不受展开扇出
        // 影响）；② subject × 币种成本分桶（costBuckets 的分组细化一维）。两查
        // 同窗同事务，subject 集 ② ⊆ ①（成本行由窗口内事件驱动）。
        List<Object> totalArgs = CollUtil.newArrayList();
        Map<String, TokenUsage> totals = jdbcTemplate.query(
                "SELECT e.subject, " + TOKEN_SUMS + " FROM met_usage_events e"
                        + whereOf(totalArgs, null, from, to)
                        + " GROUP BY e.subject",
                totalArgs.toArray(), rs -> {
                    Map<String, TokenUsage> bySubject = new LinkedHashMap<>();
                    while (rs.next()) {
                        bySubject.put(rs.getString(1), readTokens(rs, 2));
                    }
                    return bySubject;
                });
        List<Object> costArgs = CollUtil.newArrayList();
        Map<String, Map<Currency, BigDecimal>> costBySubject = jdbcTemplate.query(
                "SELECT e.subject, p.currency, SUM(part.tokens * p.unit_price)"
                        + " FROM met_usage_events e"
                        + " CROSS JOIN LATERAL (VALUES " + KIND_VALUES + ") AS part(kind, tokens)"
                        + " JOIN met_price_entries p ON " + PRICE_MATCH
                        + whereOf(costArgs, null, from, to, "part.tokens > 0")
                        + " GROUP BY e.subject, p.currency ORDER BY e.subject, p.currency",
                costArgs.toArray(), rs -> {
                    Map<String, Map<Currency, BigDecimal>> bySubject = new LinkedHashMap<>();
                    while (rs.next()) {
                        bySubject.computeIfAbsent(rs.getString(1), key -> new LinkedHashMap<>())
                                .put(Currency.getInstance(rs.getString(2)), rs.getBigDecimal(3));
                    }
                    return bySubject;
                });
        return totals.entrySet().stream()
                .map(entry -> {
                    Map<Currency, BigDecimal> cost = costBySubject.getOrDefault(entry.getKey(), Map.of());
                    // 全未配价＝窗口内有用量但无任何已配价分量（成本分桶为空）
                    return new SubjectCostSummary(entry.getKey(), entry.getValue(), cost, cost.isEmpty());
                })
                .toList();
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

    /**
     * 分模型金额按带拆查询（全局总览峰/谷拆分维度）：(provider, model, 带) × 币种
     * 分桶——只含已配价分量（无价分量走 unpriced 面，互补不重叠）；带取所配单价行
     * {@code band}（NULL＝不区分时段分量；按张行不受带影响、恒落 NULL 桶）。
     */
    private List<GlobalUsageSummary.ModelBandCost> byModelCostOf(Instant from, Instant to) {
        List<Object> args = CollUtil.newArrayList();
        Map<List<Object>, Map<Currency, BigDecimal>> grouped = jdbcTemplate.query(
                "SELECT e.provider, e.model, p.band, p.currency, SUM(part.tokens * p.unit_price)"
                        + " FROM met_usage_events e"
                        + " CROSS JOIN LATERAL (VALUES " + KIND_VALUES + ") AS part(kind, tokens)"
                        + " JOIN met_price_entries p ON " + PRICE_MATCH
                        + whereOf(args, null, from, to, "part.tokens > 0")
                        + " GROUP BY e.provider, e.model, p.band, p.currency"
                        + " ORDER BY e.provider, e.model, p.band, p.currency",
                args.toArray(), rs -> {
                    Map<List<Object>, Map<Currency, BigDecimal>> rows = new LinkedHashMap<>();
                    while (rs.next()) {
                        // 键容 null 带（不区分时段分量）——Arrays.asList 许 null（List.of 不容）
                        rows.computeIfAbsent(Arrays.asList(rs.getString(1), rs.getString(2),
                                        (Integer) rs.getObject(3)), key -> new LinkedHashMap<>())
                                .put(Currency.getInstance(rs.getString(4)), rs.getBigDecimal(5));
                    }
                    return rows;
                });
        return grouped.entrySet().stream()
                .map(entry -> new GlobalUsageSummary.ModelBandCost(
                        (String) entry.getKey().get(0), (String) entry.getKey().get(1),
                        entry.getKey().get(2) == null ? null
                                : BaseEnum.requireByCode(Band.class, (Integer) entry.getKey().get(2)),
                        entry.getValue()))
                .toList();
    }

    private static TokenUsage readTokens(ResultSet rs, int base) throws SQLException {
        return new TokenUsage(rs.getLong(base), rs.getLong(base + 1), rs.getLong(base + 2),
                rs.getLong(base + 3), rs.getLong(base + 4));
    }

    /** 可空带列读枚举：NULL → null（不区分时段），非空经 code 回解（防 ORDINAL 错位）。 */
    private static Band readBand(ResultSet rs, int index) throws SQLException {
        Integer code = (Integer) rs.getObject(index);
        return code == null ? null : BaseEnum.requireByCode(Band.class, code);
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
