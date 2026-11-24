package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.ModelConfigAppService;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import java.util.List;

import com.semgravio.aiplatform.base.agentscope.EdgeModelSupplier;
import com.semgravio.aiplatform.base.agentscope.ModelCandidate;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.ModelChannel;

/**
 * 边缘/子智能体模型供数适配器（#366＋#424）：verify 子智能体配置覆盖的解析腿
 * ——executor 行 {@code verify_model_config_key} 库值优先（→ 配置解析产物），null＝随
 * 父级（返回 null，装配侧回落执行体生效档——缺省同档不降）。
 */
class ConfigEdgeModelSupplierTest {

    private final AgentConfigAppService agentConfigs = mock(AgentConfigAppService.class);
    private final ModelConfigAppService modelConfigs = mock(ModelConfigAppService.class);
    private final ConfigEdgeModelSupplier supplier =
            new ConfigEdgeModelSupplier(agentConfigs, modelConfigs);

    @Test
    void given_no_verify_override_when_subagent_model_then_null_follows_parent() {
        // #424 缺省随执行体档：覆盖 null → 供数 null（装配侧回落父级实例——含
        // 路由升档跟随，#406「缺省同档不降」）
        when(agentConfigs.effectiveOf(AgentProfile.EXECUTOR)).thenReturn(
                new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                        true, true, false, true, null, null, null, false, false, null, 3, 2, true, true, true,
                        false, true));

        assertThat(supplier.subagentModel()).isNull();
    }

    @Test
    void given_verify_override_when_subagent_model_then_resolved_tier() {
        // 覆盖档独立解析：配置键 → 模型串＋通道注入（ModelConfigAppService 解析单点）
        when(agentConfigs.effectiveOf(AgentProfile.EXECUTOR)).thenReturn(
                new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                        true, true, false, true, null, null, null, false, false, "pro", 5, 2, true, true, true,
                        false, true));
        when(modelConfigs.resolveQueue("pro")).thenReturn(new ModelCandidateQueue("pro",
                List.of(new ModelCandidate("volcengine", "deepseek-v4-pro-ga-260731",
                        new ModelChannel("https://ark.example/api/v3", "sk-a", false)))));

        EdgeModelSupplier.EdgeModel model = supplier.subagentModel();

        assertThat(model).isNotNull();
        assertThat(model.modelString()).isEqualTo("volcengine:deepseek-v4-pro-ga-260731");
        assertThat(model.channel()).isNotNull();
        assertThat(model.channel().baseUrl()).isEqualTo("https://ark.example/api/v3");
    }
}
