package com.semgravio.aiplatform.business.project.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.business.project.domain.model.ModelProvider;
import com.semgravio.aiplatform.business.project.domain.model.ModelProviderTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfig;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidate;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigCandidateTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigSwitchTrace;
import com.semgravio.aiplatform.business.project.domain.model.ModelConfigTrace;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;

/**
 * 模型供应商通道/配置存取实现（JdbcTemplate 原生 SQL，照 {@code JdbcAgentConfigStore}
 * 先例）：装配读腿（{@link #findConfig}/{@link #findProvider}）每轮命令构建时查；
 * 写腿（后台维护面 CRUD＋留痕）整行 upsert＋append-only。留痕读面不带 apiKey
 * 掩码——掩码是应用层读面职责（{@code ModelProvider#maskApiKey} 单点），存取层
 * 如实搬运（明文与行本体同保护级）。
 */
@Component
public class JdbcModelChannelStore implements ModelChannelStore {

    private static final String FIND_CONFIG_SQL = """
            SELECT config_key, provider_key, model_name, display_name, enabled,
                   thinking_enabled, operator_id, operator_name, updated_at
            FROM prj_model_configs WHERE config_key = ?
            """;

    private static final String FIND_PROVIDER_SQL = """
            SELECT provider_key, vendor, base_url, api_key, enabled,
                   operator_id, operator_name, updated_at
            FROM prj_model_providers WHERE provider_key = ?
            """;

    private static final String FIND_ALL_PROVIDERS_SQL = """
            SELECT provider_key, vendor, base_url, api_key, enabled,
                   operator_id, operator_name, updated_at
            FROM prj_model_providers ORDER BY provider_key
            """;

    private static final String FIND_ALL_CONFIGS_SQL = """
            SELECT config_key, provider_key, model_name, display_name, enabled,
                   thinking_enabled, operator_id, operator_name, updated_at
            FROM prj_model_configs ORDER BY config_key
            """;

    /** 全部候选行（含停用行——启停过滤归解析语义）；priority 升序＝队列序。 */
    private static final String FIND_CONFIG_CANDIDATES_SQL = """
            SELECT config_key, priority, provider_key, model_name, enabled,
                   operator_id, operator_name, updated_at
            FROM prj_model_config_candidates WHERE config_key = ?
            ORDER BY priority, provider_key, model_name
            """;

