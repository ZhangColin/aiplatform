package com.semgravio.aiplatform.base.knowledge.endpoints.controller;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.base.knowledge.domain.model.EmbeddingHealth;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeRetrieval;
import com.semgravio.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台命中留痕与向量服务健康只读读面（#639）在 {@code #152} seam 上全绿：真过滤链
 * （签名闸/会话豁免/强制拦截器）＋真应用服务＋aiplatform_test 真库。夹具经
 * {@link KnowledgePort#recordRetrieval} 写入并锚定时间；teardown 清本项目夹具行
 * （不动他人夹具）。
 *
 * <p>钉死面：</p>
 * <ul>
 * <li><b>留痕清单</b>：项目精确＋检索时间闭区间＋时间倒序＋分页行走；行字段＝
 * 项目／时间／命中条目（类别、标题、来源项目展示名、片段）／是否降级；</li>
 * <li><b>降级可区分</b>：成功 0 条 = degraded=false、hits 空；降级 = degraded=true；</li>
 * <li><b>健康读面</b>：可用／不可用两态＋检查时间＋耗时；</li>
 * <li><b>鉴权</b>：非签名请求被拒（401）。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeKnowledgeRetrievalSeamTest {

    private static final String PROJ_A = "bk-ret-a";
    private static final String PROJ_B = "bk-ret-b";

    /** 检索时间锚（远过去，避开 now 抖动）。 */
    private static final Instant T_A = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant T_B = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant T_C = Instant.parse("2026-08-03T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgePort knowledgePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    private final List<String> fixtureProjectIds = new java.util.ArrayList<>();

    @AfterEach
    void teardown() {
        for (String projectId : fixtureProjectIds) {
            jdbcTemplate.update("DELETE FROM knw_retrievals WHERE project_id = ?", projectId);
        }
        fixtureProjectIds.clear();
    }

    // ---------- 留痕清单：两过滤维度＋倒序＋分页＋降级区分 ----------

    @Test
    void given_records_when_signed_list_then_filters_desc_order_and_degraded_distinguishable()
            throws Exception {
        long hitId = plant(PROJ_A, T_A, KnowledgeRetrieval.of(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。"))));
        long degradedId = plant(PROJ_B, T_B, KnowledgeRetrieval.unavailable());
        long zeroHitId = plant(PROJ_A, T_C, KnowledgeRetrieval.empty());

        // 全量：时间倒序（C 新在前）、降级与真 0 条可区分、行字段齐全
        signedGet("/api/backoffice/knowledge-retrievals")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value("3"))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(zeroHitId)))
                .andExpect(jsonPath("$.data.items[0].projectId").value(PROJ_A))
                .andExpect(jsonPath("$.data.items[0].recordedAt").value("2026-08-03T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].degraded").value(false))
                .andExpect(jsonPath("$.data.items[0].hits").isEmpty())
                .andExpect(jsonPath("$.data.items[1].id").value(Long.toString(degradedId)))
                .andExpect(jsonPath("$.data.items[1].degraded").value(true))
                .andExpect(jsonPath("$.data.items[1].hits").isEmpty())
                .andExpect(jsonPath("$.data.items[2].id").value(Long.toString(hitId)))
                .andExpect(jsonPath("$.data.items[2].degraded").value(false))
                .andExpect(jsonPath("$.data.items[2].hits", hasSize(1)))
                .andExpect(jsonPath("$.data.items[2].hits[0].kind").value("PRD"))
                .andExpect(jsonPath("$.data.items[2].hits[0].sourceProjectName")
                        .value("宠物医院预约平台"))
                .andExpect(jsonPath("$.data.items[2].hits[0].title").value("PRD·宠物医院预约"))
                .andExpect(jsonPath("$.data.items[2].hits[0].chunk")
                        .value("核心场景：主人在线选医生预约。"));

        // 项目精确：A 两条（B 不串台）
        signedGet("/api/backoffice/knowledge-retrievals?projectId=" + PROJ_A)
                .andExpect(jsonPath("$.data.total").value("2"))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].projectId").value(PROJ_A))
                .andExpect(jsonPath("$.data.items[1].projectId").value(PROJ_A));
        // 查无 → 如实空页 200
        signedGet("/api/backoffice/knowledge-retrievals?projectId=bk-nobody")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value("0"))
                .andExpect(jsonPath("$.data.items").isEmpty());

        // 检索时间闭区间 [T_A, T_B]（含两端）→ A、B
        signedGet("/api/backoffice/knowledge-retrievals?from=2026-08-01T00:00:00Z"
                        + "&to=2026-08-02T00:00:00Z")
                .andExpect(jsonPath("$.data.total").value("2"))
                .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(degradedId)))
                .andExpect(jsonPath("$.data.items[1].id").value(Long.toString(hitId)));

        // 分页行走：size 2 → 首页 2 行、次页 1 行；越界页空清单
        signedGet("/api/backoffice/knowledge-retrievals?size=2")
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total").value("3"));
        signedGet("/api/backoffice/knowledge-retrievals?size=2&page=2")
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.page").value(2));
        signedGet("/api/backoffice/knowledge-retrievals?size=2&page=9")
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("3"));
    }

    @Test
    void given_no_records_when_signed_list_then_empty_zero() throws Exception {
        signedGet("/api/backoffice/knowledge-retrievals?projectId=bk-ret-none")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value("0"))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    // ---------- 向量服务健康：两态 ----------

    @Test
    void given_embedding_health_when_signed_health_then_available_and_unavailable_states()
            throws Exception {
        when(embeddingClient.health()).thenReturn(new EmbeddingHealth(
                true, Instant.parse("2026-11-25T08:00:00Z"), 12L));
        signedGet("/api/backoffice/knowledge-health")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.checkedAt").value("2026-11-25T08:00:00Z"))
                .andExpect(jsonPath("$.data.elapsedMs").value(12));

        when(embeddingClient.health()).thenReturn(new EmbeddingHealth(
                false, Instant.parse("2026-11-25T08:01:00Z"), 2001L));
        signedGet("/api/backoffice/knowledge-health")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.checkedAt").value("2026-11-25T08:01:00Z"))
                .andExpect(jsonPath("$.data.elapsedMs").value(2001));
    }

    // ---------- 鉴权 ----------

    @Test
    void given_no_signature_when_any_read_then_401() throws Exception {
        mockMvc.perform(get("/api/backoffice/knowledge-retrievals"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Signature required"));
        mockMvc.perform(get("/api/backoffice/knowledge-health"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
    }

    // ---------- 夹具 ----------

    /** 经端口写一条留痕并锚定检索时间，回记录 id。 */
    private long plant(String projectId, Instant recordedAt, KnowledgeRetrieval retrieval) {
        fixtureProjectIds.add(projectId);
        knowledgePort.recordRetrieval(projectId, retrieval);
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM knw_retrievals WHERE project_id = ?", Long.class, projectId);
        jdbcTemplate.update("UPDATE knw_retrievals SET created_at = ? WHERE id = ?",
                Timestamp.from(recordedAt), id);
        return id;
    }

    private ResultActions signedGet(String pathWithQuery) throws Exception {
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null);
        return mockMvc.perform(request);
    }
}
