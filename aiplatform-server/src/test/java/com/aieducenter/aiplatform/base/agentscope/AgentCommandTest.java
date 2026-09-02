package com.aieducenter.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentCommand} 入参校验：runId/prompt/sessionId 必填，其余可空各取默认；
 * workspaceId/streamCorrelation 透传 + 信封契约校验。
 */
class AgentCommandTest {

    private AgentCommand command(String runId, String prompt, String sessionId) {
        return new AgentCommand(runId, prompt, null, null, sessionId, null,
                null, null, null);
    }

    @Test
    void given_blank_run_id_when_construct_then_rejected() {
        assertThatThrownBy(() -> command(" ", "你好", "s-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId/prompt/sessionId 必填");
    }

    @Test
    void given_blank_prompt_when_construct_then_rejected() {
        assertThatThrownBy(() -> command("run-1", "", "s-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_blank_session_id_when_construct_then_rejected() {
        assertThatThrownBy(() -> command("run-1", "你好", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_full_command_when_construct_then_fields_kept() {
        UsageContext usage = new UsageContext("prj-1", Map.of("agentKind", "ba"));

        AgentCommand command = new AgentCommand(
                "run-1", "你好", "你是 BA", "deepseek:deepseek-v4-flash",
                "s-1", "alice", usage, "42", Map.of("projectId", "42"));

        assertThat(command.runId()).isEqualTo("run-1");
        assertThat(command.prompt()).isEqualTo("你好");
        assertThat(command.systemPrompt()).isEqualTo("你是 BA");
        assertThat(command.modelString()).isEqualTo("deepseek:deepseek-v4-flash");
        assertThat(command.sessionId()).isEqualTo("s-1");
        assertThat(command.userId()).isEqualTo("alice");
        assertThat(command.usageContext()).isEqualTo(usage);
        assertThat(command.workspaceId()).isEqualTo("42");
        assertThat(command.streamCorrelation()).containsEntry("projectId", "42");
    }

    @Test
    void given_null_correlation_when_construct_then_normalized_to_empty() {
        AgentCommand command = new AgentCommand(
                "run-1", "你好", null, null, "s-1", null, null, null, null);

        assertThat(command.streamCorrelation()).isEmpty();
    }

    @Test
    void given_correlation_with_type_key_when_construct_then_rejected() {
        assertThatThrownBy(() -> new AgentCommand(
                "run-1", "你好", null, null, "s-1", null, null, null, Map.of("type", "evil")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamCorrelation 禁含 type 键（信封契约）");
    }

    @Test
    void given_mutable_dims_when_construct_usage_context_then_defensively_copied() {
        Map<String, String> dims = new HashMap<>(Map.of("agentKind", "ba"));
        UsageContext usage = new UsageContext("prj-1", dims);

        dims.put("agentKind", "tampered");

        assertThat(usage.dims()).containsEntry("agentKind", "ba");
    }

    @Test
    void given_convenience_forms_when_construct_then_workspace_read_write_default() {
        // 兼容形（取名 / BA 对话）缺省读写面：workspaceReadOnly 恒 false（#47 前调用面不变）
        assertThat(command("run-1", "你好", "s-1").workspaceReadOnly()).isFalse();
        assertThat(new AgentCommand("run-1", "你好", null, null, "s-1", null, null, null,
                Map.of(), "BA").workspaceReadOnly()).isFalse();
    }

    @Test
    void given_canonical_form_when_construct_then_workspace_read_only_kept() {
        AgentCommand command = new AgentCommand("run-1", "咨询", null, null, "s-1", null,
                null, "42", Map.of(), null, false, "ASSISTANT", true);

        assertThat(command.workspaceReadOnly()).isTrue(); // 助理咨询姿态：写面结构性关闭
    }
}
