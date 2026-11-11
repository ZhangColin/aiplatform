package com.semgravio.aiplatform.business.project.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;
import com.semgravio.aiplatform.business.project.domain.model.AgentConfigTrace;
import com.semgravio.aiplatform.business.project.domain.model.AgentOperationalConfig;
import com.semgravio.aiplatform.business.project.domain.repository.AgentConfigStore;

/**
 * 智能体运营配置存取实现（JdbcTemplate 原生 SQL，照 {@code JdbcSkillStore}
 * 先例——平台资产单表读写不挂 JPA 映射面）：覆盖行 upsert（键＝agent_key）＋
 * 变更留痕 append-only。装配读腿 {@link #find} 每轮命令构建时查（动态查库，
 * 配置变更下一轮自然生效——工厂实例缓存键含 sysPrompt，不改缓存机制）。
 */
@Component
public class JdbcAgentConfigStore implements AgentConfigStore {

    private static final String FIND_SQL = """
            SELECT agent_key, system_prompt, model_id, web_search_enabled, fetch_url_enabled,
                   routing_enabled, routing_strong_model_id, routing_file_count_threshold,
                   routing_escalating_size_labels, routing_file_count_signal_enabled,
                   routing_size_label_signal_enabled, verify_model_id, verify_round_budget,
                   final_verify_fix_budget, verification_presence_gate_enabled,
                   proof_of_output_gate_enabled, final_verification_gate_enabled,
                   single_session_generation_enabled, parallel_slice_machine_enabled,
                   decision_hints_enabled, decision_hint_error_streak, decision_hint_stack_cap,
                   decision_hint_disabled, verify_self_decision_enabled,
                   operator_id, operator_name, updated_at
            FROM prj_agent_configs WHERE agent_key = ?
            """;

