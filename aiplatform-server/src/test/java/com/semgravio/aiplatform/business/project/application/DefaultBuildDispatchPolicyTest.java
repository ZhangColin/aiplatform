package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 构建派发规则缝缺省实现（#332 额度/收费通用缝留位）：v1 缺省免费不限次——两挂点
 * 皆无感（规则说明位空、执行前确认位不激活），行为透明。本用例钉住「缺省即无感」
 * 这一不变量：防未来改缺省意外激活计费说明或执行前确认拦（缝在结构在、行为无感）。
 */
class DefaultBuildDispatchPolicyTest {

    private final BuildDispatchPolicy policy = new DefaultBuildDispatchPolicy();

    @Test
    void given_default_policy_when_rule_notice_then_null_no_notice() {
        assertThat(policy.ruleNotice(1L)).isNull();
    }

    @Test
    void given_default_policy_when_requires_pre_execution_confirmation_then_false_no_gate() {
        assertThat(policy.requiresPreExecutionConfirmation(1L)).isFalse();
    }
}
