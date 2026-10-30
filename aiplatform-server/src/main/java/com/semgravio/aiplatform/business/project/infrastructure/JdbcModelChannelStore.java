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
import com.semgravio.aiplatform.business.project.domain.model.ModelTier;
import com.semgravio.aiplatform.business.project.domain.model.ModelTierTrace;
import com.semgravio.aiplatform.business.project.domain.repository.ModelChannelStore;

/**
 * 模型供应商通道/档位存取实现（JdbcTemplate 原生 SQL，照 {@code JdbcAgentConfigStore}
 * 先例）：装配读腿（{@link #findTier}/{@link #findProvider}）每轮命令构建时查；
 * 写腿（后台维护面 CRUD＋留痕）整行 upsert＋append-only。留痕读面不带 apiKey
 * 掩码——掩码是应用层读面职责（{@code ModelProvider#maskApiKey} 单点），存取层
 * 如实搬运（明文与行本体同保护级）。
 */
@Component
public class JdbcModelChannelStore implements ModelChannelStore {

    private static final String FIND_TIER_SQL = """
            SELECT tier_key, provider_key, model_name, display_name, enabled,
                   operator_id, operator_name, updated_at
            FROM prj_model_tiers WHERE tier_key = ?
            """;

    private static final String FIND_PROVIDER_SQL = """
            SELECT provider_key, base_url, api_key, enabled,
                   operator_id, operator_name, updated_at
            FROM prj_model_providers WHERE provider_key = ?
            """;

    private static final String FIND_ALL_PROVIDERS_SQL = """
            SELECT provider_key, base_url, api_key, enabled,
                   operator_id, operator_name, updated_at
            FROM prj_model_providers ORDER BY provider_key
            """;

    private static final String FIND_ALL_TIERS_SQL = """
            SELECT tier_key, provider_key, model_name, display_name, enabled,
                   operator_id, operator_name, updated_at
            FROM prj_model_tiers ORDER BY tier_key
            """;