    /** 整行 upsert：值面全量＋操作者两列；冲突分支全量重写（覆盖态无部分更新语义）。 */
    private static final String SAVE_SQL = """
            INSERT INTO prj_agent_configs
                (agent_key, system_prompt, model_id, web_search_enabled, fetch_url_enabled,
                 routing_enabled, routing_strong_model_id, routing_file_count_threshold,
                 routing_escalating_size_labels, routing_file_count_signal_enabled,
                 routing_size_label_signal_enabled, verify_model_id, verify_round_budget,
                 final_verify_fix_budget, verification_presence_gate_enabled,
                 proof_of_output_gate_enabled, final_verification_gate_enabled,
                 single_session_generation_enabled, parallel_slice_machine_enabled,
                 decision_hints_enabled, decision_hint_error_streak, decision_hint_stack_cap,
                 decision_hint_disabled, verify_self_decision_enabled,
                 operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (agent_key) DO UPDATE SET
                system_prompt = EXCLUDED.system_prompt,
                model_id = EXCLUDED.model_id,
                web_search_enabled = EXCLUDED.web_search_enabled,
                fetch_url_enabled = EXCLUDED.fetch_url_enabled,
                routing_enabled = EXCLUDED.routing_enabled,
                routing_strong_model_id = EXCLUDED.routing_strong_model_id,
                routing_file_count_threshold = EXCLUDED.routing_file_count_threshold,
                routing_escalating_size_labels = EXCLUDED.routing_escalating_size_labels,
                routing_file_count_signal_enabled = EXCLUDED.routing_file_count_signal_enabled,
                routing_size_label_signal_enabled = EXCLUDED.routing_size_label_signal_enabled,
                verify_model_id = EXCLUDED.verify_model_id,
                verify_round_budget = EXCLUDED.verify_round_budget,
                final_verify_fix_budget = EXCLUDED.final_verify_fix_budget,
                verification_presence_gate_enabled = EXCLUDED.verification_presence_gate_enabled,
                proof_of_output_gate_enabled = EXCLUDED.proof_of_output_gate_enabled,
                final_verification_gate_enabled = EXCLUDED.final_verification_gate_enabled,
                single_session_generation_enabled = EXCLUDED.single_session_generation_enabled,
                parallel_slice_machine_enabled = EXCLUDED.parallel_slice_machine_enabled,
                decision_hints_enabled = EXCLUDED.decision_hints_enabled,
                decision_hint_error_streak = EXCLUDED.decision_hint_error_streak,
                decision_hint_stack_cap = EXCLUDED.decision_hint_stack_cap,
                decision_hint_disabled = EXCLUDED.decision_hint_disabled,
                verify_self_decision_enabled = EXCLUDED.verify_self_decision_enabled,
                operator_id = EXCLUDED.operator_id,
                operator_name = EXCLUDED.operator_name,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String INSERT_TRACE_SQL = """
            INSERT INTO prj_agent_config_traces
                (id, agent_key, old_system_prompt, old_model_id, old_web_search_enabled,
                 old_fetch_url_enabled, old_routing_enabled, old_routing_strong_model_id,
                 old_routing_file_count_threshold, old_routing_escalating_size_labels,
                 old_routing_file_count_signal_enabled, old_routing_size_label_signal_enabled,
                 old_verify_model_id, old_verify_round_budget, old_final_verify_fix_budget,
                 old_verification_presence_gate_enabled, old_proof_of_output_gate_enabled,
                 old_final_verification_gate_enabled,
                 old_single_session_generation_enabled, old_parallel_slice_machine_enabled,
                 old_decision_hints_enabled, old_decision_hint_error_streak,
                 old_decision_hint_stack_cap, old_decision_hint_disabled,
                 old_verify_self_decision_enabled,
                 new_system_prompt, new_model_id, new_web_search_enabled,
                 new_fetch_url_enabled, new_routing_enabled, new_routing_strong_model_id,
                 new_routing_file_count_threshold, new_routing_escalating_size_labels,
                 new_routing_file_count_signal_enabled, new_routing_size_label_signal_enabled,
                 new_verify_model_id, new_verify_round_budget, new_final_verify_fix_budget,
                 new_verification_presence_gate_enabled, new_proof_of_output_gate_enabled,
                 new_final_verification_gate_enabled,
                 new_single_session_generation_enabled, new_parallel_slice_machine_enabled,
                 new_decision_hints_enabled, new_decision_hint_error_streak,
                 new_decision_hint_stack_cap, new_decision_hint_disabled,
                 new_verify_self_decision_enabled,
                 operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** 倒序最近先（id 陪排——同秒多痕不乱序）。 */
    private static final String FIND_TRACES_SQL = """
            SELECT id, agent_key, old_system_prompt, old_model_id, old_web_search_enabled,
                   old_fetch_url_enabled, old_routing_enabled, old_routing_strong_model_id,
                   old_routing_file_count_threshold, old_routing_escalating_size_labels,
                   old_routing_file_count_signal_enabled, old_routing_size_label_signal_enabled,
                   old_verify_model_id, old_verify_round_budget, old_final_verify_fix_budget,
                   old_verification_presence_gate_enabled, old_proof_of_output_gate_enabled,
                   old_final_verification_gate_enabled,
                   old_single_session_generation_enabled, old_parallel_slice_machine_enabled,
                   old_decision_hints_enabled, old_decision_hint_error_streak,
                   old_decision_hint_stack_cap, old_decision_hint_disabled,
                   old_verify_self_decision_enabled,
                   new_system_prompt, new_model_id,
                   new_web_search_enabled, new_fetch_url_enabled, new_routing_enabled,
                   new_routing_strong_model_id, new_routing_file_count_threshold,
                   new_routing_escalating_size_labels, new_routing_file_count_signal_enabled,
                   new_routing_size_label_signal_enabled, new_verify_model_id,
                   new_verify_round_budget, new_final_verify_fix_budget,
                   new_verification_presence_gate_enabled, new_proof_of_output_gate_enabled,
                   new_final_verification_gate_enabled,
                   new_single_session_generation_enabled, new_parallel_slice_machine_enabled,
                   new_decision_hints_enabled, new_decision_hint_error_streak,
                   new_decision_hint_stack_cap, new_decision_hint_disabled,
                   new_verify_self_decision_enabled,
                   operator_id, operator_name,
                   created_at
            FROM prj_agent_config_traces WHERE agent_key = ?
            ORDER BY created_at DESC, id DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentConfigStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AgentOperationalConfig find(String agentKey) {
        List<AgentOperationalConfig> rows = jdbcTemplate.query(
                FIND_SQL, JdbcAgentConfigStore::mapConfig, agentKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void save(AgentOperationalConfig config) {
        jdbcTemplate.update(SAVE_SQL, config.agentKey(), config.systemPrompt(),
                config.modelId(), config.webSearchEnabled(), config.fetchUrlEnabled(),
                config.routingEnabled(), config.routingStrongModelId(),
                config.routingFileCountThreshold(),
                SliceSizeLabel.tokensOf(config.routingEscalatingSizeLabels()),
                config.routingFileCountSignalEnabled(), config.routingSizeLabelSignalEnabled(),
                config.verifyModelId(), config.verifyRoundBudget(), config.finalVerifyFixBudget(),
                config.verificationPresenceGateEnabled(), config.proofOfOutputGateEnabled(),
                config.finalVerificationGateEnabled(),
                config.singleSessionGenerationEnabled(), config.parallelSliceMachineEnabled(),
                config.decisionHintsEnabled(), config.decisionHintErrorStreak(),
                config.decisionHintStackCap(), config.decisionHintDisabled(),
                config.verifySelfDecisionEnabled(),
                config.operatorId(), config.operatorName());
    }

    @Override
    public void insertTrace(AgentConfigTrace trace) {
        jdbcTemplate.update(INSERT_TRACE_SQL, trace.id(), trace.agentKey(),
                trace.oldSystemPrompt(), trace.oldModelId(), trace.oldWebSearchEnabled(),
                trace.oldFetchUrlEnabled(), trace.oldRoutingEnabled(),
                trace.oldRoutingStrongModelId(), trace.oldRoutingFileCountThreshold(),
                trace.oldRoutingEscalatingSizeLabels(),
                trace.oldRoutingFileCountSignalEnabled(), trace.oldRoutingSizeLabelSignalEnabled(),
                trace.oldVerifyModelId(),
                trace.oldVerifyRoundBudget(), trace.oldFinalVerifyFixBudget(),
                trace.oldVerificationPresenceGateEnabled(), trace.oldProofOfOutputGateEnabled(),
                trace.oldFinalVerificationGateEnabled(),
                trace.oldSingleSessionGenerationEnabled(), trace.oldParallelSliceMachineEnabled(),
                trace.oldDecisionHintsEnabled(), trace.oldDecisionHintErrorStreak(),
                trace.oldDecisionHintStackCap(), trace.oldDecisionHintDisabled(),
                trace.oldVerifySelfDecisionEnabled(),
                trace.newSystemPrompt(), trace.newModelId(),
                trace.newWebSearchEnabled(), trace.newFetchUrlEnabled(),
                trace.newRoutingEnabled(), trace.newRoutingStrongModelId(),
                trace.newRoutingFileCountThreshold(), trace.newRoutingEscalatingSizeLabels(),
                trace.newRoutingFileCountSignalEnabled(), trace.newRoutingSizeLabelSignalEnabled(),
                trace.newVerifyModelId(), trace.newVerifyRoundBudget(),
                trace.newFinalVerifyFixBudget(),
                trace.newVerificationPresenceGateEnabled(), trace.newProofOfOutputGateEnabled(),
                trace.newFinalVerificationGateEnabled(),
                trace.newSingleSessionGenerationEnabled(), trace.newParallelSliceMachineEnabled(),
                trace.newDecisionHintsEnabled(), trace.newDecisionHintErrorStreak(),
                trace.newDecisionHintStackCap(), trace.newDecisionHintDisabled(),
                trace.newVerifySelfDecisionEnabled(),
                trace.operatorId(), trace.operatorName());
    }

    @Override
    public List<AgentConfigTrace> findTraces(String agentKey) {
        return jdbcTemplate.query(FIND_TRACES_SQL, JdbcAgentConfigStore::mapTrace, agentKey);
    }

    private static AgentOperationalConfig mapConfig(ResultSet rs, int rowNum) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new AgentOperationalConfig(rs.getString("agent_key"),
                rs.getString("system_prompt"), rs.getString("model_id"),
                rs.getBoolean("web_search_enabled"), rs.getBoolean("fetch_url_enabled"),
                rs.getBoolean("routing_enabled"), rs.getString("routing_strong_model_id"),
                integerOrNull(rs, "routing_file_count_threshold"),
                SliceSizeLabel.labelsOf(rs.getString("routing_escalating_size_labels")),
                rs.getBoolean("routing_file_count_signal_enabled"),
                rs.getBoolean("routing_size_label_signal_enabled"),
                rs.getString("verify_model_id"), integerOrNull(rs, "verify_round_budget"),
                integerOrNull(rs, "final_verify_fix_budget"),
                rs.getBoolean("verification_presence_gate_enabled"),
                rs.getBoolean("proof_of_output_gate_enabled"),
                rs.getBoolean("final_verification_gate_enabled"),
                rs.getBoolean("single_session_generation_enabled"),
                rs.getBoolean("parallel_slice_machine_enabled"),
                rs.getBoolean("decision_hints_enabled"),
                integerOrNull(rs, "decision_hint_error_streak"),
                integerOrNull(rs, "decision_hint_stack_cap"),
                rs.getString("decision_hint_disabled"),
                rs.getBoolean("verify_self_decision_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }

    private static AgentConfigTrace mapTrace(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AgentConfigTrace(rs.getLong("id"), rs.getString("agent_key"),
                rs.getString("old_system_prompt"), rs.getString("old_model_id"),
                rs.getBoolean("old_web_search_enabled"), rs.getBoolean("old_fetch_url_enabled"),
                rs.getBoolean("old_routing_enabled"), rs.getString("old_routing_strong_model_id"),
                integerOrNull(rs, "old_routing_file_count_threshold"),
                rs.getString("old_routing_escalating_size_labels"),
                rs.getBoolean("old_routing_file_count_signal_enabled"),
                rs.getBoolean("old_routing_size_label_signal_enabled"),
                rs.getString("old_verify_model_id"), integerOrNull(rs, "old_verify_round_budget"),
                integerOrNull(rs, "old_final_verify_fix_budget"),
                rs.getBoolean("old_verification_presence_gate_enabled"),
                rs.getBoolean("old_proof_of_output_gate_enabled"),
                rs.getBoolean("old_final_verification_gate_enabled"),
                rs.getBoolean("old_single_session_generation_enabled"),
                rs.getBoolean("old_parallel_slice_machine_enabled"),
                rs.getBoolean("old_decision_hints_enabled"),
                integerOrNull(rs, "old_decision_hint_error_streak"),
                integerOrNull(rs, "old_decision_hint_stack_cap"),
                rs.getString("old_decision_hint_disabled"),
                rs.getBoolean("old_verify_self_decision_enabled"),
                rs.getString("new_system_prompt"), rs.getString("new_model_id"),
                rs.getBoolean("new_web_search_enabled"), rs.getBoolean("new_fetch_url_enabled"),
                rs.getBoolean("new_routing_enabled"), rs.getString("new_routing_strong_model_id"),
                integerOrNull(rs, "new_routing_file_count_threshold"),
                rs.getString("new_routing_escalating_size_labels"),
                rs.getBoolean("new_routing_file_count_signal_enabled"),
                rs.getBoolean("new_routing_size_label_signal_enabled"),
                rs.getString("new_verify_model_id"), integerOrNull(rs, "new_verify_round_budget"),
                integerOrNull(rs, "new_final_verify_fix_budget"),
                rs.getBoolean("new_verification_presence_gate_enabled"),
                rs.getBoolean("new_proof_of_output_gate_enabled"),
                rs.getBoolean("new_final_verification_gate_enabled"),
                rs.getBoolean("new_single_session_generation_enabled"),
                rs.getBoolean("new_parallel_slice_machine_enabled"),
                rs.getBoolean("new_decision_hints_enabled"),
                integerOrNull(rs, "new_decision_hint_error_streak"),
                integerOrNull(rs, "new_decision_hint_stack_cap"),
                rs.getString("new_decision_hint_disabled"),
                rs.getBoolean("new_verify_self_decision_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }

    /** 可空整型列读（getInt 遇 NULL 回 0——null 列须显式判空，JDBC 老坑）。 */
    private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
