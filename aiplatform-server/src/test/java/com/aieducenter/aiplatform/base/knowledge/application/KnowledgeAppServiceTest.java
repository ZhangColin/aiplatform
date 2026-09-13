package com.aieducenter.aiplatform.base.knowledge.application;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.model.Operator;
import com.aieducenter.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 知识基座集成测试（票 #17 验收）：经端口全链路（LocalAdapter → 应用服务 →
 * pgvector 原生 SQL），embedding 以 @MockitoBean 替换（本类只验「入库/检索/清理」
 * 编排与 SQL 行为；HTTP 降级见 FastembedEmbeddingClientTest，真 embed 语义命中见
 * KnowledgePortSemanticTest）。以库内真实状态为准（B0 §5.2）。
 */
@IntegrationTest
class KnowledgeAppServiceTest {

    /** 向量维数（与 knw_chunks.embedding 列一致）。 */
    private static final int DIM = 512;

    private static final String PROJ_A = "proj-a";
    private static final String PROJ_B = "proj-b";

    @Autowired
    private KnowledgePort knowledgePort;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM knw_chunks");
        jdbcTemplate.update("DELETE FROM knw_materials");
    }

    @Test
    void given_new_spec_when_index_then_chunks_written_with_seq_and_meta() {
        KnowledgeSpec spec = spec("ARTIFACT", "proj-a:MAIN:PRD.md", PROJ_A, "电商系统", "PRD.md",
                List.of("第一段需求", "第二段需求"), Map.of("stage", "MAIN"));
        when(embeddingClient.embed(spec.chunks())).thenReturn(List.of(hot(0), hot(1)));

        knowledgePort.index(spec);

        assertThat(count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chunk FROM knw_chunks WHERE seq = 0", String.class)).isEqualTo("第一段需求");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chunk FROM knw_chunks WHERE seq = 1", String.class)).isEqualTo("第二段需求");
        // 元数据透传（底座不解释，原样可取）+ 向量落位
        assertThat(jdbcTemplate.queryForObject(
                "SELECT meta->>'stage' FROM knw_chunks WHERE seq = 0", String.class)).isEqualTo("MAIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT embedding IS NOT NULL FROM knw_chunks WHERE seq = 0", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT project_name FROM knw_chunks LIMIT 1", String.class)).isEqualTo("电商系统");
    }

    @Test
    void given_same_kind_and_source_ref_when_index_twice_then_overwritten_not_duplicated() {
        KnowledgeSpec first = spec("QA", "wait-1", PROJ_A, "电商系统", "问答纪要",
                List.of("问一", "答一"), null);
        KnowledgeSpec second = spec("QA", "wait-1", PROJ_A, "电商系统", "问答纪要",
                List.of("问二", "答二", "追问"), null);
        when(embeddingClient.embed(first.chunks())).thenReturn(List.of(hot(0), hot(1)));
        when(embeddingClient.embed(second.chunks())).thenReturn(List.of(hot(0), hot(1), hot(2)));

        knowledgePort.index(first);
        knowledgePort.index(second);   // 幂等：删后插，不叠加

        assertThat(count()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE chunk IN ('问一', '答一')", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE chunk = '追问'", Integer.class)).isEqualTo(1);
    }

    @Test
    void given_different_source_ref_when_index_then_coexist() {
        KnowledgeSpec artifact = spec("ARTIFACT", "proj-a:MAIN:PRD.md", PROJ_A, "电商系统", "PRD.md",
                List.of("需求"), null);
        KnowledgeSpec bug = spec("BUG", "bug-9", PROJ_B, "物流系统", "登录超时", List.of("缺陷"), null);
        when(embeddingClient.embed(artifact.chunks())).thenReturn(List.of(hot(0)));
        when(embeddingClient.embed(bug.chunks())).thenReturn(List.of(hot(511)));

        knowledgePort.index(artifact);
        knowledgePort.index(bug);

        assertThat(count()).isEqualTo(2);
        // 幂等键是 (kind, source_ref)：不同素材各行其是；无 meta 归一为 NULL 列
        assertThat(jdbcTemplate.queryForObject(
                "SELECT meta FROM knw_chunks WHERE kind = 'BUG'", String.class)).isNull();
    }

    @Test
    void given_embedding_unavailable_when_index_then_skip_and_keep_existing() {
        KnowledgeSpec first = spec("TEST_REPORT", "task-1", PROJ_A, "电商系统", "测试报告",
                List.of("首轮报告"), null);
        when(embeddingClient.embed(first.chunks())).thenReturn(List.of(hot(0)));
        knowledgePort.index(first);

        KnowledgeSpec retry = spec("TEST_REPORT", "task-1", PROJ_A, "电商系统", "测试报告",
                List.of("复测报告"), null);
        when(embeddingClient.embed(retry.chunks())).thenReturn(List.of());   // 服务停机：空返回

        knowledgePort.index(retry);   // 降级不炸：跳过且不删旧块

        assertThat(count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chunk FROM knw_chunks", String.class)).isEqualTo("首轮报告");
    }

    @Test
    void given_vector_count_mismatch_when_index_then_skip_and_keep_existing() {
        KnowledgeSpec first = spec("QA", "wait-2", PROJ_A, "电商系统", "问答纪要",
                List.of("问一", "答一"), null);
        when(embeddingClient.embed(first.chunks())).thenReturn(List.of(hot(0), hot(1)));
        knowledgePort.index(first);

        when(embeddingClient.embed(anyList())).thenReturn(List.of(hot(2)));   // 2 块只回 1 向量

        knowledgePort.index(spec("QA", "wait-2", PROJ_A, "电商系统", "问答纪要",
                List.of("问三", "答三"), null));

        assertThat(count()).isEqualTo(2);   // 旧块原样保留
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE chunk IN ('问三', '答三')", Integer.class))
                .isZero();
    }

    @Test
    void given_cross_project_chunks_when_retrieve_then_similar_order_and_fields() {
        // 全局跨项目纯相似（A5 §3）：query 与 proj-a 块同向、与 proj-b 块半同向
        float[] query = vector(1, 1);
        when(embeddingClient.embed(List.of("密码加密"))).thenReturn(List.of(query));
        indexDirect(spec("ARTIFACT", "proj-a:MAIN:PRD.md", PROJ_A, "电商系统", "PRD.md",
                List.of("密码加密方案"), null), vector(1, 1));
        indexDirect(spec("BUG", "bug-9", PROJ_B, "物流系统", "登录超时", List.of("登录超时缺陷"), null),
                vector(1, 0.5f));

        List<KnowledgeHit> hits = knowledgePort.retrieve("密码加密", 5);

        assertThat(hits).extracting(KnowledgeHit::sourceProjectName)
                .containsExactly("电商系统", "物流系统");   // 余弦距离升序
        assertThat(hits.get(0)).isEqualTo(new KnowledgeHit("ARTIFACT", "电商系统", "PRD.md", "密码加密方案"));
        assertThat(hits.get(1)).isEqualTo(new KnowledgeHit("BUG", "物流系统", "登录超时", "登录超时缺陷"));
    }

    @Test
    void given_top_k_one_when_retrieve_then_single_best_hit() {
        when(embeddingClient.embed(List.of("密码加密"))).thenReturn(List.of(vector(1, 1)));
        indexDirect(spec("ARTIFACT", "proj-a:MAIN:PRD.md", PROJ_A, "电商系统", "PRD.md",
                List.of("密码加密方案"), null), vector(1, 1));
        indexDirect(spec("BUG", "bug-9", PROJ_B, "物流系统", "登录超时", List.of("登录超时缺陷"), null),
                vector(1, 0.5f));

        assertThat(knowledgePort.retrieve("密码加密", 1))
                .containsExactly(new KnowledgeHit("ARTIFACT", "电商系统", "PRD.md", "密码加密方案"));
    }

    @Test
    void given_embedding_unavailable_when_retrieve_then_empty_no_error() {
        when(embeddingClient.embed(anyList())).thenReturn(List.of());

        assertThat(knowledgePort.retrieve("任意查询", 5)).isEmpty();
    }

    @Test
    void given_project_chunks_when_purge_by_project_then_only_that_project_removed() {
        indexDirect(spec("ARTIFACT", "proj-a:MAIN:PRD.md", PROJ_A, "电商系统", "PRD.md",
                List.of("需求"), null), hot(0));
        indexDirect(spec("BUG", "bug-9", PROJ_B, "物流系统", "登录超时", List.of("缺陷"), null), hot(1));

        knowledgePort.purgeByProject(PROJ_A);

        assertThat(count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT project_id FROM knw_chunks", String.class)).isEqualTo(PROJ_B);
    }

    @Test
    void given_unknown_project_when_purge_by_project_then_no_error() {
        knowledgePort.purgeByProject("nobody");

        assertThat(count()).isZero();
    }

    @Test
    void given_incomplete_spec_when_index_then_rejected_nothing_written() {
        when(embeddingClient.embed(anyList())).thenReturn(List.of(hot(0)));

        assertThatThrownBy(() -> knowledgePort.index(
                spec(" ", "ref", PROJ_A, "电商系统", "PRD.md", List.of("块"), null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("知识素材字段不完整");

        assertThat(count()).isZero();
    }

    @Test
    void given_blank_chunks_when_index_then_degraded_skip_no_error() {
        // 空素材（空 PRD 等）是运行期条件而非编程错误：记日志跳过，不炸（A5 §1）
        knowledgePort.index(spec("ARTIFACT", "ref", PROJ_A, "电商系统", "PRD.md",
                List.of("  "), null));
        knowledgePort.index(spec("ARTIFACT", "ref", PROJ_A, "电商系统", "PRD.md",
                List.of(), null));

        assertThat(count()).isZero();
    }

    @Test
    void given_blank_query_when_retrieve_then_rejected() {
        assertThatThrownBy(() -> knowledgePort.retrieve(" ", 5))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("检索 query 不能为空");
    }

    @Test
    void given_non_positive_top_k_when_retrieve_then_rejected() {
        when(embeddingClient.embed(anyList())).thenReturn(List.of(hot(0)));

        assertThatThrownBy(() -> knowledgePort.retrieve("密码加密", 0))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("topK 必须为正数");
        assertThatThrownBy(() -> knowledgePort.retrieve("密码加密", -1))
                .hasMessageContaining("topK 必须为正数");
    }

    @Test
    void given_blank_project_id_when_purge_then_rejected() {
        assertThatThrownBy(() -> knowledgePort.purgeByProject(" "))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("projectId 不能为空");
    }

    // ---------- 素材状态：停用⇄启用可逆开关＋检索状态过滤（#153） ----------

    @Test
    void given_disabled_material_when_retrieve_then_excluded_and_peer_still_hits() {
        // 同 kind 不同 source_ref 的两份 PRD（沉淀幂等键的真实形态）；query 与 A 同向、与 B 半同向
        when(embeddingClient.embed(List.of("密码加密"))).thenReturn(List.of(vector(1, 1)));
        indexDirect(spec("PRD", PROJ_A, PROJ_A, "电商系统", "PRD", List.of("密码加密方案"), null), vector(1, 1));
        indexDirect(spec("PRD", PROJ_B, PROJ_B, "物流系统", "PRD", List.of("登录超时治理"), null), vector(1, 0.5f));
        assertThat(knowledgePort.retrieve("密码加密", 5)).hasSize(2);   // 停用前：皆命中

        knowledgePort.disable("PRD", PROJ_A, OPERATOR);

        // 停用素材的块整体退出命中，未停用素材照常进位
        assertThat(knowledgePort.retrieve("密码加密", 5))
                .extracting(KnowledgeHit::sourceProjectName)
                .containsExactly("物流系统");
    }

    @Test
    void given_disabled_then_enabled_when_retrieve_then_hits_restored() {
        when(embeddingClient.embed(List.of("密码加密"))).thenReturn(List.of(vector(1, 1)));
        indexDirect(spec("PRD", PROJ_A, PROJ_A, "电商系统", "PRD", List.of("密码加密方案"), null), vector(1, 1));

        knowledgePort.disable("PRD", PROJ_A, OPERATOR);
        assertThat(knowledgePort.retrieve("密码加密", 5)).isEmpty();

        knowledgePort.enable("PRD", PROJ_A, OPERATOR);

        assertThat(knowledgePort.retrieve("密码加密", 5))
                .extracting(KnowledgeHit::chunk).containsExactly("密码加密方案");
    }

    @Test
    void given_reingest_after_disable_when_index_then_status_survives_and_registry_single_row() {
        KnowledgeSpec first = spec("PRD", PROJ_A, PROJ_A, "电商系统", "PRD", List.of("旧需求"), null);
        KnowledgeSpec second = spec("PRD", PROJ_A, PROJ_A, "电商系统", "PRD", List.of("新需求"), null);
        when(embeddingClient.embed(first.chunks())).thenReturn(List.of(hot(0)));
        when(embeddingClient.embed(second.chunks())).thenReturn(List.of(hot(1)));
        when(embeddingClient.embed(List.of("新需求"))).thenReturn(List.of(hot(1)));
        knowledgePort.index(first);
        knowledgePort.disable("PRD", PROJ_A, OPERATOR);
        Object materialId = jdbcTemplate.queryForObject(
                "SELECT id FROM knw_materials", Object.class);
        Timestamp firstSunkAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM knw_materials", Timestamp.class);

        knowledgePort.index(second);   // 重沉淀（重试归档再触发）：幂等换块

        // 登记表单行、状态仍停用、操作者/id/首沉淀时间保留——被治理素材重沉淀不复活
        assertThat(registryCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM knw_materials", Object.class)).isEqualTo(materialId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at FROM knw_materials", Timestamp.class)).isEqualTo(firstSunkAt);
        assertThat(registryStatus()).isEqualTo(MaterialStatus.DISABLED.getCode());
        assertThat(registryField("operator_id")).isEqualTo("42");
        assertThat(registryField("operator_name")).isEqualTo("运营张三");
        assertThat(knowledgePort.retrieve("新需求", 5)).isEmpty();

        knowledgePort.enable("PRD", PROJ_A, OPERATOR);

        assertThat(knowledgePort.retrieve("新需求", 5))
                .extracting(KnowledgeHit::chunk).containsExactly("新需求");
    }

    @Test
    void given_purge_by_project_when_material_disabled_then_registry_cascaded_too() {
        // 级联照删含已停用素材：登记行与块一并清理，未涉及项目不受扰
        indexDirect(spec("PRD", PROJ_A, PROJ_A, "电商系统", "PRD", List.of("需求"), null), hot(0));
        indexDirect(spec("PRD", PROJ_B, PROJ_B, "物流系统", "PRD", List.of("缺陷"), null), hot(1));
        knowledgePort.disable("PRD", PROJ_A, OPERATOR);

        knowledgePort.purgeByProject(PROJ_A);

        assertThat(registryCount()).isEqualTo(1);
        assertThat(registryField("project_id")).isEqualTo(PROJ_B);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT project_id FROM knw_chunks", String.class)).isEqualTo(PROJ_B);
    }

    @Test
    void given_index_when_registry_row_written_then_identity_and_display_fields_present() {
        indexDirect(spec("PRD", PROJ_A, PROJ_A, "电商系统", "PRD", List.of("需求"), null), hot(0));

        assertThat(registryCount()).isEqualTo(1);
        assertThat(registryField("kind")).isEqualTo("PRD");
        assertThat(registryField("source_ref")).isEqualTo(PROJ_A);
        assertThat(registryField("project_name")).isEqualTo("电商系统");
        assertThat(registryField("title")).isEqualTo("PRD");
        assertThat(registryField("status")).isEqualTo("1");   // MaterialStatus.ENABLED code
        assertThat(registryField("operator_id")).isNull();   // 未治理过无操作者
    }

    @Test
    void given_already_disabled_when_disable_again_then_ok_without_error() {
        indexDirect(spec("PRD", PROJ_A, PROJ_A, "电商系统", "PRD", List.of("需求"), null), hot(0));

        knowledgePort.disable("PRD", PROJ_A, OPERATOR);
        knowledgePort.disable("PRD", PROJ_A, new Operator("43", "运营李四"));   // 幂等：可重复治理

        assertThat(registryStatus()).isEqualTo(MaterialStatus.DISABLED.getCode());
        assertThat(registryField("operator_id")).isEqualTo("43");   // 留最近管理动作操作者
    }

    @Test
    void given_unknown_material_when_disable_then_rejected() {
        assertThatThrownBy(() -> knowledgePort.disable("PRD", "nobody", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("知识素材不存在");
        assertThatThrownBy(() -> knowledgePort.enable("PRD", "nobody", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("知识素材不存在");
    }

    @Test
    void given_blank_identity_or_operator_when_disable_then_rejected() {
        assertThatThrownBy(() -> knowledgePort.disable(" ", PROJ_A, OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("知识素材字段不完整");
        assertThatThrownBy(() -> knowledgePort.disable("PRD", " ", OPERATOR))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("知识素材字段不完整");
        assertThatThrownBy(() -> knowledgePort.disable("PRD", PROJ_A, new Operator(" ", "运营张三")))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("操作者不能为空");
        assertThatThrownBy(() -> knowledgePort.enable("PRD", PROJ_A, new Operator("42", null)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("操作者不能为空");
    }

    // ---------- fixture ----------

    /** 治理操作者（admin 侧管理员标识，非平台用户——#151 口径）。 */
    private static final Operator OPERATOR = new Operator("42", "运营张三");

    private int registryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knw_materials", Integer.class);
    }

    private int registryStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM knw_materials", Integer.class);
    }

    /** 登记表唯一行的字段直读（用例保证单行时使用）。 */
    private String registryField(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM knw_materials", String.class);
    }

    private KnowledgeSpec spec(String kind, String sourceRef, String projectId, String projectName,
                               String title, List<String> chunks, Map<String, Object> meta) {
        return new KnowledgeSpec(kind, sourceRef, projectId, projectName, title, chunks, meta);
    }

    /** 绕过 embedding 直插（本类 embed 被 mock，replace 的入参由用例直接给定）。 */
    private void indexDirect(KnowledgeSpec spec, float[]... vectors) {
        when(embeddingClient.embed(spec.chunks())).thenReturn(List.of(vectors));
        knowledgePort.index(spec);
    }

    /** 前 two 维非零、其余为零的 512 维向量（与向量列同维）。 */
    private static float[] vector(double first, double second) {
        float[] v = new float[DIM];
        v[0] = (float) first;
        v[1] = (float) second;
        return v;
    }

    private static float[] hot(int index) {
        float[] v = new float[DIM];
        v[index] = 1;
        return v;
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knw_chunks", Integer.class);
    }
}
