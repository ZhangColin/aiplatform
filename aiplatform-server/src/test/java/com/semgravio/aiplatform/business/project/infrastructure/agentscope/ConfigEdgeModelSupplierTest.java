package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.ModelTierAppService;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.base.agentscope.EdgeModelSupplier;

/**
 * 边缘/子智能体模型供数适配器（#366＋#424）：verify 子智能体档位覆盖的解析腿
 * ——executor 行 {@code verify_model_id} 库值优先（→ 档位解析产物），null＝随
 * 父级（返回 null，装配侧回落执行体生效档——缺省同档不降）。
 */
class ConfigEdgeModelSupplierTest {

    private final AgentConfigAppService agentConfigs = mock(AgentConfigAppService.class);
    private final ModelTierAppService modelTiers = mock(ModelTierAppService.class);
    private final ConfigEdgeModelSupplier supplier =
            new ConfigEdgeModelSupplier(agentConfigs, modelTiers);

    @Test
    void given_no_verify_override_when_subagent_model_then_null_follows_parent() {
        // #424 缺省随执行体档：覆盖 null → 供数 null（装配侧回落父级实例——含
        // 路由升档跟随，#406「缺省同档不降」）
        when(agentConfigs.effectiveOf(AgentProfile.EXECUTOR)).thenReturn(
                new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                        true, true, false, true, null, null, null, null, 3, 2, true, true, true));

        assertThat(supplier.subagentModel()).isNull();
    }

    @Test
    void given_verify_override_when_subagent_model_then_resolved_tier() {
        // 覆盖档独立解析：档位键 → 模型串＋通道注入（ModelTierAppService 解析单点）
        when(agentConfigs.effectiveOf(AgentProfile.EXECUTOR)).thenReturn(
                new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                        true, true, false, true, null, null, null, "pro", 5, 2, true, true, true));
        when(modelTiers.resolve("pro")).thenReturn(new ModelTierAppService.ResolvedModel(
                "pro", "volcengine", "deepseek-v4-pro-ga-260731",
                "https://ark.example/api/v3", "sk-a", false));

        EdgeModelSupplier.EdgeModel model = supplier.subagentModel();

        assertThat(model).isNotNull();
        assertThat(model.modelString()).isEqualTo("volcengine:deepseek-v4-pro-ga-260731");
        assertThat(model.channel()).isNotNull();
        assertThat(model.channel().baseUrl()).isEqualTo("https://ark.example/api/v3");
    }
}
