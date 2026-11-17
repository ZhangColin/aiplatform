package com.semgravio.aiplatform.business.project.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;

/**
 * 风格层 kill-switch 存取适配器面（#543，编写规范 8.1 适配器测试——照
 * {@link JdbcAgentConfigStore} 同目录先例：JdbcTemplate 原生 SQL、真库、
 * 无需 Mock）：单行表读面三态钉死——种行缺省 TRUE（V77 迁移已种）、显式
 * UPDATE FALSE＝关停、行被误删＝宽容回落 TRUE（防第三态：缺行也是生效态，
 * 关停必须显式）。读面动态查无缓存＝每次 enabled() 直读库（改档即生效）。
 */
@IntegrationTest
class JdbcStyleDirectionSwitchStoreTest {

    @Autowired
    private JdbcStyleDirectionSwitchStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 共享单行全局态——绝不把关停态/缺行态留给下游用例（行被删用例
        // 走 upsert 补回，UPDATE 单语句覆盖不了缺行）
        jdbcTemplate.update("""
                INSERT INTO prj_style_direction_switch (id, enabled) VALUES (1, TRUE)
                ON CONFLICT (id) DO UPDATE SET enabled = TRUE
                """);
    }

    @Test
    void given_seeded_row_when_enabled_then_default_true() {
        assertThat(store.enabled()).isTrue();
    }

    @Test
    void given_explicitly_disabled_when_enabled_then_false() {
        jdbcTemplate.update(
                "UPDATE prj_style_direction_switch SET enabled = FALSE WHERE id = 1");

        assertThat(store.enabled()).isFalse();
    }

    @Test
    void given_row_missing_when_enabled_then_falls_back_true() {
        // 误删行＝缺省生效态（不是静默关停——关停必须显式 UPDATE）
        jdbcTemplate.update("DELETE FROM prj_style_direction_switch WHERE id = 1");

        assertThat(store.enabled()).isTrue();
    }
}
