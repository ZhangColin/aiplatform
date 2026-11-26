package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.agentscope.EdgeModelSupplier;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.business.project.application.AgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.ModelConfigAppService;
import com.semgravio.aiplatform.business.project.domain.model.AgentEdgePoint;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

/**
 * 边缘调用点模型供数适配器（#366）：边缘点配置键 → 生效值（库值优先缺省回落，
 * {@link AgentConfigAppService} 装配读腿）→ 模型串＋通道注入（{@link
 * ModelConfigAppService} 解析单点，ADR-0031）——底座侧压缩装配消费面的反转接线
 * （知识在业务层、消费在底座，base 不依赖 business，先例
 * {@code ProfileToolkitSupplier}）。动态查库、不缓存：后台改配置下一轮装配/调用
 * 自然生效。#557 起携整候选队列（failover 装配面——边缘配置同获链式切换）。
 */
@Component
public class ConfigEdgeModelSupplier implements EdgeModelSupplier {

    private final AgentConfigAppService agentConfigs;
    private final ModelConfigAppService modelConfigs;

    public ConfigEdgeModelSupplier(AgentConfigAppService agentConfigs, ModelConfigAppService modelConfigs) {
        this.agentConfigs = agentConfigs;
        this.modelConfigs = modelConfigs;
    }

    @Override
    public EdgeModel compactionModel() {
        return resolve(AgentEdgePoint.COMPACTION);
    }

    private EdgeModel resolve(AgentEdgePoint point) {
        return new EdgeModel(modelConfigs.resolveQueue(
                agentConfigs.effectiveEdgeModelConfigKey(point)));
    }
}
