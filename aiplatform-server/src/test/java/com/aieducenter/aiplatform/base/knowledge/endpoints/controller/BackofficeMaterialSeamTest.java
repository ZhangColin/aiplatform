package com.aieducenter.aiplatform.base.knowledge.endpoints.controller;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aieducenter.aiplatform.backoffice.BackofficeSeamTest;
import com.aieducenter.aiplatform.backoffice.BackofficeSignatures;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.aieducenter.aiplatform.base.knowledge.domain.model.Operator;
import com.aieducenter.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识素材管理四端点在 {@code #152} seam 上全绿：真过滤链（签名闸/会话豁免/
 * 强制拦截器）＋真应用服务＋aiplatform_test 真库。素材夹具经 {@code KnowledgePort.index}
 * 沉淀（embedding 以 @MockitoBean 给定向量，同 KnowledgeAppServiceTest 口径——
 * 真 embed 语义命中另有 KnowledgePortSemanticTest），夹具项目 id 逐一登记、
 * teardown 精确清理（不动他人夹具）。
 *
 * <p>#166 四面一链钉死：</p>
 * <ul>
 * <li><b>清单</b>：三过滤维度（状态单选含缺省全部/沉淀时间闭区间/来源项目 id
 * 精确）＋沉淀时间倒序＋分页行走与越界空页＋非法参数（框架信封）；</li>
 * <li><b>详情</b>：元数据＋PRD 全文块按 seq 以空行拼接＋来源项目缺档容缺不炸
 * ＋未寻址/畸形 id 404；</li>
 * <li><b>停用⇄启用</b>（端到端）：端点操作 → 检索命中变化（停用素材退出、启用
 * 恢复、未停用素材照常）；留痕落素材级（JdbcTemplate 断言登记行 operator 两列
 * 与状态）；重复治理幂等；缺操作者头 400 KNW_006（治理动作必留痕，与单价表
 * 缺头落空有意不同——知识治理无种子脚本通道）；</li>
 * <li><b>删除</b>：清单/详情/检索均不可见、登记行与块两表无行（无行可留不留痕）、
 * 来源项目不受动（真项目行存活）、重复删除 404。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeMaterialSeamTest {

    /** 向量维数（与 knw_chunks.embedding 列一致）。 */
    private static final int DIM = 512;

    /** 夹具素材类别（v1 业务口径唯一值）。 */
    private static final String KIND = "PRD";

    /** 夹具沉淀时间锚（远过去，避开 now 抖动）。 */
    private static final Instant T_A = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant T_B = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant T_C = Instant.parse("2026-08-03T00:00:00Z");

    /** 操作者测试身份：admin 侧管理员的 TSID＋昵称样例（同订单 seam 口径）。 */
    private static final String OPERATOR_ID = "700180";
    private static final String OPERATOR_NAME = "运营·知识治理员";
    private static final String OPERATOR_ID_2 = "700181";
    private static final String OPERATOR_NAME_2 = "运营·知识副手";

    /** 夹具项目 workspace id（真实项目行夹具用，测试内唯一即可）。 */
    private static final long WORKSPACE_ID = 75_000_001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgePort knowledgePort;

    @Autowired
    private ProjectRepository projectRepository;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    /** 已沉淀夹具的项目 id（teardown 精确清理 knw_ 两表）。 */
    private final List<String> fixtureProjectIds = new ArrayList<>();

    /** 已种入的真实项目行（teardown 清理 prj_projects）。 */
    private final List<Long> plantedProjectIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String projectId : fixtureProjectIds) {
            jdbcTemplate.update("DELETE FROM knw_chunks WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM knw_materials WHERE project_id = ?", projectId);
        }
        fixtureProjectIds.clear();
        for (Long projectId : plantedProjectIds) {
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
        plantedProjectIds.clear();
    }

    // ---------- 清单：三过滤维度＋倒序＋分页＋非法参数 ----------

    @Test
    void given_materials_when_signed_list_then_filters_desc_order_and_paging() throws Exception {
        long a = plant("bk-proj-a", "电商系统", List.of("块A"), vector(1, 1));
        long b = plant("bk-proj-b", "物流系统", List.of("块B"), vector(1, 0.5f));
        long c = plant("bk-proj-c", "客服系统", List.of("块C"), vector(1, 0));
        anchorSunkAt(a, T_A);
        anchorSunkAt(b, T_B);
        anchorSunkAt(c, T_C);
        knowledgePort.disable(KIND, "bk-proj-b", new Operator(OPERATOR_ID, OPERATOR_NAME));

        // 缺省＝全部：3 行，沉淀时间倒序（新沉淀在前）；被治理行带状态与操作者
        signedGet("/api/backoffice/materials")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value("3"))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(c)))
                .andExpect(jsonPath("$.data.items[0].projectId").value("bk-proj-c"))
                .andExpect(jsonPath("$.data.items[0].projectName").value("客服系统"))
                .andExpect(jsonPath("$.data.items[0].status").value(1))
                .andExpect(jsonPath("$.data.items[0].statusName").value("启用"))
                .andExpect(jsonPath("$.data.items[0].operatorId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].id").value(Long.toString(b)))
                .andExpect(jsonPath("$.data.items[1].status").value(2))
                .andExpect(jsonPath("$.data.items[1].statusName").value("停用"))
                .andExpect(jsonPath("$.data.items[1].operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.items[1].operatorName").value(OPERATOR_NAME))
                .andExpect(jsonPath("$.data.items[2].id").value(Long.toString(a)));

        // 状态单选：停用档 → 仅 B；启用档 → A、C
        signedGet("/api/backoffice/materials?status=2")
                .andExpect(jsonPath("$.data.total").value("1"))
                .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(b)));
        signedGet("/api/backoffice/materials?status=1")
                .andExpect(jsonPath("$.data.total").value("2"))
                .andExpect(jsonPath("$.data.items", hasSize(2)));

        // 沉淀时间闭区间 [T_A, T_B]（含两端）→ A、B
        signedGet("/api/backoffice/materials?sunkFrom=2026-08-01T00:00:00Z"
                        + "&sunkTo=2026-08-02T00:00:00Z")
                .andExpect(jsonPath("$.data.total").value("2"))
                .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(b)))
                .andExpect(jsonPath("$.data.items[1].id").value(Long.toString(a)));
        // 半开于一侧：sunkFrom 严格晚于 T_B → 仅 C
        signedGet("/api/backoffice/materials?sunkFrom=2026-08-02T00:00:01Z")
                .andExpect(jsonPath("$.data.total").value("1"))
                .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(c)));

        // 来源项目 id 精确：命中一行；查无 → 如实空页 200
        signedGet("/api/backoffice/materials?projectId=bk-proj-c")
                .andExpect(jsonPath("$.data.total").value("1"))
                .andExpect(jsonPath("$.data.items[0].projectId").value("bk-proj-c"));
        signedGet("/api/backoffice/materials?projectId=bk-nobody")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value("0"))
                .andExpect(jsonPath("$.data.items").isEmpty());

        // 分页行走：size 2 → 首页 2 行、次页 1 行；越界页空清单
        signedGet("/api/backoffice/materials?size=2")
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.total").value("3"));
        signedGet("/api/backoffice/materials?size=2&page=2")
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.page").value(2));
        signedGet("/api/backoffice/materials?size=2&page=9")
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value("3"));

        // 非法参数：非法状态 code → 400 带合法取值表；非法时间 → 404；非法分页 → 400 带字段明细
        signedGet("/api/backoffice/materials?status=99")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status 取值 99 非法，合法取值：1=启用, 2=停用"));
        signedGet("/api/backoffice/materials?sunkFrom=not-a-time")
                .andExpect(status().isNotFound());
        signedGet("/api/backoffice/materials?page=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parameter validation failed"));
    }

    // ---------- 详情：元数据＋全文块序＋容缺＋404 ----------

    @Test
    void given_material_when_signed_detail_then_metadata_and_fulltext_by_seq() throws Exception {
        // 来源项目无档（prj_projects 无此行）：引用容缺直读登记面，不炸
        long id = plant("bk-ghost-proj", "电商系统", List.of("第一段需求", "第二段需求", "第三段需求"),
                vector(1, 1), vector(1, 1), vector(1, 1));
        anchorSunkAt(id, T_A);
        // 打乱块 seq（存储序 ≠ 插入序）：全文拼接必须按 seq 重排，证 ORDER BY seq 非巧合
        jdbcTemplate.update(
                "UPDATE knw_chunks SET seq = 2 - seq WHERE kind = ? AND source_ref = ?",
                KIND, "bk-ghost-proj");

        signedGet("/api/backoffice/materials/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.kind").value(KIND))
                .andExpect(jsonPath("$.data.projectId").value("bk-ghost-proj"))
                .andExpect(jsonPath("$.data.projectName").value("电商系统"))
                .andExpect(jsonPath("$.data.title").value("PRD"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.statusName").value("启用"))
                .andExpect(jsonPath("$.data.sunkAt").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.operatorId").value(nullValue()))
                .andExpect(jsonPath("$.data.operatorName").value(nullValue()))
                // PRD 全文：块按 seq 以空行拼接（段落级重组；seq 已倒序 → 全文倒序）
                .andExpect(jsonPath("$.data.content").value("第三段需求\n\n第二段需求\n\n第一段需求"));

        // 未寻址与畸形 id：皆 404 KNW_005
        signedGet("/api/backoffice/materials/999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("知识素材不存在"));
        signedGet("/api/backoffice/materials/not-a-tsid")
                .andExpect(status().isNotFound());
    }

    // ---------- 停用⇄启用：端到端命中变化＋素材级留痕＋负例 ----------

    @Test
    void given_two_materials_when_signed_disable_then_excluded_then_enable_restored() throws Exception {
        long a = plant("bk-proj-a", "电商系统", List.of("密码加密方案"), vector(1, 1));
        long b = plant("bk-proj-b", "物流系统", List.of("登录超时治理"), vector(1, 0.5f));
        when(embeddingClient.embed(List.of("密码加密"))).thenReturn(List.of(vector(1, 1)));

        // 停用前：两素材皆命中（余弦距离升序——A 同向在前）
        assertThat(knowledgePort.retrieve("密码加密", 5))
                .extracting(KnowledgeHit::sourceProjectName)
                .containsExactly("电商系统", "物流系统");

        // 停用 A（操作者透传头落痕）
        signedPost("/api/backoffice/materials/" + a + "/disable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(a)))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.statusName").value("停用"))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME));

        // 留痕落素材级（JdbcTemplate）：登记行状态与操作者两列
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, operator_id, operator_name FROM knw_materials WHERE id = ?", a);
        assertThat(row.get("status")).isEqualTo(2);
        assertThat(row.get("operator_id")).isEqualTo(OPERATOR_ID);
        assertThat(row.get("operator_name")).isEqualTo(OPERATOR_NAME);

        // 端到端：停用素材退出检索命中，未停用素材照常进位
        assertThat(knowledgePort.retrieve("密码加密", 5))
                .extracting(KnowledgeHit::sourceProjectName)
                .containsExactly("物流系统");

        // 重复停用幂等：200、操作者留最近一次
        signedPost("/api/backoffice/materials/" + a + "/disable", OPERATOR_ID_2, OPERATOR_NAME_2)
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, operator_id FROM knw_materials WHERE id = ?", a))
                .containsEntry("status", 2)
                .containsEntry("operator_id", OPERATOR_ID_2);

        // 启用恢复命中
        signedPost("/api/backoffice/materials/" + a + "/enable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.statusName").value("启用"))
                .andExpect(jsonPath("$.data.operatorId").value(OPERATOR_ID))
                .andExpect(jsonPath("$.data.operatorName").value(OPERATOR_NAME));
        // 留痕落素材级（JdbcTemplate）：启用侧同样落操作者两列
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, operator_id, operator_name FROM knw_materials WHERE id = ?", a))
                .containsEntry("status", 1)
                .containsEntry("operator_id", OPERATOR_ID)
                .containsEntry("operator_name", OPERATOR_NAME);
        assertThat(knowledgePort.retrieve("密码加密", 5))
                .extracting(KnowledgeHit::sourceProjectName)
                .containsExactly("电商系统", "物流系统");

        // 缺操作者头：400 KNW_006（治理动作必留痕，无落空通道）
        signedPost("/api/backoffice/materials/" + a + "/disable", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("操作者不能为空"));

        // 未寻址：404 KNW_005
        signedPost("/api/backoffice/materials/999999999/disable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("知识素材不存在"));
        signedPost("/api/backoffice/materials/999999999/enable", OPERATOR_ID, OPERATOR_NAME)
                .andExpect(status().isNotFound());
    }

    // ---------- 删除：两面不可见＋两表无行＋来源项目不受动 ----------

    @Test
    void given_material_with_real_source_project_when_signed_delete_then_gone_and_project_untouched()
            throws Exception {
        Project source = projectRepository.save(
                Project.create("bk 来源项目", ProjectType.WEBSITE, WORKSPACE_ID, null));
        plantedProjectIds.add(source.getId());
        long id = plant(source.getId().toString(), "bk 来源项目", List.of("独有内容块"), hot(0));
        when(embeddingClient.embed(List.of("独有内容"))).thenReturn(List.of(hot(0)));
        assertThat(knowledgePort.retrieve("独有内容", 5)).hasSize(1);

        // 删除：回执＝删除前终态（确认移除了什么）
        String path = "/api/backoffice/materials/" + id;
        mockMvc.perform(BackofficeSignatures.signed(delete(path), path, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.projectId").value(source.getId().toString()))
                .andExpect(jsonPath("$.data.projectName").value("bk 来源项目"));

        // 两表无行（无行可留、不留痕）；清单与详情均不可见；检索不可见
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_materials WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE kind = ? AND source_ref = ?",
                Integer.class, KIND, source.getId().toString())).isZero();
        signedGet("/api/backoffice/materials?projectId=" + source.getId())
                .andExpect(jsonPath("$.data.total").value("0"));
        signedGet(path)
                .andExpect(status().isNotFound());
        assertThat(knowledgePort.retrieve("独有内容", 5)).isEmpty();

        // 来源项目不受动（管理删除与项目删除级联正交）
        assertThat(projectRepository.findById(source.getId())).isPresent();

        // 重复删除：无行可删 → 404
        mockMvc.perform(BackofficeSignatures.signed(delete(path), path, null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("知识素材不存在"));
    }

    // ---------- 夹具 ----------

    /** 经端口沉淀夹具素材（sourceRef＝projectId，v1 业务口径），回素材登记 id。 */
    private long plant(String projectId, String projectName, List<String> chunks, float[]... vectors) {
        fixtureProjectIds.add(projectId);
        when(embeddingClient.embed(chunks)).thenReturn(List.of(vectors));
        knowledgePort.index(new KnowledgeSpec(KIND, projectId, projectId, projectName, "PRD",
                chunks, null));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM knw_materials WHERE project_id = ?", Long.class, projectId);
    }

    /** 夹具沉淀时间锚（登记行 created_at＝首沉淀时间，直改以获得确定区间与序）。 */
    private void anchorSunkAt(long id, Instant sunkAt) {
        jdbcTemplate.update("UPDATE knw_materials SET created_at = ? WHERE id = ?",
                Timestamp.from(sunkAt), id);
    }

    /** 签名 GET（无体）：pathWithQuery 原样参与签名计算（与验签 Filter 同构）。 */
    private ResultActions signedGet(String pathWithQuery) throws Exception {
        return mockMvc.perform(BackofficeSignatures.signed(
                get(pathWithQuery), pathWithQuery, null));
    }

    /** 签名 POST（无体）＋操作者透传头；operatorId 传 null 即缺头负例形制。 */
    private ResultActions signedPost(String path, String operatorId, String operatorName)
            throws Exception {
        MockHttpServletRequestBuilder request = BackofficeSignatures.signed(post(path), path, null);
        if (operatorId != null) {
            request.header("X-User-Id", operatorId);
        }
        if (operatorName != null) {
            request.header("X-User-Name", operatorName);
        }
        return mockMvc.perform(request);
    }

    /** 前 two 维非零、其余为零的 512 维向量（与向量列同维）。 */
    private static float[] vector(double first, double second) {
        float[] v = new float[DIM];
        v[0] = (float) first;
        v[1] = (float) second;
        return v;
    }

    /** 单位向量（第 index 维为 1）。 */
    private static float[] hot(int index) {
        float[] v = new float[DIM];
        v[index] = 1;
        return v;
    }
}
