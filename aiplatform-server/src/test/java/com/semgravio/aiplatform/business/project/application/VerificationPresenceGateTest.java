package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

/**
 * 验证在场硬门（#424）开关化（#433 开关承载面；#435 翻转缺省 false＝闸关）：闸
 * 判定接入运营配置面开关（executor 行 {@code verification_presence_gate_enabled}）
 * ——行为矩阵锚外部判定：闸开＝本 run 无验证登记抛拒绝回执（运营切回旧行为）；
 * 闸关＝无登记也放行（#435 缺省态——砍验证税落地）。
 */
class VerificationPresenceGateTest {

    private final VerificationFacts verificationFacts = new VerificationFacts();
    private final AgentConfigAppService agentConfigs = Mockito.mock(AgentConfigAppService.class);
    private final VerificationPresenceGate gate =
            new VerificationPresenceGate(verificationFacts, agentConfigs);

    /** 闸态装配桩：true＝闸开（#435 起为运营切回旧行为）、false＝闸关（缺省态）。 */
    private void givenGateEnabled(boolean enabled) {
        when(agentConfigs.effectiveOf(AgentProfile.EXECUTOR)).thenReturn(
                new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                        true, true, false, true, null, null, null, false, false, null, 3, 2,
                        enabled, true, true, true, false));
    }

    @Test
    void given_gate_open_and_no_registration_when_require_presence_then_rejected() {
        givenGateEnabled(true);

        assertThatThrownBy(() -> gate.requirePresence("42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("验证在场未过");
    }

    @Test
    void given_gate_open_and_registration_present_when_require_presence_then_passes() {
        givenGateEnabled(true);
        verificationFacts.record("42", List.of(
                new VerificationFacts.Check("首页可访问", "browser", true, null)));

        assertThatCode(() -> gate.requirePresence("42")).doesNotThrowAnyException();
    }

    @Test
    void given_gate_closed_and_no_registration_when_require_presence_then_passes() {
        // 闸关（#432 砍验证税开关态）：无验证登记也不拦——拒绝回执与重跑税不再发生
        givenGateEnabled(false);

        assertThatCode(() -> gate.requirePresence("42")).doesNotThrowAnyException();
    }
}
