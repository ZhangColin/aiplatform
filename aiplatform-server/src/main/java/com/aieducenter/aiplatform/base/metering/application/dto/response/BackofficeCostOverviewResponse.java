package com.aieducenter.aiplatform.base.metering.application.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;

/**
 * 平台成本全局总览响应（#161 成本运营）：纯平台 token 成本观测——与报价脱钩
 * （平台付出金额，无建议售价推导）、币种分桶直读不折算（键 = ISO 4217 币种码）。
 *
 * <p>无生效单价的分量不进 {@code cost}（不伪装 0、不阻断聚合）——未配价观测走
 * 用量驱动的 unpriced 端点。窗口内无事件时 total 全零、各分桶为空，不是错误。</p>
 *
 * @param from        窗口起点（含；null = 不限，原样回显）
 * @param to          窗口终点（不含；null = 不限，原样回显）
 * @param total       总量（五档分列，全平台跨项目）
 * @param cost        平台成本（币种分桶；全未配价/无事件时为空 Map）
 * @param byModel     分模型聚合（provider + model 为单价表匹配键）
 * @param byAgentKind 分智能体聚合（dims.agentKind 原值 + agentKindName 中文名随行；
 *                    无维度的事件不参与该分桶）
 */
public record BackofficeCostOverviewResponse(
        Instant from,
        Instant to,
        TokenUsage total,
        Map<String, BigDecimal> cost,
        List<ModelUsage> byModel,
        List<AgentKindUsage> byAgentKind
) {

    public BackofficeCostOverviewResponse {
        // 保序拷贝：cost 键序 = 服务层排定的币种码序（API 输出确定性）
        cost = cost == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byAgentKind = byAgentKind == null ? List.of() : List.copyOf(byAgentKind);
    }

    /**
     * 分模型聚合项（provider + model 为单价表匹配键）。
     */
    public record ModelUsage(String provider, String model, TokenUsage tokens) {
    }

    /**
     * 分智能体聚合项（agentKind = dims 透传原值，写侧终态口径 main/executor，
     * 非主链用途标记照原样；agentKindName 中文名随行——#186 枚举出口配 *Name，
     * 经 {@code AgentKindNames} 端口回解正本 AgentProfile，辅助标记为 null）。
     */
    public record AgentKindUsage(String agentKind, String agentKindName, TokenUsage tokens) {
    }
}
