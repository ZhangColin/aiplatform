package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.util.List;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;

/**
 * 项目用量响应：总量 + 分模型 + 分智能体聚合。
 *
 * <p>平台成本不进用户面（#167 收口：成本归运营口径）——计量域聚合仍含
 * cost/unpriced，由后台成本读面（BackofficeCost / BackofficeProject）承接。</p>
 *
 * @param projectId   项目标识（subject）
 * @param total       总量（五档分列）
 * @param byModel     分模型聚合（provider + model 为单价表匹配键）
 * @param byAgentKind 分智能体聚合（dims.agentKind 维度；智能体种类为稳定键 + 展示名）
 */
public record ProjectUsageResponse(
        String projectId,
        TokenUsage total,
        List<ModelUsage> byModel,
        List<AgentKindUsage> byAgentKind
) {

    public ProjectUsageResponse {
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byAgentKind = byAgentKind == null ? List.of() : List.copyOf(byAgentKind);
    }

    /**
     * 分模型聚合项。
     */
    public record ModelUsage(String provider, String model, TokenUsage tokens) {
    }

    /**
     * 分智能体聚合项（agentKind = 稳定键 ba/coder/naming，agentKindLabel = 展示名；
     * 非主链角色的用途标记（如 naming）agentKindLabel 为 null）。
     */
    public record AgentKindUsage(String agentKind, String agentKindLabel, TokenUsage tokens) {
    }
}