    /**
     * 整行 upsert：值面五件（含 vendor——#559/T5 受控值写面：显式值经校验、缺省
     * provider_key 同值）＋操作者两列；冲突分支全量重写（维护面无部分更新语义）。
     */
    private static final String SAVE_PROVIDER_SQL = """
            INSERT INTO prj_model_providers
                (provider_key, vendor, base_url, api_key, enabled, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider_key) DO UPDATE SET
                vendor = EXCLUDED.vendor,
                base_url = EXCLUDED.base_url,
                api_key = EXCLUDED.api_key,
                enabled = EXCLUDED.enabled,
                operator_id = EXCLUDED.operator_id,
                operator_name = EXCLUDED.operator_name,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String SAVE_CONFIG_SQL = """
            INSERT INTO prj_model_configs
                (config_key, provider_key, model_name, display_name, enabled,
                 thinking_enabled, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (config_key) DO UPDATE SET
                provider_key = EXCLUDED.provider_key,
                model_name = EXCLUDED.model_name,
                display_name = EXCLUDED.display_name,
                enabled = EXCLUDED.enabled,
                thinking_enabled = EXCLUDED.thinking_enabled,
                operator_id = EXCLUDED.operator_id,
                operator_name = EXCLUDED.operator_name,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String DELETE_PROVIDER_SQL =
            "DELETE FROM prj_model_providers WHERE provider_key = ?";

    /** 配置删除：候选行先随档清（配置＝行＋队列一个整体，不留孤儿候选）。 */
    private static final String DELETE_CONFIG_CANDIDATES_SQL =
            "DELETE FROM prj_model_config_candidates WHERE config_key = ?";

    /** 首选候选镜像 upsert（键＝config_key+priority 1；写面改配置伴随写，#556）。 */
    private static final String SAVE_CONFIG_PRIMARY_CANDIDATE_SQL = """
            INSERT INTO prj_model_config_candidates
                (config_key, priority, provider_key, model_name, enabled, operator_id, operator_name)
            VALUES (?, 1, ?, ?, TRUE, ?, ?)
            ON CONFLICT (config_key, priority) DO UPDATE SET
                provider_key = EXCLUDED.provider_key,
                model_name = EXCLUDED.model_name,
                operator_id = EXCLUDED.operator_id,
                operator_name = EXCLUDED.operator_name,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String DELETE_CONFIG_SQL =
            "DELETE FROM prj_model_configs WHERE config_key = ?";

    /** 候选行插入（整队重写的成员语句——#559/T5 编排写口）。 */
    private static final String INSERT_CONFIG_CANDIDATE_SQL = """
            INSERT INTO prj_model_config_candidates
                (config_key, priority, provider_key, model_name, enabled, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /** 绑定引用守卫：配置键出现在三座/边缘点的模型配置覆盖列即被引用。 */
    private static final String CONFIG_BOUND_SQL = """
            SELECT EXISTS (SELECT 1 FROM prj_agent_configs WHERE model_config_key = ?)
            """;

    /** 引用守卫：配置行直引或候选行引用（V79 起候选 FK 指通道——都是引用）。 */
    private static final String PROVIDER_REFERENCED_SQL = """
            SELECT EXISTS (SELECT 1 FROM prj_model_configs WHERE provider_key = ?)
                OR EXISTS (SELECT 1 FROM prj_model_config_candidates WHERE provider_key = ?)
            """;

    private static final String INSERT_PROVIDER_TRACE_SQL = """
            INSERT INTO prj_model_provider_traces
                (id, provider_key, old_vendor, old_base_url, old_api_key, old_enabled,
                 new_vendor, new_base_url, new_api_key, new_enabled, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_PROVIDER_TRACES_SQL = """
            SELECT id, provider_key, old_vendor, old_base_url, old_api_key, old_enabled,
                   new_vendor, new_base_url, new_api_key, new_enabled, operator_id,
                   operator_name, created_at
            FROM prj_model_provider_traces WHERE provider_key = ?
            ORDER BY created_at DESC, id DESC
            """;

    private static final String INSERT_CONFIG_TRACE_SQL = """
            INSERT INTO prj_model_config_traces
                (id, config_key, old_provider_key, old_model_name, old_display_name,
                 old_enabled, old_thinking_enabled, new_provider_key, new_model_name,
                 new_display_name, new_enabled, new_thinking_enabled, operator_id,
                 operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** 运行期切候选留痕（#557 append-only；created_at 显式落切换时刻——不依赖库时钟）。 */
    private static final String INSERT_CONFIG_SWITCH_TRACE_SQL = """
            INSERT INTO prj_model_config_switch_traces
                (id, config_key, from_provider_key, from_model_name,
                 to_provider_key, to_model_name, trigger_error, run_id, session_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_CONFIG_TRACES_SQL = """
            SELECT id, config_key, old_provider_key, old_model_name, old_display_name,
                   old_enabled, old_thinking_enabled, new_provider_key, new_model_name,
                   new_display_name, new_enabled, new_thinking_enabled, operator_id,
                   operator_name, created_at
            FROM prj_model_config_traces WHERE config_key = ?
            ORDER BY created_at DESC, id DESC
            """;

    /** 候选编排留痕（#559/T5 append-only；created_at 显式落——一次编排多痕共享
     *  事务时间戳，编排内单调纳秒偏移保证读面次序确定）。 */
    private static final String INSERT_CONFIG_CANDIDATE_TRACE_SQL = """
            INSERT INTO prj_model_config_candidate_traces
                (id, config_key, old_priority, old_provider_key, old_model_name, old_enabled,
                 new_priority, new_provider_key, new_model_name, new_enabled,
                 operator_id, operator_name, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_CONFIG_CANDIDATE_TRACES_SQL = """
            SELECT id, config_key, old_priority, old_provider_key, old_model_name, old_enabled,
                   new_priority, new_provider_key, new_model_name, new_enabled, operator_id,
                   operator_name, created_at
            FROM prj_model_config_candidate_traces WHERE config_key = ?
            ORDER BY created_at DESC, id DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcModelChannelStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ModelConfig findConfig(String configKey) {
        List<ModelConfig> rows = jdbcTemplate.query(FIND_CONFIG_SQL, JdbcModelChannelStore::mapConfig, configKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public ModelProvider findProvider(String providerKey) {
        List<ModelProvider> rows = jdbcTemplate.query(
                FIND_PROVIDER_SQL, JdbcModelChannelStore::mapProvider, providerKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<ModelProvider> findAllProviders() {
        return jdbcTemplate.query(FIND_ALL_PROVIDERS_SQL, JdbcModelChannelStore::mapProvider);
    }

    @Override
    public List<ModelConfig> findAllConfigs() {
        return jdbcTemplate.query(FIND_ALL_CONFIGS_SQL, JdbcModelChannelStore::mapConfig);
    }

    @Override
    public List<ModelConfigCandidate> findConfigCandidates(String configKey) {
        return jdbcTemplate.query(FIND_CONFIG_CANDIDATES_SQL,
                JdbcModelChannelStore::mapConfigCandidate, configKey);
    }

    @Override
    public void saveProvider(ModelProvider provider) {
        jdbcTemplate.update(SAVE_PROVIDER_SQL, provider.providerKey(), provider.vendor(),
                provider.baseUrl(), provider.apiKey(), provider.enabled(),
                provider.operatorId(), provider.operatorName());
    }

    @Override
    public void saveConfig(ModelConfig config) {
        jdbcTemplate.update(SAVE_CONFIG_SQL, config.configKey(), config.providerKey(),
                config.modelName(), config.displayName(), config.enabled(),
                config.thinkingEnabled(), config.operatorId(), config.operatorName());
    }

    @Override
    public void saveConfigPrimaryCandidate(String configKey, String providerKey, String modelName,
            String operatorId, String operatorName) {
        jdbcTemplate.update(SAVE_CONFIG_PRIMARY_CANDIDATE_SQL,
                configKey, providerKey, modelName, operatorId, operatorName);
    }

    @Override
    public void replaceConfigCandidates(String configKey, List<ModelConfigCandidate> candidates) {
        jdbcTemplate.update(DELETE_CONFIG_CANDIDATES_SQL, configKey);
        if (candidates.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_CONFIG_CANDIDATE_SQL, candidates.stream()
                .map(candidate -> new Object[] {candidate.configKey(), candidate.priority(),
                        candidate.providerKey(), candidate.modelName(), candidate.enabled(),
                        candidate.operatorId(), candidate.operatorName()})
                .toList());
    }

    @Override
    public void deleteProvider(String providerKey) {
        jdbcTemplate.update(DELETE_PROVIDER_SQL, providerKey);
    }

    @Override
    public void deleteConfig(String configKey) {
        // 先子后父：候选行 FK 指配置（V79），反序撞外键
        jdbcTemplate.update(DELETE_CONFIG_CANDIDATES_SQL, configKey);
        jdbcTemplate.update(DELETE_CONFIG_SQL, configKey);
    }

    @Override
    public boolean configBoundByAgentConfigs(String configKey) {
        Boolean bound = jdbcTemplate.queryForObject(CONFIG_BOUND_SQL, Boolean.class, configKey);
        return Boolean.TRUE.equals(bound);
    }

    @Override
    public boolean providerReferenced(String providerKey) {
        Boolean referenced = jdbcTemplate.queryForObject(
                PROVIDER_REFERENCED_SQL, Boolean.class, providerKey, providerKey);
        return Boolean.TRUE.equals(referenced);
    }

    @Override
    public void insertProviderTrace(ModelProviderTrace trace) {
        jdbcTemplate.update(INSERT_PROVIDER_TRACE_SQL, trace.id(), trace.providerKey(),
                trace.oldVendor(),
                trace.oldBaseUrl(), trace.oldApiKey(), trace.oldEnabled(),
                trace.newVendor(),
                trace.newBaseUrl(), trace.newApiKey(), trace.newEnabled(),
                trace.operatorId(), trace.operatorName());
    }

    @Override
    public List<ModelProviderTrace> findProviderTraces(String providerKey) {
        return jdbcTemplate.query(FIND_PROVIDER_TRACES_SQL,
                JdbcModelChannelStore::mapProviderTrace, providerKey);
    }

    @Override
    public void insertConfigTrace(ModelConfigTrace trace) {
        jdbcTemplate.update(INSERT_CONFIG_TRACE_SQL, trace.id(), trace.configKey(),
                trace.oldProviderKey(), trace.oldModelName(), trace.oldDisplayName(),
                trace.oldEnabled(), trace.oldThinkingEnabled(),
                trace.newProviderKey(), trace.newModelName(),
                trace.newDisplayName(), trace.newEnabled(), trace.newThinkingEnabled(),
                trace.operatorId(), trace.operatorName());
    }

    @Override
    public void insertConfigSwitchTrace(ModelConfigSwitchTrace trace) {
        jdbcTemplate.update(INSERT_CONFIG_SWITCH_TRACE_SQL, trace.id(), trace.configKey(),
                trace.fromProviderKey(), trace.fromModelName(),
                trace.toProviderKey(), trace.toModelName(), trace.triggerError(),
                trace.runId(), trace.sessionId(),
                trace.createdAt() == null ? null : java.sql.Timestamp.valueOf(trace.createdAt()));
    }

    @Override
    public List<ModelConfigTrace> findConfigTraces(String configKey) {
        return jdbcTemplate.query(FIND_CONFIG_TRACES_SQL, JdbcModelChannelStore::mapConfigTrace, configKey);
    }

    @Override
    public void insertConfigCandidateTrace(ModelConfigCandidateTrace trace) {
        jdbcTemplate.update(INSERT_CONFIG_CANDIDATE_TRACE_SQL, trace.id(), trace.configKey(),
                trace.oldPriority(), trace.oldProviderKey(), trace.oldModelName(),
                trace.oldEnabled(),
                trace.newPriority(), trace.newProviderKey(), trace.newModelName(),
                trace.newEnabled(),
                trace.operatorId(), trace.operatorName(),
                trace.createdAt() == null ? null : Timestamp.valueOf(trace.createdAt()));
    }

    @Override
    public List<ModelConfigCandidateTrace> findConfigCandidateTraces(String configKey) {
        return jdbcTemplate.query(FIND_CONFIG_CANDIDATE_TRACES_SQL,
                JdbcModelChannelStore::mapConfigCandidateTrace, configKey);
    }

    private static ModelConfig mapConfig(ResultSet rs, int rowNum) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ModelConfig(rs.getString("config_key"), rs.getString("provider_key"),
                rs.getString("model_name"), rs.getString("display_name"), rs.getBoolean("enabled"),
                rs.getBoolean("thinking_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }

    private static ModelProvider mapProvider(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ModelProvider(rs.getString("provider_key"), rs.getString("vendor"),
                rs.getString("base_url"),
                rs.getString("api_key"), rs.getBoolean("enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }

    private static ModelConfigCandidate mapConfigCandidate(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ModelConfigCandidate(rs.getString("config_key"), rs.getInt("priority"),
                rs.getString("provider_key"), rs.getString("model_name"),
                rs.getBoolean("enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }

    private static ModelProviderTrace mapProviderTrace(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ModelProviderTrace(rs.getLong("id"), rs.getString("provider_key"),
                rs.getString("old_vendor"),
                rs.getString("old_base_url"), rs.getString("old_api_key"),
                nullableBoolean(rs, "old_enabled"),
                rs.getString("new_vendor"),
                rs.getString("new_base_url"), rs.getString("new_api_key"),
                nullableBoolean(rs, "new_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }

    private static ModelConfigTrace mapConfigTrace(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ModelConfigTrace(rs.getLong("id"), rs.getString("config_key"),
                rs.getString("old_provider_key"), rs.getString("old_model_name"),
                rs.getString("old_display_name"), nullableBoolean(rs, "old_enabled"),
                nullableBoolean(rs, "old_thinking_enabled"),
                rs.getString("new_provider_key"), rs.getString("new_model_name"),
                rs.getString("new_display_name"), nullableBoolean(rs, "new_enabled"),
                nullableBoolean(rs, "new_thinking_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }

    /** 可空布尔列读（getBoolean 遇 NULL 回 false——null 列须显式判空，JDBC 老坑）。 */
    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    /** 可空整数列读（getInt 遇 NULL 回 0——同 nullableBoolean 坑）。 */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static ModelConfigCandidateTrace mapConfigCandidateTrace(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ModelConfigCandidateTrace(rs.getLong("id"), rs.getString("config_key"),
                nullableInt(rs, "old_priority"), rs.getString("old_provider_key"),
                rs.getString("old_model_name"), nullableBoolean(rs, "old_enabled"),
                nullableInt(rs, "new_priority"), rs.getString("new_provider_key"),
                rs.getString("new_model_name"), nullableBoolean(rs, "new_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }
}
