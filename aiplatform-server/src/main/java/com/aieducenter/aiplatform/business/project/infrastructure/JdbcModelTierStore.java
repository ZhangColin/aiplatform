package com.aieducenter.aiplatform.business.project.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aieducenter.aiplatform.business.project.domain.model.ModelProvider;
import com.aieducenter.aiplatform.business.project.domain.model.ModelTier;
import com.aieducenter.aiplatform.business.project.domain.repository.ModelTierStore;

/**
 * 模型档位/供应商通道存取实现（JdbcTemplate 原生 SQL，照 {@code JdbcAgentConfigStore}
 * 先例）：主键 SELECT，装配读腿每轮命令构建时查。写面（后台维护＋留痕）属 #367。
 */
@Component
public class JdbcModelTierStore implements ModelTierStore {

    private static final String FIND_TIER_SQL = """
            SELECT tier_key, provider_key, model_name, enabled
            FROM prj_model_tiers WHERE tier_key = ?
            """;

    private static final String FIND_PROVIDER_SQL = """
            SELECT provider_key, base_url, api_key, enabled
            FROM prj_model_providers WHERE provider_key = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcModelTierStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ModelTier findTier(String tierKey) {
        List<ModelTier> rows = jdbcTemplate.query(FIND_TIER_SQL, JdbcModelTierStore::mapTier, tierKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public ModelProvider findProvider(String providerKey) {
        List<ModelProvider> rows = jdbcTemplate.query(
                FIND_PROVIDER_SQL, JdbcModelTierStore::mapProvider, providerKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static ModelTier mapTier(ResultSet rs, int rowNum) throws SQLException {
        return new ModelTier(rs.getString("tier_key"), rs.getString("provider_key"),
                rs.getString("model_name"), rs.getBoolean("enabled"));
    }

    private static ModelProvider mapProvider(ResultSet rs, int rowNum)
            throws SQLException {
        return new ModelProvider(rs.getString("provider_key"), rs.getString("base_url"),
                rs.getString("api_key"), rs.getBoolean("enabled"));
    }
}
