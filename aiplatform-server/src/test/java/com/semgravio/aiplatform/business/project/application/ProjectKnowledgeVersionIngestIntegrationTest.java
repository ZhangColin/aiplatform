package com.semgravio.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
import com.semgravio.aiplatform.base.knowledge.domain.port.EmbeddingClient;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 成版需求文档入库端到端（#640，ADR-0058）：真知识底座（{@link KnowledgePort} →
 * 登记表/块表），只把工作区 PRD 读口与 embedding 端口 mock 掉——钉死两条验收行为：
 * ①同一项目多次成版只留最新一份（幂等键 PRD＋项目标识，旧块被替换）；
 * ②被运营停用的该项目需求文档在新版本入库后仍为停用（登记表状态跨替换存活）。
 */
@IntegrationTest
class ProjectKnowledgeVersionIngestIntegrationTest {

    private static final long PROJECT_ID = 9710L;
    private static final String OPERATOR_ID = "700100";
    private static final String OPERATOR_NAME = "运营·小刘";

    @Autowired
    private KnowledgePort knowledgePort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 工作区 PRD 读口（成版入库直读最新 docs/PRD.md）。 */
    @MockitoBean
    private ProjectQueryAppService projectQueryAppService;

    /** embedding 端口：确定性伪向量（本机 fastembed 不属测试依赖）。 */
    @MockitoBean
    private EmbeddingClient embeddingClient;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM knw_chunks WHERE project_id = ?",
                Long.toString(PROJECT_ID));
        jdbcTemplate.update("DELETE FROM knw_materials WHERE project_id = ?",
                Long.toString(PROJECT_ID));
    }

    @Test
    void given_deactivated_material_when_new_version_ingests_then_status_survives_and_latest_kept() {
        stubProject("宠物店官网");
        stubEmbeddingOk();
        ProjectKnowledgeAppService service = directService();
        when(projectQueryAppService.currentPrdContent(PROJECT_ID)).thenReturn("# PRD\n\n第一版需求");
        service.ingestPrdOnVersionAsync(PROJECT_ID);
        knowledgePort.disable("PRD", Long.toString(PROJECT_ID),
                new Operator(OPERATOR_ID, OPERATOR_NAME));

        when(projectQueryAppService.currentPrdContent(PROJECT_ID)).thenReturn("# PRD\n\n第二版需求");
        service.ingestPrdOnVersionAsync(PROJECT_ID);

        // 同一项目只有一份素材；块被最新内容整体替换（旧块不在）
        assertThat(registryCount()).isEqualTo(1);
        String chunks = String.join("\n", jdbcTemplate.queryForList(
                "SELECT chunk FROM knw_chunks WHERE project_id = ? AND kind = 'PRD'",
                String.class, Long.toString(PROJECT_ID)));
        assertThat(chunks).contains("第二版需求").doesNotContain("第一版需求");
        // 停用状态跨重新入库存活：新版本入库不复活素材
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM knw_materials", Integer.class))
                .isEqualTo(MaterialStatus.DISABLED.getCode());
    }

    @Test
    void given_new_version_when_ingest_then_material_enabled_and_findable_by_project_name() {
        stubProject("宠物店官网");
        stubEmbeddingOk();
        when(projectQueryAppService.currentPrdContent(PROJECT_ID)).thenReturn("# PRD\n\n全新需求");

        directService().ingestPrdOnVersionAsync(PROJECT_ID);

        assertThat(registryCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM knw_materials", Integer.class))
                .isEqualTo(MaterialStatus.ENABLED.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT project_name FROM knw_materials", String.class)).isEqualTo("宠物店官网");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_chunks WHERE project_id = ? AND kind = 'PRD'",
                Integer.class, Long.toString(PROJECT_ID))).isPositive();
    }

    /** 直通执行器：成版入库提交即同步执行，断言落库事实。 */
    private ProjectKnowledgeAppService directService() {
        return new ProjectKnowledgeAppService(knowledgePort, projectQueryAppService, 5,
                Runnable::run);
    }

    private void stubProject(String name) {
        when(projectQueryAppService.namesOf(List.of(PROJECT_ID)))
                .thenReturn(Map.of(PROJECT_ID, name));
    }

    private void stubEmbeddingOk() {
        when(embeddingClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> chunks = invocation.getArgument(0);
            return chunks.stream().map(chunk -> {
                float[] vector = new float[512];
                vector[0] = chunk.hashCode() % 97 / 97f;
                return vector;
            }).toList();
        });
    }

    private int registryCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knw_materials WHERE project_id = ?", Integer.class,
                Long.toString(PROJECT_ID));
    }
}
