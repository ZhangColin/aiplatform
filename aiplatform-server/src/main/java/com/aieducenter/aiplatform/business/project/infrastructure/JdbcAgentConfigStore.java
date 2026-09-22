package com.aieducenter.aiplatform.business.project.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.business.project.domain.model.AgentConfigTrace;
import com.aieducenter.aiplatform.business.project.domain.model.AgentOperationalConfig;
import com.aieducenter.aiplatform.business.project.domain.repository.AgentConfigStore;

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
                   operator_id, operator_name, updated_at
            FROM prj_agent_configs WHERE agent_key = ?
            """;

    /** 整行 upsert：值面四件＋操作者两列；冲突分支全量重写（覆盖态无部分更新语义）。 */
    private static final String SAVE_SQL = """
            INSERT INTO prj_agent_configs
                (agent_key, system_prompt, model_id, web_search_enabled, fetch_url_enabled,
                 operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (agent_key) DO UPDATE SET
                system_prompt = EXCLUDED.system_prompt,
                model_id = EXCLUDED.model_id,
                web_search_enabled = EXCLUDED.web_search_enabled,
                fetch_url_enabled = EXCLUDED.fetch_url_enabled,
                operator_id = EXCLUDED.operator_id,
                operator_name = EXCLUDED.operator_name,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String INSERT_TRACE_SQL = """
            INSERT INTO prj_agent_config_traces
                (id, agent_key, old_system_prompt, old_model_id, old_web_search_enabled,
                 old_fetch_url_enabled, new_system_prompt, new_model_id, new_web_search_enabled,
                 new_fetch_url_enabled, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** 倒序最近先（id 陪排——同秒多痕不乱序）。 */
    private static final String FIND_TRACES_SQL = """
            SELECT id, agent_key, old_system_prompt, old_model_id, old_web_search_enabled,
                   old_fetch_url_enabled, new_system_prompt, new_model_id,
                   new_web_search_enabled, new_fetch_url_enabled, operator_id, operator_name,
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
                config.operatorId(), config.operatorName());
    }

    @Override
    public void insertTrace(AgentConfigTrace trace) {
        jdbcTemplate.update(INSERT_TRACE_SQL, trace.id(), trace.agentKey(),
                trace.oldSystemPrompt(), trace.oldModelId(), trace.oldWebSearchEnabled(),
                trace.oldFetchUrlEnabled(), trace.newSystemPrompt(), trace.newModelId(),
                trace.newWebSearchEnabled(), trace.newFetchUrlEnabled(),
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
                rs.getString("operator_id"), rs.getString("operator_name"),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }

    private static AgentConfigTrace mapTrace(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AgentConfigTrace(rs.getLong("id"), rs.getString("agent_key"),
                rs.getString("old_system_prompt"), rs.getString("old_model_id"),
                rs.getBoolean("old_web_search_enabled"), rs.getBoolean("old_fetch_url_enabled"),
                rs.getString("new_system_prompt"), rs.getString("new_model_id"),
                rs.getBoolean("new_web_search_enabled"), rs.getBoolean("new_fetch_url_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }
}