    /** 整行 upsert：值面四件＋操作者两列；冲突分支全量重写（维护面无部分更新语义）。 */
    private static final String SAVE_PROVIDER_SQL = """
            INSERT INTO prj_model_providers
                (provider_key, base_url, api_key, enabled, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider_key) DO UPDATE SET
                base_url = EXCLUDED.base_url,
                api_key = EXCLUDED.api_key,
                enabled = EXCLUDED.enabled,
                operator_id = EXCLUDED.operator_id,
                operator_name = EXCLUDED.operator_name,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String SAVE_TIER_SQL = """
            INSERT INTO prj_model_tiers
                (tier_key, provider_key, model_name, display_name, enabled,
                 operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tier_key) DO UPDATE SET
                provider_key = EXCLUDED.provider_key,
                model_name = EXCLUDED.model_name,
                display_name = EXCLUDED.display_name,
                enabled = EXCLUDED.enabled,
                operator_id = EXCLUDED.operator_id,
                operator_name = EXCLUDED.operator_name,
                updated_at = CURRENT_TIMESTAMP
            """;

    private static final String DELETE_PROVIDER_SQL =
            "DELETE FROM prj_model_providers WHERE provider_key = ?";

    private static final String DELETE_TIER_SQL =
            "DELETE FROM prj_model_tiers WHERE tier_key = ?";

    /** 绑定引用守卫：档位键出现在三座/边缘点的模型档位或路由强档覆盖列即被引用。 */
    private static final String TIER_BOUND_SQL = """
            SELECT EXISTS (SELECT 1 FROM prj_agent_configs
                WHERE model_id = ? OR routing_strong_model_id = ?)
            """;

    private static final String PROVIDER_REFERENCED_SQL =
            "SELECT EXISTS (SELECT 1 FROM prj_model_tiers WHERE provider_key = ?)";

    private static final String INSERT_PROVIDER_TRACE_SQL = """
            INSERT INTO prj_model_provider_traces
                (id, provider_key, old_base_url, old_api_key, old_enabled,
                 new_base_url, new_api_key, new_enabled, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_PROVIDER_TRACES_SQL = """
            SELECT id, provider_key, old_base_url, old_api_key, old_enabled,
                   new_base_url, new_api_key, new_enabled, operator_id, operator_name,
                   created_at
            FROM prj_model_provider_traces WHERE provider_key = ?
            ORDER BY created_at DESC, id DESC
            """;

    private static final String INSERT_TIER_TRACE_SQL = """
            INSERT INTO prj_model_tier_traces
                (id, tier_key, old_provider_key, old_model_name, old_display_name,
                 old_enabled, new_provider_key, new_model_name, new_display_name,
                 new_enabled, operator_id, operator_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_TIER_TRACES_SQL = """
            SELECT id, tier_key, old_provider_key, old_model_name, old_display_name,
                   old_enabled, new_provider_key, new_model_name, new_display_name,
                   new_enabled, operator_id, operator_name, created_at
            FROM prj_model_tier_traces WHERE tier_key = ?
            ORDER BY created_at DESC, id DESC
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcModelChannelStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ModelTier findTier(String tierKey) {
        List<ModelTier> rows = jdbcTemplate.query(FIND_TIER_SQL, JdbcModelChannelStore::mapTier, tierKey);
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
    public List<ModelTier> findAllTiers() {
        return jdbcTemplate.query(FIND_ALL_TIERS_SQL, JdbcModelChannelStore::mapTier);
    }

    @Override
    public void saveProvider(ModelProvider provider) {
        jdbcTemplate.update(SAVE_PROVIDER_SQL, provider.providerKey(), provider.baseUrl(),
                provider.apiKey(), provider.enabled(), provider.operatorId(), provider.operatorName());
    }

    @Override
    public void saveTier(ModelTier tier) {
        jdbcTemplate.update(SAVE_TIER_SQL, tier.tierKey(), tier.providerKey(),
                tier.modelName(), tier.displayName(), tier.enabled(),
                tier.operatorId(), tier.operatorName());
    }

    @Override
    public void deleteProvider(String providerKey) {
        jdbcTemplate.update(DELETE_PROVIDER_SQL, providerKey);
    }

    @Override
    public void deleteTier(String tierKey) {
        jdbcTemplate.update(DELETE_TIER_SQL, tierKey);
    }

    @Override
    public boolean tierBoundByAgentConfigs(String tierKey) {
        Boolean bound = jdbcTemplate.queryForObject(TIER_BOUND_SQL, Boolean.class, tierKey, tierKey);
        return Boolean.TRUE.equals(bound);
    }

    @Override
    public boolean providerReferencedByTiers(String providerKey) {
        Boolean referenced = jdbcTemplate.queryForObject(
                PROVIDER_REFERENCED_SQL, Boolean.class, providerKey);
        return Boolean.TRUE.equals(referenced);
    }

    @Override
    public void insertProviderTrace(ModelProviderTrace trace) {
        jdbcTemplate.update(INSERT_PROVIDER_TRACE_SQL, trace.id(), trace.providerKey(),
                trace.oldBaseUrl(), trace.oldApiKey(), trace.oldEnabled(),
                trace.newBaseUrl(), trace.newApiKey(), trace.newEnabled(),
                trace.operatorId(), trace.operatorName());
    }

    @Override
    public List<ModelProviderTrace> findProviderTraces(String providerKey) {
        return jdbcTemplate.query(FIND_PROVIDER_TRACES_SQL,
                JdbcModelChannelStore::mapProviderTrace, providerKey);
    }

    @Override
    public void insertTierTrace(ModelTierTrace trace) {
        jdbcTemplate.update(INSERT_TIER_TRACE_SQL, trace.id(), trace.tierKey(),
                trace.oldProviderKey(), trace.oldModelName(), trace.oldDisplayName(),
                trace.oldEnabled(), trace.newProviderKey(), trace.newModelName(),
                trace.newDisplayName(), trace.newEnabled(),
                trace.operatorId(), trace.operatorName());
    }

    @Override
    public List<ModelTierTrace> findTierTraces(String tierKey) {
        return jdbcTemplate.query(FIND_TIER_TRACES_SQL, JdbcModelChannelStore::mapTierTrace, tierKey);
    }

    private static ModelTier mapTier(ResultSet rs, int rowNum) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ModelTier(rs.getString("tier_key"), rs.getString("provider_key"),
                rs.getString("model_name"), rs.getString("display_name"), rs.getBoolean("enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }

    private static ModelProvider mapProvider(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ModelProvider(rs.getString("provider_key"), rs.getString("base_url"),
                rs.getString("api_key"), rs.getBoolean("enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                updatedAt == null ? null : updatedAt.toLocalDateTime());
    }

    private static ModelProviderTrace mapProviderTrace(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ModelProviderTrace(rs.getLong("id"), rs.getString("provider_key"),
                rs.getString("old_base_url"), rs.getString("old_api_key"),
                nullableBoolean(rs, "old_enabled"),
                rs.getString("new_base_url"), rs.getString("new_api_key"),
                nullableBoolean(rs, "new_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }

    private static ModelTierTrace mapTierTrace(ResultSet rs, int rowNum)
            throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ModelTierTrace(rs.getLong("id"), rs.getString("tier_key"),
                rs.getString("old_provider_key"), rs.getString("old_model_name"),
                rs.getString("old_display_name"), nullableBoolean(rs, "old_enabled"),
                rs.getString("new_provider_key"), rs.getString("new_model_name"),
                rs.getString("new_display_name"), nullableBoolean(rs, "new_enabled"),
                rs.getString("operator_id"), rs.getString("operator_name"),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }

    /** 可空布尔列读（getBoolean 遇 NULL 回 false——null 列须显式判空，JDBC 老坑）。 */
    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}
