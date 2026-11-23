package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 生成内核运营护栏三数值＋输出上限开关的配置断言（#597，ADR-0054／#593 用户故事 8/10）：
 * 护栏（300 轮／45 分钟／60M prompt）为运营兜底、非工作语义、不重新标定；输出上限
 * 是每次请求的配置开关、默认 32768、可调。本类钉「配置项存在＋默认值＋可调」——
 * 接线行为（到顶关工具／每次请求携带 max_tokens）由 KernelAgentGuardrailsTest 与
 * KernelAgentAssemblyTest 钉守。
 */
class KernelGuardConfigTest {

    @Test
    void given_default_properties_when_read_then_guardrails_and_output_cap_pinned() {
        AgentscopeProperties properties = new AgentscopeProperties();

        assertThat(properties.getKernelGuardRounds()).isEqualTo(300);
        assertThat(properties.getKernelGuardDuration()).isEqualTo(Duration.ofMinutes(45));
        assertThat(properties.getKernelGuardPromptTokens()).isEqualTo(60_000_000L);
        assertThat(properties.getKernelMaxOutputTokens()).isEqualTo(32768);
    }

    @Test
    void given_operator_override_when_set_then_values_adjustable() {
        AgentscopeProperties properties = new AgentscopeProperties();
        properties.setKernelGuardRounds(120);
        properties.setKernelGuardDuration(Duration.ofMinutes(20));
        properties.setKernelGuardPromptTokens(20_000_000L);
        properties.setKernelMaxOutputTokens(8192);

        assertThat(properties.getKernelGuardRounds()).isEqualTo(120);
        assertThat(properties.getKernelGuardDuration()).isEqualTo(Duration.ofMinutes(20));
        assertThat(properties.getKernelGuardPromptTokens()).isEqualTo(20_000_000L);
        assertThat(properties.getKernelMaxOutputTokens()).isEqualTo(8192);
    }
}
