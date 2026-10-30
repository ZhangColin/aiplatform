package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.agentscope.EdgeModelSupplier;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.ModelTierAppService;
import com.semgravio.aiplatform.business.project.domain.model.AgentEdgePoint;

/**
 * 边缘调用点模型供数适配器（#366）：边缘点档位键 → 生效值（库值优先缺省回落，
 * {@link AgentConfigAppService} 装配读腿）→ 模型串＋通道注入（{@link
 * ModelTierAppService} 解析单点，ADR-0031）——底座侧消费面（压缩装配/技能预检）
 * 的反转接线（知识在业务层、消费在底座，base 不依赖 business，先例
 * {@code ProfileToolkitSupplier}）。动态查库、不缓存：后台改档下一轮装配/调用
 * 自然生效。
 */
@Component
public class ConfigEdgeModelSupplier implements EdgeModelSupplier {

    private final AgentConfigAppService agentConfigs;
    private final ModelTierAppService modelTiers;

    public ConfigEdgeModelSupplier(AgentConfigAppService agentConfigs, ModelTierAppService modelTiers) {
        this.agentConfigs = agentConfigs;
        this.modelTiers = modelTiers;
    }

    @Override
    public EdgeModel compactionModel() {
        return resolve(AgentEdgePoint.COMPACTION);
    }

    @Override
    public EdgeModel precheckModel() {
        return resolve(AgentEdgePoint.PRECHECK);
    }

    private EdgeModel resolve(AgentEdgePoint point) {
        ModelTierAppService.ResolvedModel model =
                modelTiers.resolve(agentConfigs.effectiveEdgeModelId(point));
        return new EdgeModel(model.modelString(), model.channel());
    }
}
