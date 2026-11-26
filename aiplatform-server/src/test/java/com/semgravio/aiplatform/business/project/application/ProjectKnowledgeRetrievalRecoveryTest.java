package com.semgravio.aiplatform.business.project.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeSpec;
import com.semgravio.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 命中留痕落库与重启恢复真库验收（#639）：真端口（LocalAdapter → 应用服务 →
 * knw_retrievals / knw_chunks）＋embedding mock。覆盖：
 * <ul>
 * <li>检索有命中 → 留痕一条（项目、时间、条目、未降级），块可组装；</li>
 * <li>新服务实例（模拟重启）拿回同一份块且不重检索；</li>
 * <li>检索成功但 0 条 → 留痕未降级、0 条；向量服务不可用 → 留痕降级——两者可区分；</li>
 * <li>项目删除级联清理该项目的留痕。</li>
 * </ul>
 */
@IntegrationTest
class ProjectKnowledgeRetrievalRecoveryTest {

    /** 向量维数（与 knw_chunks.embedding 列一致）。 */
    private static final int DIM = 512;

    private static final String REQUIREMENT = "给宠物医院做预约系统";

    @Autowired
    private ProjectKnowledgeAppService appService;

    @Autowired
    private ProjectQueryAppService projectQueryAppService;

    @Autowired
    private KnowledgePort knowledgePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM knw_retrievals");
        jdbcTemplate.update("DELETE FROM knw_chunks");
        jdbcTemplate.update("DELETE FROM knw_materials");
    }

    @Test
    void given_hits_when_establish_then_recorded_and_new_instance_rebuilds_same_block() {
        // 素材：一段与需求同题的块（检索命中）
        when(embeddingClient.embed(List.of("核心场景：主人在线选医生预约。"))).thenReturn(List.of(hot(0)));
        knowledgePort.index(new KnowledgeSpec("PRD", "9509", "9509", "宠物医院预约平台",
                "PRD", List.of("核心场景：主人在线选医生预约。"), null));
        when(embeddingClient.embed(List.of(REQUIREMENT))).thenReturn(List.of(hot(0)));

        String block = appService.establishSessionInjection(9530L, REQUIREMENT);

        assertThat(block).contains("〔宠物医院预约平台｜历史需求〕PRD")
                .contains("核心场景：主人在线选医生预约。")
                .contains("这些内容来自平台其他项目");
        // 留痕落库：项目、时间、未降级、一条命中
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_retrievals WHERE project_id = '9530'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT degraded FROM knw_retrievals WHERE project_id = '9530'", Boolean.class))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(items) FROM knw_retrievals WHERE project_id = '9530'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT items->0->>'sourceProjectName' FROM knw_retrievals WHERE project_id = '9530'",
                String.class)).isEqualTo("宠物医院预约平台");

        // 模拟重启：新实例从该项目最近一次留痕重建同一份块，不重检索
        ProjectKnowledgeAppService restarted =
                new ProjectKnowledgeAppService(knowledgePort, projectQueryAppService, 5);
        assertThat(restarted.sessionTailOf(9530L)).isEqualTo(block);
        // embed 两次＝素材入库一次＋检索一次；重建不再 embed
        verify(embeddingClient, times(2)).embed(anyList());
    }

    @Test
    void given_zero_hit_when_establish_then_recorded_not_degraded() {
        when(embeddingClient.embed(List.of(REQUIREMENT))).thenReturn(List.of(hot(0)));

        assertThat(appService.establishSessionInjection(9531L, REQUIREMENT)).isEmpty();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT degraded FROM knw_retrievals WHERE project_id = '9531'", Boolean.class))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(items) FROM knw_retrievals WHERE project_id = '9531'",
                Integer.class)).isZero();
    }

    @Test
    void given_embedding_unavailable_when_establish_then_recorded_degraded() {
        when(embeddingClient.embed(List.of(REQUIREMENT))).thenReturn(List.of());

        assertThat(appService.establishSessionInjection(9532L, REQUIREMENT)).isEmpty();

        // 降级与「真 0 条」可区分：degraded=true
        assertThat(jdbcTemplate.queryForObject(
                "SELECT degraded FROM knw_retrievals WHERE project_id = '9532'", Boolean.class))
                .isTrue();
    }

    @Test
    void given_records_when_purge_project_then_records_cascaded() {
        when(embeddingClient.embed(List.of(REQUIREMENT))).thenReturn(List.of(hot(0)));
        appService.establishSessionInjection(9533L, REQUIREMENT);
        appService.establishSessionInjection(9534L, REQUIREMENT);

        knowledgePort.purgeByProject("9533");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_retrievals WHERE project_id = '9533'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_retrievals WHERE project_id = '9534'", Integer.class))
                .isEqualTo(1);
    }

    private static float[] hot(int index) {
        float[] vector = new float[DIM];
        vector[index] = 1f;
        return vector;
    }
}
