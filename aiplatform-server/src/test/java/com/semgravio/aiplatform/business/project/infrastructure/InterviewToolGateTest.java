package com.semgravio.aiplatform.business.project.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;
import com.semgravio.aiplatform.business.project.domain.aggregate.ConversationEntry;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ConversationEntryRepository;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * {@link InterviewToolGate}：workspaceId → projectId 短事务解析唯一缝，提问/产出
 * 两闸判定同读 {@code InterviewQuotaAppService} 单源（ADR-0048 程序计数权威）；
 * 工作区无项目（防御面）一律不拦。
 */
@IntegrationTest
class InterviewToolGateTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private InterviewToolGate gate;

    @Autowired
    private ConversationEntryRepository entries;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_unknown_workspace_when_question_gate_then_null_both_seams() {
        // 工作区无项目（本地工作区/置备竞态防御面）：两缝均放行不拦
        assertThat(gate.questionBlockReason("999999")).isNull();
        assertThat(gate.earlyClosureBlockReason("999999", false)).isNull();
    }

    @Test
    void given_trial_answered_full_when_question_gate_then_blocked_reason() {
        // 提问侧闸：答满五问 → ask_user 拒因（工具点拦漂移、不出第 6 张卡）
        Long projectId = persistedProject("9701");
        for (int i = 1; i <= 5; i++) {
            saveAnsweredCard(projectId, "run-" + i, "问题" + i);
        }

        String reason = gate.questionBlockReason("9701");

        assertThat(reason).isNotNull();
        assertThat(reason).contains("平台拒绝").contains("5/5").contains("savePrd");
    }

    @Test
    void given_trial_unfilled_when_question_gate_then_null_and_early_closure_blocked() {
        // 同一项目未答满：提问放行、提前产出被拒（两闸对偶各管一边）
        Long projectId = persistedProject("9702");
        saveAnsweredCard(projectId, "run-1", "目标用户是谁？");

        assertThat(gate.questionBlockReason("9702")).isNull();
        String closure = gate.earlyClosureBlockReason("9702", false);
        assertThat(closure).isNotNull().contains("1/5");
    }

    @Test
    void given_trial_answered_full_when_early_closure_without_flag_then_admitted() {
        // 问满后产出侧自然放行（fastLane 不传）
        Long projectId = persistedProject("9703");
        for (int i = 1; i <= 5; i++) {
            saveAnsweredCard(projectId, "run-" + i, "问题" + i);
        }

        assertThat(gate.earlyClosureBlockReason("9703", false)).isNull();
    }

    // ---------- 内部 ----------

    private Long persistedProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("闸测试项目", null, null,
                Long.parseLong(workspaceId), OWNER, null));
        return project.getId();
    }

    private void saveAnsweredCard(Long projectId, String runId, String question) {
        entries.save(ConversationEntry.answer(projectId, runId, "种子答复"));
        ConversationEntry card = ConversationEntry.question(projectId, runId,
                questionPayload(runId, question));
        card.markAnswered();
        entries.save(card);
    }

    private Map<String, Object> questionPayload(String runId, String question) {
        return new LinkedHashMap<>(Map.of(
                AgentEventTypes.RUN_FIELD, runId,
                AgentEventTypes.WAIT_ENGINE_REF_FIELD, "reply-" + runId,
                AgentEventTypes.WAIT_DATA_FIELD, Map.of(
                        "toolCalls", List.of(Map.of("id", "tc-1", "name", "ask_user")),
                        "questions", List.of(Map.of(
                                "header", "提问", "question", question,
                                "multiple", false, "custom", true, "options", List.of())))));
    }
}
