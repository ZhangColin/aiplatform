package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.ModelSwitchTraceSink;

/**
 * 运行期切候选留痕落库缝（#557，ADR-0053 ③）：base 端口 {@link ModelSwitchTraceSink}
 * 的业务实现（{@link PersistingModelSwitchTraceSink}）→ {@code ModelChannelStore}
 * → {@code prj_model_config_switch_traces}（V79 建表、写腿随本票落）。真测试库
 * 观测：一痕一行、全字段如实、append-only（无 update 面）。切换决策语义归
 * {@code FailoverModelTest}／{@code ModelFailoverInjectionTest}——本缝只钉落库。
 */
@IntegrationTest
class PersistingModelSwitchTraceSinkTest {

    @Autowired
    private ModelSwitchTraceSink sink;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM prj_model_config_switch_traces WHERE config_key = 't-fo'");
    }

    @Test
    void given_switch_trace_when_record_then_row_persisted_with_all_fields() {
        Instant occurredAt = Instant.parse("2026-11-19T12:23:45Z");

        sink.record(new ModelSwitchTraceSink.Trace(
                "t-fo",
                "t-ark", "primary-model",
                "t-ark2", "backup-model",
                "AuthenticationException: Error code: 401 - The API key doesn't exist",
                "run-fo-1", "s-fo-1", occurredAt));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT config_key, from_provider_key, from_model_name, to_provider_key,"
                        + " to_model_name, trigger_error, run_id, session_id, created_at"
                        + " FROM prj_model_config_switch_traces WHERE config_key = 't-fo'"))
                .containsEntry("config_key", "t-fo")
                .containsEntry("from_provider_key", "t-ark")
                .containsEntry("from_model_name", "primary-model")
                .containsEntry("to_provider_key", "t-ark2")
                .containsEntry("to_model_name", "backup-model")
                .containsEntry("trigger_error",
                        "AuthenticationException: Error code: 401 - The API key doesn't exist")
                .containsEntry("run_id", "run-fo-1")
                .containsEntry("session_id", "s-fo-1")
                .containsKey("created_at");
    }

    @Test
    void given_null_run_context_when_record_then_row_persisted_with_nullable_legs() {
        // 非 run 装配面切换（无计量语境）：run/session 可空落库（schema 允许）
        sink.record(new ModelSwitchTraceSink.Trace(
                "t-fo", "t-ark", "primary-model", "t-ark2", "backup-model",
                "OpenAIException: Error code: 402 - insufficient balance",
                null, null, Instant.now()));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT run_id, session_id FROM prj_model_config_switch_traces"
                        + " WHERE config_key = 't-fo'"))
                .containsEntry("run_id", null)
                .containsEntry("session_id", null);
    }
}
