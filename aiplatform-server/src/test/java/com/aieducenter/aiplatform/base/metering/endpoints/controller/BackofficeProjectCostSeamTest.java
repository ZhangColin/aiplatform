package com.aieducenter.aiplatform.base.metering.endpoints.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.base.metering.domain.model.UsageEvent;
import com.aieducenter.aiplatform.base.metering.domain.port.UsageEventSink;
import com.aieducenter.aiplatform.base.metering.domain.repository.PriceEntryRepository;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #164 平台成本读面②在 {@code #152} seam 上全绿：真过滤链（签名闸/会话豁免/
 * 强制拦截器）＋真应用服务＋aiplatform_test 真库。单价夹具用独立 provider
 * {@code backoffice-projcost}（与他类测试互不沾）；清单是全局聚合，
 * met_usage_events 全表前后各清一次（同 BackofficeCostSeamTest 形制）。
 *
 * <p>#164 验收口径逐条钉死：</p>
 * <ul>
 * <li><b>项目成本清单</b>：窗口聚合＋成本降序（排序标量＝币种桶直加，混币种
 * 行分桶直读）＋全未配价项目排后且 allUnpriced 标注＋分页（size 上界截断、
 * 越界空页）；无用量项目不出现在清单（用量驱动）；</li>
 * <li><b>单项目下钻</b>：复用 bySubject 聚合口径（同窗口半开边界、同事件时点
 * 生效价换算、unpriced 与 cost 互补）＋ byModel/byAgentKind 分解（agentKind
 * 原值、无维度事件不参与该分桶）＋查无 subject 全零空态（不 404）；</li>
 * <li><b>项目详情内嵌成本指针</b>（跨项目域与 metering 咬合）：真项目＋真用量
 * 事件下，detail.costSummary 与成本下钻端点同数值（同数据源 bySubject）；无
 * 用量项目＝空 cost＋false 明确空态；</li>
 * <li><b>参数负例</b>：from/to 非 ISO-8601 Instant 404（类型不匹配）、分页非数值
 * 400 带字段明细（框架统一信封）。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeProjectCostSeamTest {

    /** 夹具 provider：与他类测试互不沾。 */
    private static final String PROVIDER = "backoffice-projcost";

    /** 夹具时间锚：T0…T4 逐日，窗口边界以这些整点切。 */
    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-03T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-07-04T00:00:00Z");
    private static final Instant T4 = Instant.parse("2026-07-05T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PriceEntryRepository priceEntryRepository;

    @Autowired
    private UsageEventSink usageEventSink;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 工作区软引用无 FK：夹具自增占位（隔离只求不撞号）。 */
    private long workspaceSeq = 930100L;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        // 清单是全局聚合（断言面是全表和数）：事件表前后全清；单价行只清本类
        // provider（他类测试的单价夹具不动）；项目夹具全清（本类自建，跨域咬合用）
        jdbcTemplate.update("DELETE FROM met_usage_events");
        jdbcTemplate.update("DELETE FROM met_price_entries WHERE provider = ?", PROVIDER);
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    // ---------- 清单：窗口聚合＋成本降序＋全未配价排后标注＋无用量不出现 ----------

    @Test
    void given_subjects_with_costs_when_signed_project_costs_then_cost_desc_and_unpriced_last()
            throws Exception {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-usd",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-cny",
                TokenKind.INPUT, new BigDecimal("0.000002"), "CNY", T0, null));
        // 三用量 subject：P1 混币种（标量 0.003+0.002=0.005）、P2 单币种 0.004、
        // P3 全未配价；P4 有项目无用量（清单不出现）
        report("evt-p1-usd", T1, "pc-p1", "m-usd", Map.of(), 3000);
        report("evt-p1-cny", T1, "pc-p1", "m-cny", Map.of(), 1000);
        report("evt-p2", T2, "pc-p2", "m-usd", Map.of(), 4000);
        report("evt-p3", T2, "pc-p3", "m-noprice", Map.of(), 2000);
        Project unused = newProject("无用量项目");

        signedGet("/api/backoffice/costs/projects")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 成本降序：P1（标量 0.005）> P2（0.004）> 全未配价排后
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.total").value("3"))
                .andExpect(jsonPath("$.data.items[0].projectId").value("pc-p1"))
                // 行内成本按币种分桶直读不折算（混币种不折算合并）
                .andExpect(jsonPath("$.data.items[0].cost.USD").value(0.003))
                .andExpect(jsonPath("$.data.items[0].cost.CNY").value(0.002))
                .andExpect(jsonPath("$.data.items[0].total.input").value(4000))
                .andExpect(jsonPath("$.data.items[0].allUnpriced").value(false))
                .andExpect(jsonPath("$.data.items[1].projectId").value("pc-p2"))
                .andExpect(jsonPath("$.data.items[1].cost.USD").value(0.004))
                .andExpect(jsonPath("$.data.items[1].allUnpriced").value(false))
                // 全未配价：排后＋明确标注＋成本分桶为空（不伪装 0）
                .andExpect(jsonPath("$.data.items[2].projectId").value("pc-p3"))
                .andExpect(jsonPath("$.data.items[2].allUnpriced").value(true))
                .andExpect(jsonPath("$.data.items[2].cost").isEmpty())
                .andExpect(jsonPath("$.data.items[2].total.input").value(2000));

        // 无用量项目不出现在清单（用量驱动；P4 是真项目但零事件）
        signedGet("/api/backoffice/costs/projects")
                .andExpect(jsonPath("$.data.items[*].projectId")
                        .value(not(hasItem(unused.getId().toString()))));
    }

    @Test
    void given_events_across_window_when_project_costs_with_window_then_scoped()
            throws Exception {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-w",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        // T1 窗外、T3 窗内（[T2, T4) 半开）
        report("evt-outside", T1, "pc-w", "m-w", Map.of(), 1000);
        report("evt-inside", T3, "pc-w", "m-w", Map.of(), 3000);

        String path = "/api/backoffice/costs/projects?from=" + T2 + "&to=" + T4;
        signedGet(path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].projectId").value("pc-w"))
                .andExpect(jsonPath("$.data.items[0].total.input").value(3000))
                .andExpect(jsonPath("$.data.items[0].cost.USD").value(0.003));

        // 零宽窗：空清单 200（窗口收窄后无用量 subject 不出现）
        signedGet("/api/backoffice/costs/projects?from=" + T2 + "&to=" + T2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("0"));
    }

    @Test
    void given_101_costed_subjects_when_project_costs_then_size_capped_and_pages_slice()
            throws Exception {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-page",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        // 101 个 subject 成本各不同（tokens 递增）：排序全序可证分页切片正确
        for (int i = 0; i < 101; i++) {
            report("evt-page-" + i, T1, "pc-page-" + String.format("%03d", i),
                    "m-page", Map.of(), 1000 + i);
        }

        // size 上界截断：500 → 100（首屏＝成本最高的 100 个）
        signedGet("/api/backoffice/costs/projects?size=500&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(100)))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.total").value("101"))
                // 成本降序首页首行＝token 最多（1100）
                .andExpect(jsonPath("$.data.items[0].projectId").value("pc-page-100"));

        // 第 2 页恰剩末位（成本最低＝token 最少 1000）
        signedGet("/api/backoffice/costs/projects?size=100&page=2")
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].projectId").value("pc-page-000"))
                .andExpect(jsonPath("$.data.page").value(2));

        // 越界页：空页不炸、total 原样回
        signedGet("/api/backoffice/costs/projects?size=20&page=99")
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("101"));
    }

    // ---------- 下钻：bySubject 口径一致＋空态 ----------

    @Test
    void given_project_usage_breakdown_when_signed_drill_down_then_by_subject_semantics()
            throws Exception {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-usd",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        // 有价 input（main/executor 两智能体）＋无价 output：cost/unpriced 互补
        report("evt-main", T1, "pc-d", "m-usd", Map.of("agentKind", "main"), 1000);
        report("evt-exec", T1, "pc-d", "m-usd", Map.of("agentKind", "executor"), 500);
        report("evt-nop", T1, "pc-d", "m-nop", Map.of(), new TokenUsage(0, 300, 0, 0, 0));
        report("evt-nodim", T1, "pc-d", "m-usd", Map.of(), 250);

        signedGet("/api/backoffice/costs/projects/pc-d")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.projectId").value("pc-d"))
                .andExpect(jsonPath("$.data.from").value(nullValue()))
                .andExpect(jsonPath("$.data.to").value(nullValue()))
                // 总量含无维度事件；分智能体分桶不含（bySubject 同口径）
                .andExpect(jsonPath("$.data.total.input").value(1750))
                .andExpect(jsonPath("$.data.total.output").value(300))
                // cost 只含有价分量：1750×$1/1M；无价 output 不进不伪装 0
                .andExpect(jsonPath("$.data.cost.USD").value(0.00175))
                .andExpect(jsonPath("$.data.unpriced", hasSize(1)))
                .andExpect(jsonPath("$.data.unpriced[0].model").value("m-nop"))
                .andExpect(jsonPath("$.data.unpriced[0].tokenKind").value(2))
                .andExpect(jsonPath("$.data.unpriced[0].tokenKindName").value("输出"))
                // 分模型（码序）：m-nop（无价模型照列）→ m-usd
                .andExpect(jsonPath("$.data.byModel", hasSize(2)))
                .andExpect(jsonPath("$.data.byModel[0].model").value("m-nop"))
                .andExpect(jsonPath("$.data.byModel[0].tokens.output").value(300))
                .andExpect(jsonPath("$.data.byModel[1].model").value("m-usd"))
                .andExpect(jsonPath("$.data.byModel[1].tokens.input").value(1750))
                // 分智能体（dims.agentKind 原值、码序；无维度事件不参与）；
                // agentKindName 中文名随行（#186，口径同全局总览）
                .andExpect(jsonPath("$.data.byAgentKind", hasSize(2)))
                .andExpect(jsonPath("$.data.byAgentKind[0].agentKind").value("executor"))
                .andExpect(jsonPath("$.data.byAgentKind[0].agentKindName").value("run 执行体"))
                .andExpect(jsonPath("$.data.byAgentKind[0].tokens.input").value(500))
                .andExpect(jsonPath("$.data.byAgentKind[1].agentKind").value("main"))
                .andExpect(jsonPath("$.data.byAgentKind[1].agentKindName").value("主智能体"))
                .andExpect(jsonPath("$.data.byAgentKind[1].tokens.input").value(1000));

        // 时间窗半开边界：[T2, T4) 不含 T1 事件 → 空态（窗口内无该 subject 用量）
        signedGet("/api/backoffice/costs/projects/pc-d?from=" + T2 + "&to=" + T4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value(T2.toString()))
                .andExpect(jsonPath("$.data.to").value(T4.toString()))
                .andExpect(jsonPath("$.data.total.input").value(0))
                .andExpect(jsonPath("$.data.cost").isEmpty())
                .andExpect(jsonPath("$.data.byModel").isEmpty());
    }

    @Test
    void given_unknown_or_non_numeric_subject_when_drill_down_then_zero_summary()
            throws Exception {
        // subject 不透明（底座不解释存在性）：查无此号＝全零空态，不是 404
        signedGet("/api/backoffice/costs/projects/999999999999")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total.input").value(0))
                .andExpect(jsonPath("$.data.cost").isEmpty())
                .andExpect(jsonPath("$.data.unpriced").isEmpty())
                .andExpect(jsonPath("$.data.byModel").isEmpty())
                .andExpect(jsonPath("$.data.byAgentKind").isEmpty());
        // 非数值 subject 同口径（不可能有事件＝空态）
        signedGet("/api/backoffice/costs/projects/not-a-tsid")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total.input").value(0));
    }

    // ---------- 项目详情内嵌成本指针：与成本端点同数据源（跨域咬合） ----------

    @Test
    void given_real_project_with_usage_when_detail_then_cost_summary_matches_cost_endpoint()
            throws Exception {
        priceEntryRepository.save(PriceEntry.open(PROVIDER, "m-usd",
                TokenKind.INPUT, new BigDecimal("0.000001"), "USD", T0, null));
        Project costed = newProject("有成本的项目");
        Project idle = newProject("无用量项目");
        report("evt-embed-priced", T1, costed.getId().toString(), "m-usd",
                Map.of("agentKind", "main"), 2000);
        report("evt-embed-nop", T2, costed.getId().toString(), "m-nop", Map.of(), 400);

        // 成本端点（全量窗）与项目详情内嵌 costSummary 同数值——同数据源 bySubject
        signedGet("/api/backoffice/costs/projects/" + costed.getId())
                .andExpect(jsonPath("$.data.cost.USD").value(0.002))
                .andExpect(jsonPath("$.data.unpriced", hasSize(1)));
        signedGet("/api/backoffice/projects/" + costed.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(costed.getId().toString()))
                .andExpect(jsonPath("$.data.costSummary.cost.USD").value(0.002))
                // 有无价分量 → unpriced 标记（成本不完整，下钻走成本端点）
                .andExpect(jsonPath("$.data.costSummary.unpriced").value(true));

        // 无用量项目：空 cost＋false（明确空态，非 null 非错误）
        signedGet("/api/backoffice/projects/" + idle.getId())
                .andExpect(jsonPath("$.data.costSummary.cost").isEmpty())
                .andExpect(jsonPath("$.data.costSummary.unpriced").value(false));
    }

    // ---------- 参数负例：坏窗口 → 404（类型不匹配）、坏分页 → 400（字段明细，框架口径） ----------

    @Test
    void given_bad_params_when_cost_project_endpoints_then_framework_envelope() throws Exception {
        for (String query : new String[] {"from=not-a-date", "to=2026-09-32T00:00:00Z"}) {
            signedGet("/api/backoffice/costs/projects?" + query)
                    .andExpect(status().isNotFound());
        }
        for (String query : new String[] {"size=abc", "page=0x"}) {
            signedGet("/api/backoffice/costs/projects?" + query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Parameter validation failed"));
        }
        signedGet("/api/backoffice/costs/projects/pc-x?to=not-a-date")
                .andExpect(status().isNotFound());
    }

    // ---------- 夹具 ----------

    /** 签名 GET（无体）：pathWithQuery 原样参与签名计算（与验签 Filter 同构）。 */
    private ResultActions signedGet(String pathWithQuery) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null));
    }

    /** 建项目（真库写入，跨域咬合夹具；workspaceId 占位自增）。 */
    private Project newProject(String name) {
        return projectRepository.save(
                Project.create(name, ProjectType.WEBSITE, workspaceSeq++, null));
    }

    private void report(String eventId, Instant ts, String subject, String model,
                        Map<String, String> dims, long inputTokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, subject, "run-1", "session-1",
                PROVIDER, model, dims, new TokenUsage(inputTokens, 0, 0, 0, 0)));
    }

    private void report(String eventId, Instant ts, String subject, String model,
                        Map<String, String> dims, TokenUsage tokens) {
        usageEventSink.report(new UsageEvent(eventId, ts, subject, "run-1", "session-1",
                PROVIDER, model, dims, tokens));
    }
}
