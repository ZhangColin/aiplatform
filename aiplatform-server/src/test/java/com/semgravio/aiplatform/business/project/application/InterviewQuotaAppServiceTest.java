package com.semgravio.aiplatform.business.project.application;

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
import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.billing.application.PoolTopupAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.project.application.dto.response.InterviewQuotaResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.ConversationEntry;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ConversationEntryRepository;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 五问额度读面（#538 访谈①，ADR-0048）：「第 N/5」进度 chip 的服务端单源——试用态
 * 访谈期返回 {已答缺口数, 5}；付费态 / PRD 已产出 / 已生成（非访谈期）返回 null
 * （chip 不呈现）。前端不自算，收口判定（{@code closureRequired}）与 chip 同读本口。
 */
@IntegrationTest
class InterviewQuotaAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private InterviewQuotaAppService quotaAppService;

    @Autowired
    private ConversationHistoryAppService conversationHistory;

    @Autowired
    private ConversationEntryRepository entries;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private PoolTopupAppService poolTopupAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
        jdbcTemplate.update("DELETE FROM prj_projects");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
        jdbcTemplate.update("DELETE FROM bil_entries");
    }

    @Test
    void given_trial_interview_with_answered_gaps_when_quota_then_answered_over_five() {
        Long projectId = persistedInterviewProject("9601");
        saveAnsweredCard(projectId, "run-1", "目标用户是谁？");
        saveAnsweredCard(projectId, "run-2", "目标用户是谁？"); // 同缺口重派不另计
        saveAnsweredCard(projectId, "run-3", "核心流程走哪条？");
        savePendingCard(projectId, "run-4", "预算多少？"); // 未答不计

        InterviewQuotaResponse quota = quotaAppService.quotaOf(projectId);

        assertThat(quota).isNotNull();
        assertThat(quota.answered()).isEqualTo(2);
        assertThat(quota.total()).isEqualTo(5);
    }

    @Test
    void given_trial_answered_full_when_closure_required_then_true() {
        Long projectId = persistedInterviewProject("9602");
        for (int i = 1; i <= 5; i++) {
            saveAnsweredCard(projectId, "run-" + i, "问题" + i);
        }

        assertThat(quotaAppService.quotaOf(projectId).answered()).isEqualTo(5);
        assertThat(quotaAppService.closureRequired(projectId)).isTrue();
    }

    @Test
    void given_paid_project_when_quota_then_null() {
        // 付费翻转后五问额度解除（无上限）——读面缺席即 chip 不呈现
        Long projectId = persistedInterviewProject("9603");
        saveAnsweredCard(projectId, "run-1", "目标用户是谁？");
        poolTopupAppService.topup(null, projectId, 1, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT);

        assertThat(quotaAppService.quotaOf(projectId)).isNull();
        assertThat(quotaAppService.closureRequired(projectId)).isFalse();
    }

    @Test
    void given_prd_produced_when_quota_then_null() {
        // PRD 产出即访谈收口——额度面退场（chip 不再呈现）
        Long projectId = persistedInterviewProject("9604");
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        projectRepository.save(project);
        saveAnsweredCard(projectId, "run-1", "目标用户是谁？");

        assertThat(quotaAppService.quotaOf(projectId)).isNull();
        assertThat(quotaAppService.closureRequired(projectId)).isFalse();
    }

    @Test
    void given_generated_project_when_quota_then_null() {
        // 已生成＝迭代期：五问额度只约束访谈期
        Long projectId = persistedInterviewProject("9605");
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markPrdProduced();
        project.markGenerated();
        projectRepository.save(project);

        assertThat(quotaAppService.quotaOf(projectId)).isNull();
    }

    @Test
    void given_trial_unfilled_without_convergence_when_early_closure_then_blocked_reason() {
        // 五问承诺（2026-11-17 用户裁决）：未答满五问且无收敛令——savePrd 被拒，
        // 拒因模型可见、点名已答进度并指引继续 ask_user
        Long projectId = persistedInterviewProject("9607");
        saveAnsweredCard(projectId, "run-1", "目标用户是谁？");
        saveAnsweredCard(projectId, "run-2", "核心流程走哪条？");

        String reason = quotaAppService.earlyClosureBlockReason(projectId, false);

        assertThat(reason).isNotNull();
        assertThat(reason).contains("平台拒绝").contains("2/5").contains("ask_user")
                .contains("收敛令");
    }

    @Test
    void given_trial_unfilled_with_convergence_command_when_early_closure_then_admitted() {
        // 用户明确下收敛令（fastLane attest）——未答满也放行（用户明确不要的例外）
        Long projectId = persistedInterviewProject("9608");
        saveAnsweredCard(projectId, "run-1", "目标用户是谁？");

        assertThat(quotaAppService.earlyClosureBlockReason(projectId, true)).isNull();
    }

    @Test
    void given_trial_answered_full_when_early_closure_then_admitted_without_flag() {
        // 问满五问的自然收口：fastLane 不传也放行
        Long projectId = persistedInterviewProject("9609");
        for (int i = 1; i <= 5; i++) {
            saveAnsweredCard(projectId, "run-" + i, "问题" + i);
        }

        assertThat(quotaAppService.earlyClosureBlockReason(projectId, false)).isNull();
    }

    @Test
    void given_paid_or_prd_produced_when_early_closure_then_admitted() {
        // 非访谈期（付费翻转 / PRD 已产出）闸不拦——零必答首轮直产是付费态合法快道
        Long paidId = persistedInterviewProject("9610");
        saveAnsweredCard(paidId, "run-1", "目标用户是谁？");
        poolTopupAppService.topup(null, paidId, 1, null, null);
        billingAppService.flipToPaid(paidId, FlipGate.INTERCEPT);

        assertThat(quotaAppService.earlyClosureBlockReason(paidId, false)).isNull();

        Long prdId = persistedInterviewProject("9611");
        Project prdProject = projectRepository.findById(prdId).orElseThrow();
        prdProject.markPrdProduced();
        projectRepository.save(prdProject);

        assertThat(quotaAppService.earlyClosureBlockReason(prdId, false)).isNull();
    }

    @Test
    void given_trial_answered_full_when_ask_block_reason_then_blocked_with_closure_directive() {
        // 提问侧额度闸（收口闸对偶）：答满五问后 ask_user 被拒——拒因点名 5/5、
        // 指引停止提问并 savePrd（工具点拦漂移，不出第 6 张问答卡）
        Long projectId = persistedInterviewProject("9612");
        for (int i = 1; i <= 5; i++) {
            saveAnsweredCard(projectId, "run-" + i, "问题" + i);
        }

        String reason = quotaAppService.exhaustedBlockReason(projectId);

        assertThat(reason).isNotNull();
        assertThat(reason).contains("平台拒绝").contains("5/5")
                .contains("savePrd").doesNotContain("6/5");
    }

    @Test
    void given_trial_unfilled_when_ask_block_reason_then_null() {
        // 未答满：提问照常放行（恒 ASK 挂起）
        Long projectId = persistedInterviewProject("9613");
        saveAnsweredCard(projectId, "run-1", "目标用户是谁？");

        assertThat(quotaAppService.exhaustedBlockReason(projectId)).isNull();
    }

    @Test
    void given_paid_or_prd_produced_when_ask_block_reason_then_null() {
        // 付费翻转 / PRD 已产出（非访谈期）：提问侧不拦——付费轨无提问上限
        Long paidId = persistedInterviewProject("9614");
        saveAnsweredCard(paidId, "run-1", "目标用户是谁？");
        poolTopupAppService.topup(null, paidId, 1, null, null);
        billingAppService.flipToPaid(paidId, FlipGate.INTERCEPT);

        assertThat(quotaAppService.exhaustedBlockReason(paidId)).isNull();

        Long prdId = persistedInterviewProject("9615");
        for (int i = 1; i <= 5; i++) {
            saveAnsweredCard(prdId, "run-p" + i, "问题" + i);
        }
        Project prdProject = projectRepository.findById(prdId).orElseThrow();
        prdProject.markPrdProduced();
        projectRepository.save(prdProject);

        assertThat(quotaAppService.exhaustedBlockReason(prdId)).isNull();
    }

    @Test
    void given_no_question_cards_when_quota_then_answered_zero() {
        Long projectId = persistedInterviewProject("9606");

        InterviewQuotaResponse quota = quotaAppService.quotaOf(projectId);

        assertThat(quota).isNotNull();
        assertThat(quota.answered()).isZero();
        assertThat(quota.total()).isEqualTo(5);
    }

    private Long persistedInterviewProject(String workspaceId) {
        Project project = projectRepository.save(Project.create("访谈项目", null, null,
                Long.parseLong(workspaceId), OWNER, null));
        return project.getId();
    }

    private void saveAnsweredCard(Long projectId, String runId, String question) {
        entries.save(ConversationEntry.answer(projectId, runId, "种子答复")); // 用户已答语义
        ConversationEntry card = ConversationEntry.question(projectId, runId,
                questionPayload(runId, question));
        card.markAnswered();
        entries.save(card);
    }

    private void savePendingCard(Long projectId, String runId, String question) {
        entries.save(ConversationEntry.question(projectId, runId, questionPayload(runId, question)));
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
