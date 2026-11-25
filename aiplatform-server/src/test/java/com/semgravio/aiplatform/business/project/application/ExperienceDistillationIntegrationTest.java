package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.ModelCandidate;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.ModelChannel;
import com.semgravio.aiplatform.base.knowledge.application.ExperienceDistillationLedgerAppService;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.model.AgentEdgePoint;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersion;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 事后提炼落库端到端（#641，ADR-0058）：真知识底座（提炼处理记录表 + 沉淀草稿表），
 * 只把版本读口、需求文档读口与模型调用 mock 掉——钉死四条行为：①候选落待审行带
 * 来源五件与扫描回执、处理记录带计数与批次；②同一版本重复触发幂等（不重复写草稿）；
 * ③失败只落失败记录、零草稿；④无修改意见只落记录、零草稿。
 */
@IntegrationTest
class ExperienceDistillationIntegrationTest {

    private static final long PROJECT_ID = 9641L;
    private static final String PROJECT_NAME = "宠物店官网";
    private static final String HASH_1 = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";
    private static final String HASH_2 = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2";

    @Autowired
    private ExperienceDistillationLedgerAppService ledger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final WorkspaceVersionLogReader versionLogReader = mock(WorkspaceVersionLogReader.class);
    private final ProjectQueryAppService projectQueryAppService = mock(ProjectQueryAppService.class);
    private final AgentConfigAppService agentConfigs = mock(AgentConfigAppService.class);
    private final ModelConfigAppService modelConfigs = mock(ModelConfigAppService.class);
    private final AgentscopeAgentClient agentClient = mock(AgentscopeAgentClient.class);

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM knw_sediment_drafts WHERE project_id = ?", PROJECT_ID);
        jdbcTemplate.update("DELETE FROM knw_experience_distillations WHERE project_id = ?",
                PROJECT_ID);
    }

    @Test
    void given_candidates_when_trigger_then_record_and_pending_drafts_with_source_fields() {
        stubWindow();
        when(agentClient.converse(any(), any())).thenReturn(new AgentReply("run-d", """
                {"candidates":[
                  {"kind":"MODULE","title":"可复用导出","summary":"导出形态","content":"导出模块正文","sourceVersion":2,"sourceSentence":"加导出功能"}
                ],"opinions":[
                  {"sourceVersion":2,"sourceSentence":"加导出功能","missed":true,"reason":"PRD 已写导出"}
                ]}
                """));

        service().triggerOnVersionAsync(PROJECT_ID, 2);

        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT * FROM knw_experience_distillations WHERE project_id = ?", PROJECT_ID);
        assertThat(((Number) record.get("version_no")).intValue()).isEqualTo(2);
        assertThat(record.get("commit_hash")).isEqualTo(HASH_2);
        assertThat(record.get("project_name")).isEqualTo(PROJECT_NAME);
        assertThat(((Number) record.get("status")).intValue()).isEqualTo(1);
        assertThat(((Number) record.get("opinion_count")).intValue()).isEqualTo(1);
        assertThat(((Number) record.get("missed_count")).intValue()).isEqualTo(1);
        assertThat(((Number) record.get("candidate_count")).intValue()).isEqualTo(1);
        assertThat(record.get("batch_id")).isNotNull();

        Map<String, Object> draft = jdbcTemplate.queryForMap(
                "SELECT * FROM knw_sediment_drafts WHERE project_id = ?", PROJECT_ID);
        assertThat(draft.get("kind")).isEqualTo("MODULE");
        assertThat(draft.get("title")).isEqualTo("可复用导出");
        assertThat(((Number) draft.get("status")).intValue()).isEqualTo(1); // 待审
        assertThat(((Number) draft.get("inflight_slot")).intValue()).isEqualTo(1);
        assertThat(draft.get("slot")).isEqualTo(AgentEdgePoint.REFINEMENT.key());
        assertThat(draft.get("source_project_name")).isEqualTo(PROJECT_NAME);
        assertThat(((Number) draft.get("source_version_no")).intValue()).isEqualTo(2);
        assertThat(draft.get("source_commit_hash")).isEqualTo(HASH_2);
        assertThat(draft.get("source_sentence")).isEqualTo("加导出功能");
        assertThat(draft.get("distill_batch_id")).isEqualTo(record.get("batch_id"));
        assertThat(draft.get("scan_verdict")).isIn("SAFE", "CAUTION");
    }

    @Test
    void given_repeated_trigger_same_version_when_trigger_then_single_record_one_draft_set() {
        stubWindow();
        when(agentClient.converse(any(), any())).thenReturn(new AgentReply("run-d", """
                {"candidates":[
                  {"kind":"RULE","title":"规则","summary":"s","content":"c","sourceVersion":2,"sourceSentence":"加导出功能"}
                ],"opinions":[]}
                """));

        service().triggerOnVersionAsync(PROJECT_ID, 2);
        service().triggerOnVersionAsync(PROJECT_ID, 2);

        assertThat(count("knw_experience_distillations")).isEqualTo(1);
        assertThat(count("knw_sediment_drafts")).isEqualTo(1);
    }

    @Test
    void given_model_failure_when_trigger_then_failed_record_and_no_draft() {
        stubWindow();
        when(agentClient.converse(any(), any()))
                .thenThrow(new IllegalStateException("模型超时"));

        service().triggerOnVersionAsync(PROJECT_ID, 2);

        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT * FROM knw_experience_distillations WHERE project_id = ?", PROJECT_ID);
        assertThat(((Number) record.get("status")).intValue()).isEqualTo(3); // 失败
        assertThat(record.get("error_summary")).asString().contains("模型超时");
        assertThat(count("knw_sediment_drafts")).isZero();
    }

    @Test
    void given_no_opinion_version_when_trigger_then_record_only_no_draft() {
        Project project = project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(versionLogReader.read(project))
                .thenReturn(List.of(version(HASH_1, 1, "做一个预约系统")));

        service().triggerOnVersionAsync(PROJECT_ID, 1);

        Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT * FROM knw_experience_distillations WHERE project_id = ?", PROJECT_ID);
        assertThat(((Number) record.get("status")).intValue()).isEqualTo(2); // 无修改意见
        assertThat(count("knw_sediment_drafts")).isZero();
    }

    // ---------- 测试数据 ----------

    private ExperienceDistillationAppService service() {
        return new ExperienceDistillationAppService(projectRepository, versionLogReader,
                projectQueryAppService, agentConfigs, modelConfigs, agentClient, ledger,
                Runnable::run);
    }

    private void stubWindow() {
        Project project = project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(versionLogReader.read(project)).thenReturn(List.of(
                version(HASH_2, 2, "加导出功能"), version(HASH_1, 1, "做一个预约系统")));
        when(agentConfigs.effectiveEdgeModelConfigKey(AgentEdgePoint.REFINEMENT))
                .thenReturn("flash");
        when(modelConfigs.resolveQueue("flash")).thenReturn(new ModelCandidateQueue(
                "flash", List.of(new ModelCandidate("deepseek", "m",
                        new ModelChannel("https://ark.example/api/v3", "sk-test", false)))));
    }

    private Project project() {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(PROJECT_ID);
        when(project.getName()).thenReturn(PROJECT_NAME);
        return project;
    }

    private static WorkspaceVersion version(String hash, int versionNo, String subject) {
        return new WorkspaceVersion(hash, 1_700_000_000L + versionNo, subject, versionNo,
                "run-" + versionNo, null);
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE project_id = ?", Integer.class,
                PROJECT_ID);
        return count == null ? 0 : count;
    }
}
