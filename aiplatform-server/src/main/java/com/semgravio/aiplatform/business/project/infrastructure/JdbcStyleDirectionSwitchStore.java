package com.semgravio.aiplatform.business.project.infrastructure;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.business.project.domain.repository.StyleDirectionSwitchStore;

/**
 * 风格层 kill-switch 存取实现（#543——照 {@link JdbcAgentConfigStore} 先例：
 * JdbcTemplate 原生 SQL；单行表 {@code prj_style_direction_switch}〔V77〕，读面
 * 动态查、无缓存）。行缺/表空＝缺省生效 true（迁移已种行——读面宽容，防误删
 * 行把风格层静默关停成「看似生效」之外的第三态：缺行也是生效态，关停必须
 * 显式 UPDATE enabled=FALSE）。
 */
@Component
public class JdbcStyleDirectionSwitchStore implements StyleDirectionSwitchStore {

    private static final String FIND_SQL =
            "SELECT enabled FROM prj_style_direction_switch WHERE id = 1";

    private final JdbcTemplate jdbcTemplate;

    public JdbcStyleDirectionSwitchStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean enabled() {
        List<Boolean> rows = jdbcTemplate.query(FIND_SQL,
                (rs, rowNum) -> rs.getBoolean("enabled"));
        return rows.isEmpty() || rows.get(0);
    }
}
